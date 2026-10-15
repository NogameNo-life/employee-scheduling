package org.acme.employeescheduling.service;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;

import org.acme.employeescheduling.domain.Crew;
import org.acme.employeescheduling.domain.CrewShift;
import org.acme.employeescheduling.domain.Line;
import org.acme.employeescheduling.domain.ShiftType;
import org.junit.jupiter.api.Test;

class CrewRotationGeneratorTest {

    private final CrewRotationGenerator generator = new CrewRotationGenerator();

    @Test
    void matchesTableFromSpecification() {
        Crew c1 = new Crew(1, "Cr1");
        Crew c2 = new Crew(2, "Cr2");
        Crew c3 = new Crew(3, "Cr3");
        Crew c4 = new Crew(4, "Cr4");
        Line line1 = new Line("L1", "Line 1");
        LocalDate day1 = LocalDate.of(2026, 11, 1);

        List<CrewShift> shifts = generator.generate(day1, day1.plusDays(4), day1,
                Map.of(line1, List.of(c1, c3, c4, c2)));

        // Spec: Day1 Cr1,Cr2 | Day2 Cr3,Cr1 | Day3 Cr4,Cr3 | Day4 Cr2,Cr4 | Day5 Cr1,Cr2
        Crew[][] expected = { { c1, c2 }, { c3, c1 }, { c4, c3 }, { c2, c4 }, { c1, c2 } };
        for (int i = 0; i < expected.length; i++) {
            LocalDate date = day1.plusDays(i);
            assertThat(crewOn(shifts, date, ShiftType.DAY)).isEqualTo(expected[i][0]);
            assertThat(crewOn(shifts, date, ShiftType.NIGHT)).isEqualTo(expected[i][1]);
        }
        assertThat(shifts).hasSize(10); // 5 days x 2 shifts
    }

    @Test
    void continuesAcrossMonths() {
        Crew c1 = new Crew(1, "Cr1");
        Line line1 = new Line("L1", "Line 1");
        Map<Line, List<Crew>> rotation = Map.of(line1,
                List.of(c1, new Crew(3, "Cr3"), new Crew(4, "Cr4"), new Crew(2, "Cr2")));
        LocalDate anchor = LocalDate.of(2026, 1, 1);

        // Same anchor -> the same date gets the same crew, whatever period we generate
        LocalDate date = LocalDate.of(2026, 12, 1);
        List<CrewShift> fromMonthStart = generator.generate(date, date, anchor, rotation);
        List<CrewShift> fromEarlier = generator.generate(date.minusDays(10), date, anchor, rotation).stream()
                .filter(s -> s.getDate().equals(date)).toList();
        assertThat(fromMonthStart).extracting(CrewShift::getCrew)
                .containsExactlyElementsOf(fromEarlier.stream().map(CrewShift::getCrew).toList());
    }

    private static Crew crewOn(List<CrewShift> shifts, LocalDate date, ShiftType type) {
        return shifts.stream()
                .filter(s -> s.getDate().equals(date) && s.getType() == type)
                .findFirst().orElseThrow().getCrew();
    }
}
