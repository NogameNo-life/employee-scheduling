package org.acme.employeescheduling.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.hasSize;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.net.URI;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.YearMonth;
import java.io.ByteArrayInputStream;
import java.util.List;
import java.util.Map;

import org.acme.employeescheduling.config.ClockConfig;
import org.acme.employeescheduling.config.SessionHeader;
import org.acme.employeescheduling.domain.Crew;
import org.acme.employeescheduling.domain.Employee;
import org.acme.employeescheduling.domain.Line;
import org.acme.employeescheduling.domain.ShiftType;
import org.acme.employeescheduling.domain.ShiftTime;
import org.acme.employeescheduling.repository.CrewScheduleRepository;
import org.acme.employeescheduling.service.CrewData;
import org.acme.employeescheduling.service.CrewDataCache;
import org.acme.employeescheduling.service.CrewRosterService;
import org.acme.employeescheduling.service.CrewScheduleEditor;
import org.acme.employeescheduling.service.CrewScheduleFactory;
import org.acme.employeescheduling.service.CrewScheduleSessionService;
import org.acme.employeescheduling.service.CrewRotationGenerator;
import org.acme.employeescheduling.service.ScheduleExcelExporter;
import org.acme.employeescheduling.service.ScheduleValidator;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.springframework.test.util.ReflectionTestUtils;
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
        CrewScheduleFactory.class, CrewScheduleEditor.class, CrewRotationGenerator.class, ScheduleValidator.class,
        ScheduleExcelExporter.class,
        ClockConfig.class })
class CrewScheduleControllerTest {

    private static final String SHIFT_ID = "2026-11-03-DAY-L1";
    /** Real line codes (PLR_LC.KC) have Cyrillic letters. */
    private static final String KC = "БерЦГС1";

    @Autowired
    private MockMvc mvc;
    @Autowired
    private CrewScheduleRepository repository;
    @Autowired
    private CrewScheduleFactory factory;
    @MockitoBean
    private CrewDataCache crewDataCache;

    private static final List<Line> LINES = List.of(new Line("L1", null, "Line 1"),
            new Line(KC, "170610060000", "Линия 1"));
    private static final List<ShiftTime> SHIFTS = List.of(ShiftTime.defaultFor(ShiftType.DAY),
            ShiftTime.defaultFor(ShiftType.NIGHT));

    private CrewData data;

    /** Crews 1, 3, 4, 5 are the crews of line KC (for the generator); crew 2 has no line. */
    @BeforeEach
    void createDefaultVersion() {
        Crew crew1 = new Crew(1, "Cr1");
        crew1.setStaffCount(5);
        crew1.setLineId(KC);
        Crew crew2 = new Crew(2, "Cr2");
        List<Crew> crews = new java.util.ArrayList<>(List.of(crew1, crew2));
        for (int id = 3; id <= 5; id++) {
            Crew crew = new Crew(id, "Cr" + id);
            crew.setLineId(KC);
            crews.add(crew);
        }
        data = new CrewData(crews, List.of(new Employee(100, "Anna", crew1), new Employee(200, "Boris", crew2)));
        repository.create(SessionHeader.DEFAULT_SESSION_ID,
                factory.createEmpty(YearMonth.of(2026, 11), LINES, SHIFTS, data));
    }

    @Test
    void generateOneLine() throws Exception {
        mvc.perform(json(post("/crew-schedules/generate"), "{\"lineId\": \"" + KC + "\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.generation.created").value(60))
                .andExpect(jsonPath("$.generation.kept").value(0))
                .andExpect(jsonPath("$.generation.messages", hasSize(0)))
                .andExpect(jsonPath("$.lines[0].days", hasSize(0)))
                .andExpect(jsonPath("$.lines[1].days", hasSize(30)))
                // c0..c3 = 1, 3, 4, 5: DAY c0, c2, ...; NIGHT c1, c0, ...
                .andExpect(jsonPath("$.lines[1].days[0].dayShift.crew.id").value(1))
                .andExpect(jsonPath("$.lines[1].days[0].nightShift.crew.id").value(3))
                .andExpect(jsonPath("$.lines[1].days[1].dayShift.crew.id").value(4))
                .andExpect(jsonPath("$.warnings", hasSize(0)));

        // Other changes do not have generation
        mvc.perform(get("/crew-schedules/rotation"))
                .andExpect(jsonPath("$.generation").doesNotExist())
                .andExpect(jsonPath("$.lines[1].days", hasSize(30)));
    }

