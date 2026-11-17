package org.acme.employeescheduling.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.LocalDate;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.TreeMap;

import org.acme.employeescheduling.domain.Crew;
import org.acme.employeescheduling.domain.CrewSchedule;
import org.acme.employeescheduling.domain.CrewShift;
import org.acme.employeescheduling.domain.Line;
import org.acme.employeescheduling.domain.ShiftTime;
import org.acme.employeescheduling.domain.ShiftType;
import org.acme.employeescheduling.service.CrewRotationGenerator.GenerateRequest;
import org.acme.employeescheduling.service.CrewRotationGenerator.GenerateResult;
import org.acme.employeescheduling.service.CrewScheduleEditor.CrewShiftRequest;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.api.Test;

class CrewRotationGeneratorTest {

    private static final YearMonth NOVEMBER = YearMonth.of(2026, 11);
    private static final LocalDate DAY1 = NOVEMBER.atDay(1);

    private final CrewScheduleEditor editor = new CrewScheduleEditor();
    private final CrewRotationGenerator generator = new CrewRotationGenerator(editor);
    private final CrewScheduleFactory factory = new CrewScheduleFactory();

    /** Lines L1, L2 with crews 11..14 and 21..24 (c0..c3); L3 has only 3 crews (31..33). */
    private CrewSchedule schedule(YearMonth month, boolean withLine3) {
        List<Line> lines = new ArrayList<>(List.of(new Line("L1", "1", "Line 1"), new Line("L2", "2", "Line 2")));
        List<Crew> crews = new ArrayList<>();
        for (int line = 1; line <= 2; line++) {
            for (int number = 1; number <= 4; number++) {
                crews.add(crew(line * 10 + number, "L" + line));
            }
        }
        if (withLine3) {
            lines.add(new Line("L3", "3", "Line 3"));
            for (int number = 1; number <= 3; number++) {
                crews.add(crew(30 + number, "L3"));
            }
        }
        // Not sorted by id on purpose: the generator sorts them
        crews.sort((a, b) -> Integer.compare(b.getId(), a.getId()));
        return factory.createEmpty(month, lines,
                List.of(ShiftTime.defaultFor(ShiftType.DAY), ShiftTime.defaultFor(ShiftType.NIGHT)),
                new CrewData(crews, List.of()));
    }

    private static Crew crew(int id, String lineId) {
        Crew crew = new Crew(id, "Линия" + lineId.substring(1) + "/" + (id % 10));
        crew.setLineId(lineId);
        return crew;
    }

    @Test
    void rotationTableForOffsetZero() {
        List<Crew> crews = List.of(crew(11, "L1"), crew(12, "L1"), crew(13, "L1"), crew(14, "L1"));
        // c0..c3 = 11..14
        int[] day = { 11, 13, 14, 12, 11 };
        int[] night = { 12, 11, 13, 14, 12 };
        for (int i = 0; i < 5; i++) {
            assertThat(CrewRotationGenerator.crewOn(crews, 0, i, ShiftType.DAY).getId()).as("DAY %d", i)
                    .isEqualTo(day[i]);
            assertThat(CrewRotationGenerator.crewOn(crews, 0, i, ShiftType.NIGHT).getId()).as("NIGHT %d", i)
                    .isEqualTo(night[i]);
        }
    }

    @Test
    void generatedScheduleFollowsTheTable() {
        CrewSchedule schedule = schedule(NOVEMBER, false);

        GenerateResult result = generator.generate(schedule, new GenerateRequest("L1", null, null, false));

        assertThat(result).isEqualTo(new GenerateResult(60, 0, List.of()));
        assertThat(row(schedule, "L1", ShiftType.DAY, 5)).containsExactly(11, 13, 14, 12, 11);
        assertThat(row(schedule, "L1", ShiftType.NIGHT, 5)).containsExactly(12, 11, 13, 14, 12);
        assertThat(schedule.getCrewShifts()).allMatch(shift -> shift.getLine().getId().equals("L1"));
        assertThat(schedule.getCrewShifts().get(0).getId()).isEqualTo(DAY1 + "-DAY-L1");
    }

    @Test
    void startCrewWorksTheDayShiftOfTheFirstDate() {
        CrewSchedule schedule = schedule(NOVEMBER, false);

        generator.generate(schedule, new GenerateRequest("L1", DAY1.plusDays(2), 13, false));

        assertThat(crewAt(schedule, "L1", DAY1.plusDays(1), ShiftType.DAY)).isNull();
        assertThat(row(schedule, "L1", ShiftType.DAY, DAY1.plusDays(2), 4)).containsExactly(13, 14, 12, 11);
        assertThat(row(schedule, "L1", ShiftType.NIGHT, DAY1.plusDays(2), 4)).containsExactly(11, 13, 14, 12);
    }

