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

    private static final List<Line> LINES = List.of(new Line("L1", "Line 1"), new Line("L2", "Line 2"));

    @Test
    void everyLineGetsFourDifferentCrewsAndNoCrewIsUsedTwice() {
        List<Crew> crews = crews(9);

        Map<Line, List<Crew>> crewsByLine = CrewRosterService.assignCrewsToLines(LINES, crews, new Random(42));

        assertThat(crewsByLine).containsOnlyKeys(LINES);
        crewsByLine.values().forEach(lineCrews -> assertThat(lineCrews).hasSize(4).doesNotHaveDuplicates());
        List<Crew> used = crewsByLine.values().stream().flatMap(List::stream).toList();
        assertThat(used).hasSize(8).doesNotHaveDuplicates().isSubsetOf(crews);
    }

    @Test
    void lineWithoutFourCrewsGetsNone() {
        Map<Line, List<Crew>> crewsByLine = CrewRosterService.assignCrewsToLines(LINES, crews(6), new Random(42));

        assertThat(crewsByLine).containsOnlyKeys(LINES.get(0));
    }

    @Test
    void assignmentIsRandom() {
        List<Crew> crews = crews(8);

        assertThat(CrewRosterService.assignCrewsToLines(LINES, crews, new Random(1)))
                .isNotEqualTo(CrewRosterService.assignCrewsToLines(LINES, crews, new Random(2)));
    }

    private static List<Crew> crews(int count) {
        return IntStream.rangeClosed(1, count).mapToObj(i -> new Crew(i, "Cr" + i)).toList();
    }
}
