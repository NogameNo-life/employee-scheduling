package org.acme.employeescheduling.service;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.stream.IntStream;

import org.acme.employeescheduling.domain.Crew;
import org.acme.employeescheduling.domain.Line;
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

    private static List<Crew> used(Map<Line, List<Crew>> crewsByLine) {
        return crewsByLine.values().stream().flatMap(List::stream).toList();
    }

    private static List<Crew> crews(int count) {
        return IntStream.rangeClosed(1, count).mapToObj(i -> new Crew(i, "Cr" + i)).toList();
    }
}
