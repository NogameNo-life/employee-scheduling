package org.acme.employeescheduling.service;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.LocalDate;
import java.util.List;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;

import org.acme.employeescheduling.domain.Crew;
import org.acme.employeescheduling.domain.Line;
import org.acme.employeescheduling.service.LineSchedule.LineDay;
import org.apache.commons.lang3.tuple.Pair;
import org.junit.jupiter.api.Test;

class LineScheduleJsonTest {

    /** Same settings as the Spring Boot ObjectMapper: ISO dates. */
    private final ObjectMapper mapper = new ObjectMapper().registerModule(new JavaTimeModule())
            .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);

    @Test
    void pairIsWrittenAsDayCrewAndNightCrew() throws Exception {
        LineSchedule schedule = new LineSchedule(new Line("L1", "Line 1"), List.of(
                new LineDay(LocalDate.of(2026, 11, 1), Pair.of(new Crew(1, "Cr1"), new Crew(2, "Cr2")))));

        assertThat(mapper.writeValueAsString(schedule)).isEqualTo("""
                {"line":{"id":"L1","name":"Line 1"},"days":[{"date":"2026-11-01",\
                "dayCrew":{"id":1,"name":"Cr1","staffCount":0},"nightCrew":{"id":2,"name":"Cr2","staffCount":0}}]}""");
    }
}
