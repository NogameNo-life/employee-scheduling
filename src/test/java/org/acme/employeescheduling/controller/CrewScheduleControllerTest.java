package org.acme.employeescheduling.controller;

import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.hasSize;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.YearMonth;
import java.util.HashSet;
import java.util.List;
import java.util.Map;

import org.acme.employeescheduling.config.ClockConfig;
import org.acme.employeescheduling.config.SessionHeader;
import org.acme.employeescheduling.domain.Crew;
import org.acme.employeescheduling.domain.Employee;
import org.acme.employeescheduling.domain.Line;
import org.acme.employeescheduling.domain.Role;
import org.acme.employeescheduling.repository.CrewScheduleRepository;
import org.acme.employeescheduling.service.CrewData;
import org.acme.employeescheduling.service.CrewDataCache;
import org.acme.employeescheduling.service.CrewRosterService;
import org.acme.employeescheduling.service.CrewScheduleEditor;
import org.acme.employeescheduling.service.CrewScheduleFactory;
import org.acme.employeescheduling.service.CrewScheduleSessionService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

/** The REST API of the editor with the real services; only the DB (CrewDataCache) is mocked. */
@WebMvcTest(CrewScheduleController.class)
@Import({ CrewScheduleSessionService.class, CrewScheduleRepository.class, CrewRosterService.class,
        CrewScheduleFactory.class, CrewScheduleEditor.class, ClockConfig.class })
class CrewScheduleControllerTest {

    private static final String SHIFT_ID = "2026-11-03-DAY-L1";

    @Autowired
    private MockMvc mvc;
    @Autowired
    private CrewScheduleRepository repository;
    @Autowired
    private CrewScheduleFactory factory;
    @MockitoBean
    private CrewDataCache crewDataCache;

    @BeforeEach
    void createDefaultVersion() {
        Crew crew1 = new Crew(1, "Cr1");
        Crew crew2 = new Crew(2, "Cr2");
        Role packer = new Role(10, "Packer");
        CrewData data = new CrewData(List.of(crew1, crew2), List.of(packer),
                List.of(new Employee(100, "Anna", crew1, new HashSet<>())), Map.of(1, Map.of(packer, 2)));
        repository.create(SessionHeader.DEFAULT_SESSION_ID,
                factory.createEmpty(YearMonth.of(2026, 11), List.of(new Line("L1", "Line 1")), data));
    }

    @Test
    void addPinMoveAndRemoveACrewShift() throws Exception {
        mvc.perform(json(post("/crew-schedules/crew-shifts"), shift("DAY", 1)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.month").value("2026-11"))
                .andExpect(jsonPath("$.lines[0].days[0].dayShift.id").value(SHIFT_ID))
                .andExpect(jsonPath("$.lines[0].days[0].dayShift.crew.id").value(1))
                .andExpect(jsonPath("$.lines[0].days[0].nightShift").doesNotExist());

        // The same line shift again: conflict
        mvc.perform(json(post("/crew-schedules/crew-shifts"), shift("DAY", 2)))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.message").value(containsString("already has crew")));

        mvc.perform(json(put("/crew-schedules/crew-shifts/" + SHIFT_ID + "/pinned"), "{\"pinned\": true}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.lines[0].days[0].dayShift.pinned").value(true));
        mvc.perform(delete("/crew-schedules/crew-shifts/" + SHIFT_ID)).andExpect(status().isConflict());

        mvc.perform(json(put("/crew-schedules/crew-shifts/" + SHIFT_ID + "/pinned"), "{\"pinned\": false}"))
                .andExpect(status().isOk());
        mvc.perform(json(put("/crew-schedules/crew-shifts/" + SHIFT_ID), shift("NIGHT", 2)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.lines[0].days[0].dayShift").doesNotExist())
                .andExpect(jsonPath("$.lines[0].days[0].nightShift.crew.id").value(2));

        mvc.perform(delete("/crew-schedules/crew-shifts/2026-11-03-NIGHT-L1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.lines[0].days", hasSize(0)));
        mvc.perform(delete("/crew-schedules/crew-shifts/2026-11-03-NIGHT-L1")).andExpect(status().isNotFound());
    }

    @Test
    void dateOutsideTheMonthIsABadRequest() throws Exception {
        mvc.perform(json(post("/crew-schedules/crew-shifts"),
                "{\"date\": \"2026-12-01\", \"type\": \"DAY\", \"lineId\": \"L1\", \"crewId\": 1}"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void changeTheCompositionAndMoveAnEmployee() throws Exception {
        mvc.perform(json(put("/crew-schedules/crews/1/roles/10"), "{\"count\": 3}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].crew.staffCount").value(3))
                .andExpect(jsonPath("$[0].roles[0].role.name").value("Packer"))
                .andExpect(jsonPath("$[0].roles[0].count").value(3));

        mvc.perform(json(put("/crew-schedules/employees/100/crew"), "{\"crewId\": 2}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].employees", hasSize(0)))
                .andExpect(jsonPath("$[1].employees[0].fullName").value("Anna"))
                .andExpect(jsonPath("$[1].employees[0].crew.id").value(2));
    }

    @Test
    void everySessionChangesOnlyItsOwnCopy() throws Exception {
        mvc.perform(json(post("/crew-schedules/crew-shifts"), shift("DAY", 1)).header(SessionHeader.HEADER, "user1"))
                .andExpect(status().isOk());

        mvc.perform(get("/crew-schedules/rotation").header(SessionHeader.HEADER, "user2"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.lines[0].days", hasSize(0)));
    }

    private static String shift(String type, int crewId) {
        return "{\"date\": \"2026-11-03\", \"type\": \"" + type + "\", \"lineId\": \"L1\", \"crewId\": " + crewId + "}";
    }

    private static MockHttpServletRequestBuilder json(MockHttpServletRequestBuilder request, String body) {
        return request.contentType(MediaType.APPLICATION_JSON).content(body);
    }
}
