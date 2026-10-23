package org.acme.employeescheduling.service;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Clock;
import java.time.LocalDate;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.stream.IntStream;

import org.acme.employeescheduling.domain.Crew;
import org.acme.employeescheduling.domain.CrewSchedule;
import org.acme.employeescheduling.domain.CrewShift;
import org.acme.employeescheduling.domain.Employee;
import org.acme.employeescheduling.domain.Line;
import org.acme.employeescheduling.domain.ShiftType;
import org.acme.employeescheduling.service.CrewRosterService.CrewDetails;
import org.junit.jupiter.api.Test;

class CrewRosterServiceTest {

    private static final List<Line> LINES = List.of(new Line("L1", "Line 1"), new Line("L2", "Line 2"),
            new Line("L3", "Line 3"));

    @Test
    void fewerCrewsThanLinesEveryCrewWorksOnADifferentLine() {
        Map<Line, List<Crew>> crewsByLine = CrewRosterService.assignCrewsToLines(LINES, crews(3), new Random(42));

        assertThat(crewsByLine).containsOnlyKeys(LINES);
        crewsByLine.values().forEach(lineCrews -> assertThat(lineCrews).hasSize(1));
    }

    @Test
    void crewsAreSpreadOverAllLines() {
        Map<Line, List<Crew>> crewsByLine = CrewRosterService.assignCrewsToLines(LINES, crews(7), new Random(42));

        assertThat(crewsByLine).containsOnlyKeys(LINES);
        assertThat(crewsByLine.values()).extracting(List::size).containsExactly(3, 2, 2);
        assertThat(used(crewsByLine)).hasSize(7).doesNotHaveDuplicates();
    }

    @Test
    void atMostFourCrewsPerLine() {
        List<Crew> crews = crews(13);

        Map<Line, List<Crew>> crewsByLine = CrewRosterService.assignCrewsToLines(LINES, crews, new Random(42));

        crewsByLine.values().forEach(lineCrews -> assertThat(lineCrews).hasSize(4));
        assertThat(used(crewsByLine)).hasSize(12).doesNotHaveDuplicates().isSubsetOf(crews);
    }

    @Test
    void noLinesNoAssignment() {
        assertThat(CrewRosterService.assignCrewsToLines(List.of(), crews(4), new Random(42))).isEmpty();
    }

    @Test
    void assignmentIsRandom() {
        List<Crew> crews = crews(8);

        assertThat(CrewRosterService.assignCrewsToLines(LINES, crews, new Random(1)))
                .isNotEqualTo(CrewRosterService.assignCrewsToLines(LINES, crews, new Random(2)));
    }

    @Test
    void crewDetailsHaveLinesAndEmployeesSortedByName() {
        Crew crew1 = new Crew(1, "Cr1");
        Crew crew2 = new Crew(2, "Cr2");
        Line line = LINES.get(0);
        LocalDate date = LocalDate.of(2026, 11, 1);
        List<CrewShift> crewShifts = List.of(
                new CrewShift("s1", date, ShiftType.DAY, line, crew1),
                new CrewShift("s2", date, ShiftType.NIGHT, line, crew1));
        List<Employee> employees = List.of(
                new Employee(10, "Petrov", crew1, new HashSet<>()),
                new Employee(11, "Ivanov", crew1, new HashSet<>()),
                new Employee(20, "Sidorov", crew2, new HashSet<>()));
        CrewSchedule schedule = new CrewSchedule(LINES, List.of(crew2, crew1), employees, crewShifts, List.of());
        CrewRosterService service = new CrewRosterService(null, null, null, Clock.systemUTC());

        List<CrewDetails> details = service.toCrewDetails(schedule);

        assertThat(details).extracting(CrewDetails::crew).containsExactly(crew1, crew2);
        assertThat(details.get(0).lines()).containsExactly(line);
        assertThat(details.get(0).employees()).extracting(Employee::getFullName).containsExactly("Ivanov", "Petrov");
        assertThat(details.get(1).lines()).isEmpty();
        assertThat(details.get(1).employees()).extracting(Employee::getFullName).containsExactly("Sidorov");
    }

    private static List<Crew> used(Map<Line, List<Crew>> crewsByLine) {
        return crewsByLine.values().stream().flatMap(List::stream).toList();
    }

    private static List<Crew> crews(int count) {
        return IntStream.rangeClosed(1, count).mapToObj(i -> new Crew(i, "Cr" + i)).toList();
    }
}
