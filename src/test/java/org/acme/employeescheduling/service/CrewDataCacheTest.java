package org.acme.employeescheduling.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.YearMonth;
import java.util.List;

import org.acme.employeescheduling.domain.Line;
import org.acme.employeescheduling.domain.ShiftTime;
import org.acme.employeescheduling.domain.ShiftType;
import org.acme.employeescheduling.entity.plr.PlrLc;
import org.acme.employeescheduling.entity.plr.PlrLines;
import org.acme.employeescheduling.entity.plr.PlrSm;
import org.acme.employeescheduling.repository.plr.LineRepository;
import org.acme.employeescheduling.repository.plr.PlannerLineRepository;
import org.acme.employeescheduling.repository.plr.ShiftTimeRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class CrewDataCacheTest {

    private final PlannerLineRepository plannerLineRepository = mock(PlannerLineRepository.class);
    private final LineRepository lineRepository = mock(LineRepository.class);
    private final ShiftTimeRepository shiftTimeRepository = mock(ShiftTimeRepository.class);
    private final CrewDataCache cache = new CrewDataCache(null, null, lineRepository, plannerLineRepository,
            shiftTimeRepository);

    @BeforeEach
    void loadLines() {
        when(plannerLineRepository.findActive()).thenReturn(List.of(
                lc("БерЦГС1   ", "170610060000", null, null),
                lc("БерЦГС2   ", "170610070000", LocalDate.of(2026, 12, 1), null),
                lc("БерЦГС3   ", "170610080000", null, LocalDate.of(2026, 10, 31)),
                lc("БерЦГС4   ", null, null, null)));
        when(lineRepository.findActive()).thenReturn(List.of(
                plines("170610060000", "Линия 1    "),
                plines("170610060000", "Линия 1 (другая упаковка)"),
                plines("170610070000", "Линия 2"),
                plines("170610080000", "Линия 3")));
        cache.refreshLines();
    }

    @Test
    void lineIdIsKcAndTheNameComesFromPlinesByKrc() {
        Line line = cache.getLine("БерЦГС1").orElseThrow();

        assertThat(line.getId()).isEqualTo("БерЦГС1");
        assertThat(line.getKrc()).isEqualTo("170610060000");
        assertThat(line.getName()).isEqualTo("Линия 1");
    }

    @Test
    void kcIsTheNameWhenThereIsNoKrcOrNoName() {
        assertThat(cache.getLine("БерЦГС4").orElseThrow().getName()).isEqualTo("БерЦГС4");
        assertThat(CrewDataCache.toLines(List.of(lc("БерЦГС5", "999", null, null)), List.of()))
                .singleElement().extracting(row -> row.line().getName()).isEqualTo("БерЦГС5");
    }

    @Test
    void bothDirectionsKcAndKrc() {
        assertThat(cache.getLineByKrc("170610070000 ")).map(Line::getId).contains("БерЦГС2");
        assertThat(cache.getLine(" БерЦГС2")).map(Line::getKrc).contains("170610070000");
        assertThat(cache.getLineByKrc("000")).isEmpty();
        assertThat(cache.getLine("БерЦГС9")).isEmpty();
    }

    @Test
    void onlyLinesWhosePeriodOverlapsTheMonth() {
        assertThat(cache.getLines(YearMonth.of(2026, 11))).extracting(Line::getId)
                .containsExactly("БерЦГС4", "БерЦГС1"); // sorted by name: "Линия 1" after "БерЦГС4"
        assertThat(cache.getLines(YearMonth.of(2026, 12))).extracting(Line::getId)
                .containsExactly("БерЦГС4", "БерЦГС1", "БерЦГС2");
        assertThat(cache.getLines(YearMonth.of(2026, 10))).extracting(Line::getId)
                .containsExactly("БерЦГС4", "БерЦГС1", "БерЦГС3");
    }

    @Test
    void deletedRowsAndRowsWithoutKcAreSkipped() {
        PlrLc deleted = lc("БерЦГС7", "1", null, null);
        deleted.deleted = 1;
        assertThat(CrewDataCache.toLines(List.of(deleted, lc(null, "2", null, null), lc("  ", "3", null, null)),
                List.of())).isEmpty();
    }

    @Test
    void shiftTimesFromPlrSm() {
        // The real rows
        when(shiftTimeRepository.findActive()).thenReturn(List.of(
                sm("2         ", "20.00", "8.00", "60;61"),
                sm("1         ", "8.00", "20.00", "446       ")));
        cache.refreshShifts();

        assertThat(cache.getShifts()).containsExactly(
                new ShiftTime(ShiftType.DAY, LocalTime.of(8, 0), LocalTime.of(20, 0), "446"),
                new ShiftTime(ShiftType.NIGHT, LocalTime.of(20, 0), LocalTime.of(8, 0), "60;61"));
    }

    @Test
    void defaultTimesForMissingOrInvalidRows() {
        assertThat(CrewDataCache.toShiftTimes(List.of(sm("1", "7.30", "19.30", null), sm("2", "20.75", "8.00", null),
                sm("9", "1.00", "2.00", null))))
                .containsExactly(new ShiftTime(ShiftType.DAY, LocalTime.of(7, 30), LocalTime.of(19, 30), null),
                        ShiftTime.defaultFor(ShiftType.NIGHT));
    }

    @Test
    void defaultTimesWhenPlrSmCannotBeRead() {
        when(shiftTimeRepository.findActive()).thenThrow(new IllegalStateException("no DB"));
        cache.refreshShifts();

        assertThat(cache.getShifts()).containsExactly(ShiftTime.defaultFor(ShiftType.DAY),
                ShiftTime.defaultFor(ShiftType.NIGHT));
    }

    private static PlrSm sm(String code, String start, String end, String sng) {
        PlrSm row = new PlrSm();
        row.code = code;
        row.start = new BigDecimal(start);
        row.end = new BigDecimal(end);
        row.sng = sng;
        row.deleted = 0;
        return row;
    }

    private static PlrLc lc(String kc, String krc, LocalDate from, LocalDate to) {
        PlrLc row = new PlrLc();
        row.kc = kc;
        row.krc = krc;
        row.deleted = 0;
        row.from = from;
        row.to = to;
        return row;
    }

    private static PlrLines plines(String krc, String name) {
        PlrLines row = new PlrLines();
        row.lineId = krc;
        row.lineName = name;
        row.deleted = 0;
        return row;
    }
}
