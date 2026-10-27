package org.acme.employeescheduling.service;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.LocalDate;
import java.util.List;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;

import org.acme.employeescheduling.domain.Crew;
import org.acme.employeescheduling.domain.CrewShift;
import org.acme.employeescheduling.domain.Line;
import org.acme.employeescheduling.domain.ShiftType;
import org.acme.employeescheduling.service.LineSchedule.LineDay;
import org.apache.commons.lang3.tuple.Pair;
import org.junit.jupiter.api.Test;

class LineScheduleJsonTest {

    /** Same settings as the Spring Boot ObjectMapper: ISO dates. */
    private final ObjectMapper mapper = new ObjectMapper().registerModule(new JavaTimeModule())
            .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);

    @Test
    void pairIsWrittenAsDayShiftAndNightShift() throws Exception {
        Line line = new Line("L1", "Line 1");
        LocalDate date = LocalDate.of(2026, 11, 1);
        CrewShift dayShift = new CrewShift("d", date, ShiftType.DAY, line, new Crew(1, "Cr1"));
        dayShift.setPinned(true);
        LineSchedule schedule = new LineSchedule(line, List.of(new LineDay(date, Pair.of(dayShift, null))));

        // Trees, not strings: the order of the fields (e.g. the getters start and end) is not fixed
        assertThat((JsonNode) mapper.valueToTree(schedule)).isEqualTo(mapper.readTree("""
                {"line":{"id":"L1","name":"Line 1"},"days":[{"date":"2026-11-01",\
                "dayShift":{"id":"d","date":"2026-11-01","type":"DAY","line":{"id":"L1","name":"Line 1"},\
                "crew":{"id":1,"name":"Cr1","staffCount":0},"pinned":true,\
                "employeeIds":[],"absentEmployeeIds":[],"extraEmployeeIds":[],"unavailableEmployeeIds":[],\
                "roleCounts":null,"staffCount":0,\
                "start":"2026-11-01T08:00:00","end":"2026-11-01T20:00:00"},"nightShift":null}]}"""));
    }

    @Test
    void groupsDayAndNightShiftIntoOnePairPerDay() {
        Line line = new Line("L1", "Line 1");
        LocalDate day1 = LocalDate.of(2026, 11, 1);
        CrewShift night2 = new CrewShift("n2", day1.plusDays(1), ShiftType.NIGHT, line, new Crew(1, "Cr1"));
        CrewShift day1Shift = new CrewShift("d1", day1, ShiftType.DAY, line, new Crew(1, "Cr1"));
        CrewShift night1 = new CrewShift("n1", day1, ShiftType.NIGHT, line, new Crew(2, "Cr2"));

        List<LineSchedule> schedules = LineSchedule.of(List.of(night2, day1Shift, night1));

        assertThat(schedules).hasSize(1);
        assertThat(schedules.get(0).days()).extracting(LineDay::date).containsExactly(day1, day1.plusDays(1));
        assertThat(schedules.get(0).days().get(0).shifts()).isEqualTo(Pair.of(day1Shift, night1));
        assertThat(schedules.get(0).days().get(1).shifts()).isEqualTo(Pair.of(null, night2));
    }
}
