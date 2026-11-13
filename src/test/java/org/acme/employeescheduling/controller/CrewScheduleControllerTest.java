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
                List.of(new Employee(100, "Anna", crew1, new HashSet<>()), new Employee(200, "Boris", crew2,
                        new HashSet<>())), Map.of(1, Map.of(packer, 2)));
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
    void replaceAnEmployeeForOneShift() throws Exception {
        mvc.perform(json(post("/crew-schedules/crew-shifts"), shift("DAY", 1))).andExpect(status().isOk());

        mvc.perform(json(post("/crew-schedules/crew-shifts/" + SHIFT_ID + "/replacements"),
                "{\"employeeId\": 100, \"substituteId\": 200}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.lines[0].days[0].dayShift.employeeIds", hasSize(1)))
                .andExpect(jsonPath("$.lines[0].days[0].dayShift.employeeIds[0]").value(200))
                .andExpect(jsonPath("$.lines[0].days[0].dayShift.absentEmployeeIds[0]").value(100))
                .andExpect(jsonPath("$.lines[0].days[0].dayShift.extraEmployeeIds[0]").value(200));

        mvc.perform(delete("/crew-schedules/crew-shifts/" + SHIFT_ID + "/employees/200"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.lines[0].days[0].dayShift.employeeIds", hasSize(0)));
        mvc.perform(put("/crew-schedules/crew-shifts/" + SHIFT_ID + "/employees/100"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.lines[0].days[0].dayShift.employeeIds[0]").value(100));
        mvc.perform(put("/crew-schedules/crew-shifts/" + SHIFT_ID + "/employees/100"))
                .andExpect(status().isConflict());
    }

    @Test
    void dateOutsideTheMonthIsABadRequest() throws Exception {
        mvc.perform(json(post("/crew-schedules/crew-shifts"),
                "{\"date\": \"2026-12-01\", \"type\": \"DAY\", \"lineId\": \"L1\", \"crewId\": 1}"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void moveAnEmployeeToAnotherCrew() throws Exception {
        mvc.perform(json(put("/crew-schedules/employees/100/crew"), "{\"crewId\": 2}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].employees", hasSize(0)))
                .andExpect(jsonPath("$[1].employees", hasSize(2)))
                .andExpect(jsonPath("$[1].employees[0].fullName").value("Anna"))
                .andExpect(jsonPath("$[1].employees[0].crew.id").value(2));
    }

    @Test
    void absencesCrewChangesAndShiftCompositionForOneShift() throws Exception {
        mvc.perform(json(post("/crew-schedules/crew-shifts"), shift("DAY", 1))).andExpect(status().isOk());

        // Vacation: Anna does not work the shift of her crew
        mvc.perform(json(post("/crew-schedules/employees/100/absences"),
                "{\"type\": \"VACATION\", \"from\": \"2026-11-02\", \"to\": \"2026-11-04\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.lines[0].days[0].dayShift.employeeIds", hasSize(0)))
                .andExpect(jsonPath("$.lines[0].days[0].dayShift.unavailableEmployeeIds[0]").value(100));
        mvc.perform(get("/crew-schedules"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.employees[0].absences[0].type").value("VACATION"))
                .andExpect(jsonPath("$.employees[0].absences[0].to").value("2026-11-04"));
        mvc.perform(json(post("/crew-schedules/employees/100/absences"),
                "{\"type\": \"SICK_LEAVE\", \"from\": \"2026-11-04\", \"to\": \"2026-11-05\"}"))
                .andExpect(status().isConflict());

        // Boris moves to crew 1 from the 3rd
        mvc.perform(json(put("/crew-schedules/employees/200/crew"), "{\"crewId\": 1, \"from\": \"2026-11-03\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[1].employees[0].crew.id").value(2))
                .andExpect(jsonPath("$[1].employees[0].crewChanges[0].from").value("2026-11-03"))
                .andExpect(jsonPath("$[1].employees[0].crewChanges[0].crew.id").value(1));
        mvc.perform(get("/crew-schedules/rotation"))
                .andExpect(jsonPath("$.lines[0].days[0].dayShift.employeeIds[0]").value(200));
        mvc.perform(delete("/crew-schedules/employees/200/crew-changes/2026-11-03")).andExpect(status().isOk());
        mvc.perform(get("/crew-schedules/rotation"))
                .andExpect(jsonPath("$.lines[0].days[0].dayShift.employeeIds", hasSize(0)));

        // 3 packers in this shift only, then back to the crew's 2
        mvc.perform(json(put("/crew-schedules/crew-shifts/" + SHIFT_ID + "/composition"),
                "{\"roles\": [{\"roleId\": 10, \"count\": 3}]}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.lines[0].days[0].dayShift.staffCount").value(3))
                .andExpect(jsonPath("$.lines[0].days[0].dayShift.roleCounts.10").value(3));
        mvc.perform(delete("/crew-schedules/crew-shifts/" + SHIFT_ID + "/composition"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.lines[0].days[0].dayShift.roleCounts").isEmpty());
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
