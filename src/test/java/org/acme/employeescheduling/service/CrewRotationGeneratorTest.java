package org.acme.employeescheduling.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.LocalDate;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.Arrays;
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
import org.acme.employeescheduling.service.CrewRotationGenerator.GeneratePreview;
import org.acme.employeescheduling.service.CrewRotationGenerator.GenerateRequest;
import org.acme.employeescheduling.service.CrewRotationGenerator.GenerateResult;
import org.acme.employeescheduling.service.CrewRotationGenerator.LinePreview;
import org.acme.employeescheduling.service.CrewRotationGenerator.PlaceCounts;
import org.acme.employeescheduling.service.CrewScheduleEditor.CrewShiftRequest;
import org.acme.employeescheduling.service.CrewScheduleEditor.SampleRequest;
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
    void defaultWithoutAnchorsIsTheTableOfDayOrder() {
        CrewSchedule schedule = schedule(NOVEMBER, false);

        generator.generate(schedule, new GenerateRequest("L1", null, null, false));

        // c0..c3 = 11..14, D = [c0, c2, c3, c1]; NIGHT = the DAY crew of the day before
        assertThat(row(schedule, "L1", ShiftType.DAY, 8)).containsExactly(11, 13, 14, 12, 11, 13, 14, 12);
        assertThat(row(schedule, "L1", ShiftType.NIGHT, 8)).containsExactly(12, 11, 13, 14, 12, 11, 13, 14);
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
    void anchorsHavePriorityOverTheStartCrew() {
        CrewSchedule schedule = schedule(NOVEMBER, false);
        editor.addCrewShift(schedule, new CrewShiftRequest(DAY1, ShiftType.DAY, "L1", 11));

        GenerateResult result = generator.generate(schedule, new GenerateRequest("L1", DAY1.plusDays(2), 11, false));

        // 11 keeps the phase of the 1st: DAY on the 5th, not on the 3rd
        assertThat(crewAt(schedule, "L1", DAY1.plusDays(4), ShiftType.DAY)).isEqualTo(11);
        assertThat(crewAt(schedule, "L1", DAY1.plusDays(2), ShiftType.DAY)).isNotEqualTo(11);
        assertThat(result.messages()).singleElement().asString().contains("Линия L1", "не начинает");
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
        // The default order [11, 13, 14, 12] is turned so that 12 is on the 5th: then 11
        assertThat(crewAt(schedule, "L1", DAY1.plusDays(5), ShiftType.DAY)).isEqualTo(11);
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
    void lineWithThreeCrewsIsFilledAndReportsTheMissingPlaces() {
        CrewSchedule schedule = schedule(NOVEMBER, true);

        GenerateResult result = generator.generate(schedule, new GenerateRequest(null, null, null, false));

        assertThat(result.messages()).first().isEqualTo("Линия L3: 3 бригады вместо 4, часть смен останется пустой.");
        // Place of the missing c3: 7 DAY (3rd, 7th, ... 27th) + 8 NIGHT (the day after, also the 4th ... 28th)
        long missing = 2 * 30 - schedule.getCrewShifts().stream().filter(s -> s.getLine().getId().equals("L3")).count();
        assertThat(missing).isEqualTo(result.messages().size() - 1L);
        assertThat(result.created()).isEqualTo(3 * 60 - (int) missing);
        assertThat(row(schedule, "L2", ShiftType.DAY, 4)).containsExactly(21, 23, 24, 22);
    }

    @Test
    void sampleOnOneLineOtherLinesGetTheDefault() {
        CrewSchedule schedule = schedule(NOVEMBER, false);
        editor.setLineSample(schedule, "L1", new SampleRequest(DAY1, List.of(14, 12, 11, 13)));

        GenerateResult result = generator.generate(schedule, new GenerateRequest(null, null, null, false));

        assertThat(result.messages()).isEmpty();
        assertThat(row(schedule, "L1", ShiftType.DAY, 8)).containsExactly(14, 12, 11, 13, 14, 12, 11, 13);
        assertThat(row(schedule, "L1", ShiftType.NIGHT, 5)).containsExactly(13, 14, 12, 11, 13);
        assertThat(row(schedule, "L2", ShiftType.DAY, 4)).containsExactly(21, 23, 24, 22);
    }

    @Test
    void sampleWithACrewOfAnotherLine() {
        CrewSchedule schedule = schedule(NOVEMBER, false);
        editor.setLineSample(schedule, "L1", new SampleRequest(DAY1, List.of(21, 11, 13, 14)));

        GenerateResult result = generator.generate(schedule, new GenerateRequest(null, null, null, false));

        assertThat(row(schedule, "L1", ShiftType.DAY, 5)).containsExactly(21, 11, 13, 14, 21);
        assertThat(schedule.getCrewShifts()).noneMatch(s -> s.getLine().getId().equals("L2")
                && s.getCrew().getId() == 21);
        assertThat(schedule.getCrewShifts()).noneMatch(s -> s.getCrew().getId() == 12);
        assertThat(crewAt(schedule, "L2", DAY1, ShiftType.DAY)).isNull();
        assertThat(row(schedule, "L2", ShiftType.DAY, DAY1.plusDays(1), 3)).containsExactly(23, 24, 22);
        assertThat(result.messages()).anyMatch(m -> m.contains("Линия L2: 3 бригады"))
                .anyMatch(m -> m.contains("Линия L2, " + DAY1 + ", дневная смена"));
    }

    @Test
    void sampleWithThreeCrewsTheFourthOwnCrewTakesTheFreePhase() {
        CrewSchedule schedule = schedule(NOVEMBER, false);
        editor.setLineSample(schedule, "L1", new SampleRequest(DAY1, Arrays.asList(14, null, 11, 13)));

        GenerateResult result = generator.generate(schedule, new GenerateRequest("L1", null, null, false));

        assertThat(result.messages()).isEmpty();
        assertThat(row(schedule, "L1", ShiftType.DAY, 4)).containsExactly(14, 12, 11, 13);
    }

    @Test
    void sampleChecks() {
        CrewSchedule schedule = schedule(NOVEMBER, false);
        LocalDate lastDays = NOVEMBER.atDay(28);

        assertThatThrownBy(() -> editor.setLineSample(schedule, "L1", new SampleRequest(DAY1, List.of(11, 12, 13))))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> editor.setLineSample(schedule, "L1",
                new SampleRequest(DAY1, Arrays.asList(11, null, null, 13))))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> editor.setLineSample(schedule, "L1", new SampleRequest(DAY1, List.of(11, 11, 13, 14))))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> editor.setLineSample(schedule, "L1",
                new SampleRequest(lastDays, List.of(11, 12, 13, 14))))
                .isInstanceOf(IllegalArgumentException.class);
        assertThat(schedule.getCrewShifts()).isEmpty();
    }

    @Test
    void sampleThatConflictsChangesNothing() {
        CrewSchedule schedule = schedule(NOVEMBER, false);
        editor.addCrewShift(schedule, new CrewShiftRequest(DAY1, ShiftType.NIGHT, "L1", 12));
        editor.addCrewShift(schedule, new CrewShiftRequest(DAY1.plusDays(3), ShiftType.DAY, "L2", 14));

        assertThatThrownBy(() -> editor.setLineSample(schedule, "L1", new SampleRequest(DAY1, List.of(11, 12, 13, 14))))
                .isInstanceOf(IllegalStateException.class);

        assertThat(crewAt(schedule, "L1", DAY1, ShiftType.NIGHT)).isEqualTo(12);
        assertThat(crewAt(schedule, "L1", DAY1, ShiftType.DAY)).isNull();
    }

    @Test
    void pinnedFirstTenDaysAreContinued() {
        CrewSchedule schedule = schedule(NOVEMBER, false);
        editor.setLineSample(schedule, "L1", new SampleRequest(DAY1, List.of(14, 12, 11, 13)));
        generator.generate(schedule, new GenerateRequest("L1", null, null, false));
        Map<String, Integer> full = snapshot(schedule);
        schedule.getCrewShifts().removeIf(s -> s.getDate().isAfter(DAY1.plusDays(9)));
        editor.setPinnedInRange(schedule, "L1", DAY1, DAY1.plusDays(9), true);

        GenerateResult result = generator.generate(schedule, new GenerateRequest("L1", null, null, false));

        assertThat(result.kept()).isEqualTo(20);
        assertThat(result.created()).isEqualTo(40);
        assertThat(snapshot(schedule)).isEqualTo(full);
    }

    @Test
    void unpinnedFirstTenDaysWithoutOverwriteAreContinued() {
        CrewSchedule schedule = schedule(NOVEMBER, false);
        editor.setLineSample(schedule, "L1", new SampleRequest(DAY1, List.of(14, 12, 11, 13)));
        generator.generate(schedule, new GenerateRequest("L1", null, null, false));
        Map<String, Integer> full = snapshot(schedule);
        editor.setPinnedInRange(schedule, "L1", null, null, false);
        schedule.getCrewShifts().removeIf(s -> s.getDate().isAfter(DAY1.plusDays(9)));

        generator.generate(schedule, new GenerateRequest("L1", null, null, false));

        assertThat(snapshot(schedule)).isEqualTo(full);
    }

    @Test
    void unpinnedFirstTenDaysWithOverwriteAreRegeneratedFromTheDefault() {
        CrewSchedule schedule = schedule(NOVEMBER, false);
        editor.setLineSample(schedule, "L1", new SampleRequest(DAY1, List.of(14, 12, 11, 13)));
        generator.generate(schedule, new GenerateRequest("L1", null, null, false));
        editor.setPinnedInRange(schedule, "L1", null, null, false);
        schedule.getCrewShifts().removeIf(s -> s.getDate().isAfter(DAY1.plusDays(9)));

        generator.generate(schedule, new GenerateRequest("L1", null, null, true));

        assertThat(row(schedule, "L1", ShiftType.DAY, 5)).containsExactly(11, 13, 14, 12, 11);
        assertThat(schedule.getCrewShifts()).hasSize(60);
    }

    @Test
    void swapOnTheTenthChangesTheOrderFromThenOn() {
        CrewSchedule schedule = schedule(NOVEMBER, false);
        generator.generate(schedule, new GenerateRequest("L1", null, null, false));
        Map<String, Integer> before = snapshot(schedule);
        LocalDate tenth = DAY1.plusDays(9);
        LocalDate eleventh = DAY1.plusDays(10);
        assertThat(crewAt(schedule, "L1", tenth, ShiftType.DAY)).isEqualTo(13);
        assertThat(crewAt(schedule, "L1", eleventh, ShiftType.DAY)).isEqualTo(14);
        // The swap of two DAY cards in the grid, both pinned
        editor.moveCrewShift(schedule, tenth + "-DAY-L1", new CrewShiftRequest(tenth, ShiftType.DAY, "L1", 14));
        editor.moveCrewShift(schedule, eleventh + "-DAY-L1", new CrewShiftRequest(eleventh, ShiftType.DAY, "L1", 13));
        editor.setPinned(schedule, tenth + "-DAY-L1", true);
        editor.setPinned(schedule, eleventh + "-DAY-L1", true);

        GenerateResult result = generator.generate(schedule, new GenerateRequest("L1", tenth, null, true));

        assertThat(result.messages()).isEmpty();
        assertThat(schedule.getCrewShifts()).hasSize(60);
        for (int day = 1; day <= 9; day++) {
            String id = NOVEMBER.atDay(day) + "-DAY-L1";
            assertThat(snapshot(schedule).get(id)).as(id).isEqualTo(before.get(id));
        }
        assertThat(row(schedule, "L1", ShiftType.DAY, tenth, 6)).containsExactly(14, 13, 12, 11, 14, 13);
        assertThat(row(schedule, "L1", ShiftType.NIGHT, eleventh, 5)).containsExactly(14, 13, 12, 11, 14);
    }

    @Test
    void twoAnchorsWithOnePhaseLeaveThePlaceEmpty() {
        CrewSchedule schedule = schedule(NOVEMBER, false);
        editor.addCrewShift(schedule, new CrewShiftRequest(DAY1, ShiftType.DAY, "L1", 11)).setPinned(true);
        editor.addCrewShift(schedule, new CrewShiftRequest(DAY1.plusDays(4), ShiftType.DAY, "L1", 13)).setPinned(true);

        GenerateResult result = generator.generate(schedule, new GenerateRequest("L1", null, null, false));

        assertThat(crewAt(schedule, "L1", DAY1.plusDays(8), ShiftType.DAY)).isNull();
        // 14 and 12 keep their default phases; the phase 13 left (the 2nd, 6th, ...) has no crew
        assertThat(row(schedule, "L1", ShiftType.DAY, DAY1.plusDays(1), 3)).containsExactly(null, 14, 12);
        // Two crews on one phase, so one of the 4 phases has no crew: both kinds of place are reported
        assertThat(result.messages()).allMatch(m -> m.contains("Линия L1"))
                .anyMatch(m -> m.contains("Линия1/1") && m.contains("Линия1/3"))
                .anyMatch(m -> m.contains("нет бригады"));
        assertThat(result.created() + result.kept() + result.messages().size()).isEqualTo(60);
    }

    @Test
    void previewCountsPlacesAndChangesNothing() {
        CrewSchedule schedule = schedule(NOVEMBER, false);
        editor.addCrewShift(schedule, new CrewShiftRequest(DAY1, ShiftType.DAY, "L1", 11)); // before from
        editor.addCrewShift(schedule, new CrewShiftRequest(NOVEMBER.atDay(29), ShiftType.DAY, "L1", 12)).setPinned(true);
        editor.addCrewShift(schedule, new CrewShiftRequest(NOVEMBER.atDay(30), ShiftType.DAY, "L2", 21));

        GeneratePreview all = generator.preview(schedule, null, NOVEMBER.atDay(29));
        GeneratePreview one = generator.preview(schedule, "L2", NOVEMBER.atDay(29));

        assertThat(all.total()).isEqualTo(new PlaceCounts(1, 1, 6));
        assertThat(all.lines()).extracting(LinePreview::lineId).containsExactly("L1", "L2");
        assertThat(all.lines().get(0)).isEqualTo(new LinePreview("L1", "Line 1", 1, 0, 3));
        assertThat(one.total()).isEqualTo(new PlaceCounts(0, 1, 3));
        assertThat(schedule.getCrewShifts()).hasSize(3);
    }

    @Test
    void pinOnlyTheCreatedShiftsAndBulkUnpinARange() {
        CrewSchedule schedule = schedule(NOVEMBER, false);
        CrewShift manual = editor.addCrewShift(schedule, new CrewShiftRequest(DAY1, ShiftType.DAY, "L1", 11));

        generator.generate(schedule, new GenerateRequest("L1", null, null, false, true));

        assertThat(manual.isPinned()).isFalse();
        assertThat(schedule.getCrewShifts()).filteredOn(shift -> shift != manual).allMatch(CrewShift::isPinned);

        int changed = editor.setPinnedInRange(schedule, "L1", DAY1.plusDays(1), DAY1.plusDays(2), false);

        assertThat(changed).isEqualTo(4);
        assertThat(schedule.getCrewShifts()).filteredOn(CrewShift::isPinned).hasSize(60 - 1 - 4);
        assertThatThrownBy(() -> editor.setPinnedInRange(schedule, "L1", DAY1.plusDays(2), DAY1, true))
                .isInstanceOf(IllegalArgumentException.class);
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