    @Test
    void generateErrors() throws Exception {
        mvc.perform(json(post("/crew-schedules/generate"), "{\"startCrewId\": 1}"))
                .andExpect(status().isBadRequest());
        mvc.perform(json(post("/crew-schedules/generate"), "{\"lineId\": \"" + KC + "\", \"startCrewId\": 2}"))
                .andExpect(status().isBadRequest());
        mvc.perform(json(post("/crew-schedules/generate"), "{\"lineId\": \"L9\"}"))
                .andExpect(status().isNotFound());
        mvc.perform(json(post("/crew-schedules/generate"), "{\"from\": \"2026-11-10\", \"to\": \"2026-11-05\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value(containsString("раньше даты начала")));
        mvc.perform(get("/crew-schedules/generate/preview").param("from", "2026-11-05").param("to", "2026-11-15"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.to").value("2026-11-15"))
                .andExpect(jsonPath("$.total.empty").value(2 * 11 * 2));
    }

    @Test
    void previewSampleAndBulkPin() throws Exception {
        String url = "/crew-schedules/lines/" + URLEncoder.encode(KC, StandardCharsets.UTF_8) + "/sample";
        mvc.perform(json(put(URI.create(url)), "{\"from\": \"2026-11-01\", \"dayCrewIds\": [5, 4, 3, 1]}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.lines[1].days[0].dayShift.crew.id").value(5))
                .andExpect(jsonPath("$.lines[1].days[0].dayShift.pinned").value(true))
                .andExpect(jsonPath("$.generation").doesNotExist());
        mvc.perform(json(put(URI.create(url)), "{\"dayCrewIds\": [5, 5, 3, 1]}"))
                .andExpect(status().isBadRequest());

        mvc.perform(get("/crew-schedules/generate/preview").param("lineId", KC).param("from", "2026-11-01"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.total.pinned").value(4))
                .andExpect(jsonPath("$.total.unpinned").value(0))
                .andExpect(jsonPath("$.total.empty").value(56))
                .andExpect(jsonPath("$.lines[0].lineId").value(KC));

        mvc.perform(json(post("/crew-schedules/generate"), "{\"lineId\": \"" + KC + "\", \"pin\": true}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.generation.created").value(56))
                .andExpect(jsonPath("$.lines[1].days[4].dayShift.crew.id").value(5))
                .andExpect(jsonPath("$.lines[1].days[4].dayShift.pinned").value(true));

        mvc.perform(json(post("/crew-schedules/crew-shifts/pinned"),
                "{\"lineId\": \"" + KC + "\", \"from\": \"2026-11-05\", \"pinned\": false}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.lines[1].days[3].dayShift.pinned").value(true))
                .andExpect(jsonPath("$.lines[1].days[4].dayShift.pinned").value(false))
                .andExpect(jsonPath("$.lines[1].days[29].nightShift.pinned").value(false));
        mvc.perform(json(post("/crew-schedules/crew-shifts/pinned"),
                "{\"from\": \"2026-12-01\", \"pinned\": true}"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void clearCrewShifts() throws Exception {
        mvc.perform(json(post("/crew-schedules/generate"), "{\"lineId\": \"" + KC + "\"}"))
                .andExpect(status().isOk());
        mvc.perform(json(post("/crew-schedules/crew-shifts/pinned"),
                "{\"from\": \"2026-11-03\", \"to\": \"2026-11-03\", \"pinned\": true}"))
                .andExpect(status().isOk());
        String clear = "{\"lineId\": \"" + KC + "\", \"from\": \"2026-11-02\", \"to\": \"2026-11-04\"";

        mvc.perform(json(post("/crew-schedules/crew-shifts/clear"), clear + ", \"includePinned\": false}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.cleared.removed").value(4))
                .andExpect(jsonPath("$.cleared.keptPinned").value(2))
                .andExpect(jsonPath("$.generation").doesNotExist())
                // 30 days, minus the 2nd and the 4th; the pinned 3rd stays
                .andExpect(jsonPath("$.lines[1].days", hasSize(28)))
                .andExpect(jsonPath("$.lines[1].days[1].date").value("2026-11-03"));

        mvc.perform(json(post("/crew-schedules/crew-shifts/clear"), "{\"includePinned\": true}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.cleared.removed").value(56))
                .andExpect(jsonPath("$.cleared.keptPinned").value(0))
                .andExpect(jsonPath("$.lines[1].days", hasSize(0)));

        mvc.perform(json(post("/crew-schedules/crew-shifts/clear"), "{\"from\": \"2026-11-10\", \"to\": \"2026-11-09\"}"))
                .andExpect(status().isBadRequest());
        mvc.perform(json(post("/crew-schedules/crew-shifts/clear"), "{\"to\": \"2026-12-01\"}"))
                .andExpect(status().isBadRequest());
        // Other responses have no cleared
        mvc.perform(get("/crew-schedules/rotation")).andExpect(jsonPath("$.cleared").doesNotExist());
    }

    @Test
    void staticFilesAreRevalidatedByTheBrowser() throws Exception {
        mvc.perform(get("/app.js"))
                .andExpect(status().isOk())
                .andExpect(header().string("Cache-Control", containsString("no-cache")))
                .andExpect(header().exists("Last-Modified"));
    }

    @Test
    void exportExcel() throws Exception {
        mvc.perform(json(post("/crew-schedules/generate"), "{\"lineId\": \"" + KC + "\"}"))
                .andExpect(status().isOk());

        byte[] body = mvc.perform(get("/crew-schedules/export.xlsx"))
                .andExpect(status().isOk())
                .andExpect(header().string("Content-Type", ScheduleExcelExporter.CONTENT_TYPE))
                .andExpect(header().string("Content-Disposition", "attachment; filename=\"crew-schedule-2026-11.xlsx\"; "
                        + "filename*=UTF-8''" + encode("График_бригад_2026-11.xlsx")))
                .andReturn().getResponse().getContentAsByteArray();
        try (XSSFWorkbook workbook = new XSSFWorkbook(new ByteArrayInputStream(body))) {
            assertThat(workbook.getSheetName(0)).isEqualTo("По линиям");
        }

        mvc.perform(get("/crew-schedules/export.xlsx").param("lineId", KC))
                .andExpect(status().isOk())
                .andExpect(header().string("Content-Disposition",
                        containsString("filename*=UTF-8''" + encode("График_бригад_2026-11_" + KC + ".xlsx"))));
        mvc.perform(get("/crew-schedules/export.xlsx").param("lineId", "L9"))
                .andExpect(status().isNotFound());
    }

    @Test
    void exportWithoutAVersionIs404() throws Exception {
        // No version at all, also no default one (e.g. the DB was not reachable at startup)
        ((Map<?, ?>) ReflectionTestUtils.getField(repository, "sessionSchedules")).clear();

        mvc.perform(get("/crew-schedules/export.xlsx").header(SessionHeader.HEADER, "new-session"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.message").value(containsString("No schedule version")));
    }

    @Test
    void initWithAndWithoutGenerate() throws Exception {
        when(crewDataCache.getLines(any())).thenReturn(LINES);
        when(crewDataCache.getShifts()).thenReturn(SHIFTS);
        when(crewDataCache.getAll(any())).thenReturn(data);

        mvc.perform(post("/crew-schedules/init?month=2026-12"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.month").value("2026-12"))
                .andExpect(jsonPath("$.lines[1].days", hasSize(0)))
                .andExpect(jsonPath("$.generation").doesNotExist());

        mvc.perform(post("/crew-schedules/init?month=2026-12&generate=true"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.lines[1].days", hasSize(31)))
                .andExpect(jsonPath("$.lines[1].days[0].dayShift.crew.id").value(1))
                .andExpect(jsonPath("$.generation.created").value(62))
                .andExpect(jsonPath("$.generation.messages[0]").value("Линия L1: 0 бригад вместо 4, пропущена."));
    }

    @Test
    void restWarningsInEveryResponse() throws Exception {
        mvc.perform(json(post("/crew-schedules/crew-shifts"), shift("DAY", 1)))
                .andExpect(jsonPath("$.warnings", hasSize(0)));

        mvc.perform(json(post("/crew-schedules/crew-shifts"), shift("NIGHT", 1)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.warnings", hasSize(1)))
                .andExpect(jsonPath("$.warnings[0].type").value("CREW_REST"))
                .andExpect(jsonPath("$.warnings[0].crewShiftId").value("2026-11-03-NIGHT-L1"))
                .andExpect(jsonPath("$.warnings[0].previousCrewShiftId").value(SHIFT_ID))
                .andExpect(jsonPath("$.warnings[0].crewId").value(1))
                .andExpect(jsonPath("$.warnings[0].restHours").value(0.0))
                .andExpect(jsonPath("$.warnings[0].message").value(containsString("Бригада Cr1: отдых 0 ч")));

        mvc.perform(delete("/crew-schedules/crew-shifts/2026-11-03-NIGHT-L1"))
                .andExpect(jsonPath("$.warnings", hasSize(0)));
    }

    @Test
    void addPinMoveAndRemoveACrewShift() throws Exception {
        mvc.perform(json(post("/crew-schedules/crew-shifts"), shift("DAY", 1)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.month").value("2026-11"))
                .andExpect(jsonPath("$.shifts[0].type").value("DAY"))
                .andExpect(jsonPath("$.shifts[0].start").value("08:00:00"))
                .andExpect(jsonPath("$.shifts[1].end").value("08:00:00"))
                .andExpect(jsonPath("$.shifts[1].hours").value(12.0))
                .andExpect(jsonPath("$.lines[0].days[0].dayShift.id").value(SHIFT_ID))
                .andExpect(jsonPath("$.lines[0].days[0].dayShift.crew.id").value(1))
                .andExpect(jsonPath("$.lines[0].days[0].dayShift.staffCount").value(5))
                .andExpect(jsonPath("$.lines[0].days[0].dayShift.crew.lineId").value(KC))
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
    void crewShiftIdWithACyrillicLineCodeInTheUrl() throws Exception {
        String id = "2026-11-03-NIGHT-" + KC;
        mvc.perform(json(post("/crew-schedules/crew-shifts"),
                "{\"date\": \"2026-11-03\", \"type\": \"NIGHT\", \"lineId\": \"" + KC + "\", \"crewId\": 1}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.lines[1].line.id").value(KC))
                .andExpect(jsonPath("$.lines[1].line.krc").value("170610060000"))
                .andExpect(jsonPath("$.lines[1].days[0].nightShift.id").value(id));

        // The id encoded as app.js does it (encodeURIComponent): %D0%91%D0%B5...
        URI pinnedUrl = URI.create("/crew-schedules/crew-shifts/" + encode(id) + "/pinned");
        assertThat(pinnedUrl.getRawPath()).contains("%D0%91");
        mvc.perform(json(put(pinnedUrl), "{\"pinned\": true}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.lines[1].days[0].nightShift.pinned").value(true));
        mvc.perform(json(put(pinnedUrl), "{\"pinned\": false}")).andExpect(status().isOk());
        mvc.perform(delete(URI.create("/crew-schedules/crew-shifts/" + encode(id))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.lines[1].days", hasSize(0)));
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
    void absencesAndCrewChanges() throws Exception {
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

    /** Like encodeURIComponent for the characters of a crew shift id (no spaces). */
    private static String encode(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8);
    }

    private static MockHttpServletRequestBuilder json(MockHttpServletRequestBuilder request, String body) {
        return request.contentType(MediaType.APPLICATION_JSON).content(body);
    }
}