    @Test
    void startCrewNeedsALineAndMustBelongToIt() {
        CrewSchedule schedule = schedule(NOVEMBER, false);
        GenerateRequest withoutLine = new GenerateRequest(null, null, 13, false);
        GenerateRequest crewOfOtherLine = new GenerateRequest("L1", null, 21, false);
        GenerateRequest dateBeforeMonth = new GenerateRequest("L1", DAY1.minusDays(1), null, false);
        GenerateRequest unknownLine = new GenerateRequest("L9", null, null, false);

        assertThatThrownBy(() -> generator.generate(schedule, withoutLine))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> generator.generate(schedule, crewOfOtherLine))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> generator.generate(schedule, dateBeforeMonth))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> generator.generate(schedule, unknownLine))
                .isInstanceOf(NoSuchElementException.class);
        assertThat(schedule.getCrewShifts()).isEmpty();
    }

    @Test
    void continuesTheEarlierCrewShifts() {
        CrewSchedule schedule = schedule(NOVEMBER, false);
        generator.generate(schedule, new GenerateRequest(null, null, null, false));
        Map<String, Integer> full = snapshot(schedule);

        LocalDate tenth = DAY1.plusDays(9);
        schedule.getCrewShifts().stream().filter(shift -> !shift.getDate().isBefore(tenth)).map(CrewShift::getId)
                .toList().forEach(id -> editor.removeCrewShift(schedule, id));
        GenerateResult result = generator.generate(schedule, new GenerateRequest(null, tenth, null, false));

        assertThat(result.created()).isEqualTo(2 * 2 * 21);
        assertThat(snapshot(schedule)).isEqualTo(full);
    }

    @Test
    void continuesEvenFromANightShiftOnly() {
        CrewSchedule schedule = schedule(NOVEMBER, false);
        // c2 (13) worked NIGHT on the 1st, so DAY on October 31; then c3 (14) DAY on the 1st, c1 (12) on the 2nd
        editor.addCrewShift(schedule, new CrewShiftRequest(DAY1, ShiftType.NIGHT, "L1", 13));

        generator.generate(schedule, new GenerateRequest("L1", DAY1.plusDays(1), null, false));

        assertThat(row(schedule, "L1", ShiftType.DAY, DAY1.plusDays(1), 4)).containsExactly(12, 11, 13, 14);
        assertThat(row(schedule, "L1", ShiftType.NIGHT, DAY1.plusDays(1), 4)).containsExactly(14, 12, 11, 13);
    }

    @Test
    void earlierCrewShiftsThatDoNotFitOneRotationAreAConflict() {
        CrewSchedule schedule = schedule(NOVEMBER, true);
        editor.addCrewShift(schedule, new CrewShiftRequest(DAY1, ShiftType.DAY, "L1", 11));
        editor.addCrewShift(schedule, new CrewShiftRequest(DAY1, ShiftType.NIGHT, "L1", 13));

        GenerateRequest fromThe3rd = new GenerateRequest(null, DAY1.plusDays(2), null, false);
        assertThatThrownBy(() -> generator.generate(schedule, fromThe3rd))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Линия L1")
                .hasMessageContaining("Выберите бригаду");
        // Nothing changed, also not on the other lines
        assertThat(schedule.getCrewShifts()).hasSize(2);

        // With a start crew it works
        generator.generate(schedule, new GenerateRequest("L1", DAY1.plusDays(2), 11, false));
        assertThat(crewAt(schedule, "L1", DAY1.plusDays(2), ShiftType.DAY)).isEqualTo(11);
    }

    @Test
    void pinnedCrewShiftIsKeptAlsoWithOverwrite() {
        CrewSchedule schedule = schedule(NOVEMBER, false);
        CrewShift pinned = editor.addCrewShift(schedule, new CrewShiftRequest(DAY1.plusDays(4), ShiftType.DAY, "L1",
                12));
        pinned.setPinned(true);

        GenerateResult result = generator.generate(schedule, new GenerateRequest("L1", null, null, true));

        assertThat(result.kept()).isEqualTo(1);
        assertThat(result.created()).isEqualTo(59);
        assertThat(schedule.getCrewShifts()).contains(pinned);
        assertThat(crewAt(schedule, "L1", DAY1.plusDays(4), ShiftType.DAY)).isEqualTo(12);
        assertThat(crewAt(schedule, "L1", DAY1.plusDays(5), ShiftType.DAY)).isEqualTo(13);
    }

    @Test
    void withoutOverwriteExistingCrewShiftsAreKept() {
        CrewSchedule schedule = schedule(NOVEMBER, false);
        editor.addCrewShift(schedule, new CrewShiftRequest(DAY1.plusDays(1), ShiftType.DAY, "L1", 14));

        GenerateResult result = generator.generate(schedule, new GenerateRequest("L1", null, null, false));

        assertThat(result.kept()).isEqualTo(1);
        assertThat(result.created()).isEqualTo(59);
        assertThat(crewAt(schedule, "L1", DAY1.plusDays(1), ShiftType.DAY)).isEqualTo(14);
    }

    @Test
    void withOverwriteUnpinnedCrewShiftsFromTheDateAreReplaced() {
        CrewSchedule schedule = schedule(NOVEMBER, false);
        editor.addCrewShift(schedule, new CrewShiftRequest(DAY1, ShiftType.DAY, "L1", 11));
        editor.addCrewShift(schedule, new CrewShiftRequest(DAY1.plusDays(1), ShiftType.DAY, "L1", 14));

        GenerateResult result = generator.generate(schedule, new GenerateRequest("L1", DAY1.plusDays(1), 13, true));

        assertThat(result.kept()).isZero();
        assertThat(crewAt(schedule, "L1", DAY1, ShiftType.DAY)).isEqualTo(11); // before the date: not touched
        assertThat(crewAt(schedule, "L1", DAY1.plusDays(1), ShiftType.DAY)).isEqualTo(13);
    }

    @Test
    void lineWithoutFourCrewsIsSkippedTheOthersAreGenerated() {
        CrewSchedule schedule = schedule(NOVEMBER, true);

        GenerateResult result = generator.generate(schedule, new GenerateRequest(null, null, null, false));

        assertThat(result.messages()).containsExactly("Линия L3: 3 бригады вместо 4, пропущена.");
        assertThat(result.created()).isEqualTo(2 * 60);
        assertThat(schedule.getCrewShifts()).noneMatch(shift -> shift.getLine().getId().equals("L3"));
        assertThat(row(schedule, "L2", ShiftType.DAY, 4)).containsExactly(21, 23, 24, 22);
    }

    @ParameterizedTest
    @CsvSource({ "2027-02, 28", "2026-11, 30", "2026-12, 31" })
    void everyDayOfTheMonth(String month, int days) {
        CrewSchedule schedule = schedule(YearMonth.parse(month), false);

        GenerateResult result = generator.generate(schedule, new GenerateRequest(null, null, null, false));

        assertThat(result.created()).isEqualTo(days * 2 * 2);
        LocalDate last = YearMonth.parse(month).atEndOfMonth();
        assertThat(crewAt(schedule, "L1", last, ShiftType.NIGHT)).isNotNull();
        assertThat(schedule.getCrewShifts()).allMatch(shift -> YearMonth.from(shift.getDate())
                .equals(YearMonth.parse(month)));
    }

    @Test
    void crewBusyOnAnotherLineLeavesTheLineShiftEmpty() {
        CrewSchedule schedule = schedule(NOVEMBER, false);
        editor.addCrewShift(schedule, new CrewShiftRequest(DAY1, ShiftType.DAY, "L2", 11));

        GenerateResult result = generator.generate(schedule, new GenerateRequest("L1", null, null, false));

        assertThat(crewAt(schedule, "L1", DAY1, ShiftType.DAY)).isNull();
        assertThat(result.created()).isEqualTo(59);
        assertThat(result.messages()).singleElement().asString()
                .contains("Линия L1", DAY1.toString(), "дневная смена", "Линия1/1", "на линии L2");
    }

    private static Integer crewAt(CrewSchedule schedule, String lineId, LocalDate date, ShiftType type) {
        return schedule.getCrewShifts().stream()
                .filter(shift -> shift.getLine().getId().equals(lineId) && shift.getDate().equals(date)
                        && shift.getType() == type)
                .map(shift -> shift.getCrew().getId())
                .findFirst().orElse(null);
    }

    private static List<Integer> row(CrewSchedule schedule, String lineId, ShiftType type, int days) {
        return row(schedule, lineId, type, DAY1, days);
    }

    private static List<Integer> row(CrewSchedule schedule, String lineId, ShiftType type, LocalDate from, int days) {
        List<Integer> crews = new ArrayList<>();
        for (int i = 0; i < days; i++) {
            crews.add(crewAt(schedule, lineId, from.plusDays(i), type));
        }
        return crews;
    }

    private static Map<String, Integer> snapshot(CrewSchedule schedule) {
        Map<String, Integer> result = new TreeMap<>();
        schedule.getCrewShifts().forEach(shift -> result.put(shift.getId(), shift.getCrew().getId()));
        return result;
    }
}
