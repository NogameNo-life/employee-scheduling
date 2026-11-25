package org.acme.employeescheduling.service;

import java.time.LocalDate;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.atomic.AtomicReference;

import org.springframework.stereotype.Service;

import org.acme.employeescheduling.domain.Line;
import org.acme.employeescheduling.domain.ShiftTime;
import org.acme.employeescheduling.domain.ShiftType;
import org.acme.employeescheduling.entity.plr.PlrLc;
import org.acme.employeescheduling.entity.plr.PlrLines;
import org.acme.employeescheduling.entity.plr.PlrSm;
import org.acme.employeescheduling.repository.plr.CrewRepository;
import org.acme.employeescheduling.repository.plr.LineRepository;
import org.acme.employeescheduling.repository.plr.PlannerLineRepository;
import org.acme.employeescheduling.repository.plr.ShiftTimeRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Master data kept in memory: {@link CrewData} per department and all lines of the planner (PLR_LC).
 * Filled when the application starts by {@link CrewRosterService} (like LoadDataService in
 * food-packaging-quarkus). Call {@link #refresh} after crews or employees are changed in the DB.
 */
@Service
public class CrewDataCache {

    private static final Logger LOGGER = LoggerFactory.getLogger(CrewDataCache.class);

    private final CrewDataLoader loader;
    private final CrewRepository crewRepository;
    private final LineRepository lineRepository;
    private final PlannerLineRepository plannerLineRepository;
    private final ShiftTimeRepository shiftTimeRepository;

    /**
     * Department (OBJ, trimmed) and month -> data. Replaced as a whole by {@link #refresh}, so other users never
     * see a half-loaded cache; {@link #get} adds data that was not loaded yet.
     */
    private final AtomicReference<ConcurrentMap<DepartmentMonth, CrewData>> crewData =
            new AtomicReference<>(new ConcurrentHashMap<>());
    /** Lines of every period (PLR_LC). Read-only, replaced as a whole by {@link #refreshLines()}. */
    private final AtomicReference<List<LineRow>> lines = new AtomicReference<>(List.of());
    /** DAY and NIGHT times (PLR_SM), the defaults until {@link #refreshShifts()} reads them. */
    private final AtomicReference<List<ShiftTime>> shifts = new AtomicReference<>(toShiftTimes(List.of()));

    public CrewDataCache(CrewDataLoader loader, CrewRepository crewRepository, LineRepository lineRepository,
            PlannerLineRepository plannerLineRepository, ShiftTimeRepository shiftTimeRepository) {
        this.loader = loader;
        this.crewRepository = crewRepository;
        this.lineRepository = lineRepository;
        this.plannerLineRepository = plannerLineRepository;
        this.shiftTimeRepository = shiftTimeRepository;
    }

    /** Reloads lines, shift times and the crew data of {@code month}. */
    public void refresh(YearMonth month) {
        refreshLines();
        refreshShifts();
        refreshCrews(month);
    }

    /**
     * Reloads the lines only. Called at startup on its own, so the frontend can show the lines
     * even when the crews cannot be loaded.
     */
    public void refreshLines() {
        List<LineRow> rows = toLines(plannerLineRepository.findActive(), loadLineNames());
        lines.set(rows);
        LOGGER.info("Loaded {} lines: {}.", rows.size(), rows);
    }

    /**
     * Reloads the shift times (PLR_SM). Never fails: if PLR_SM cannot be read, the default times
     * (08:00-20:00, 20:00-08:00) are used and a warning is logged.
     */
    public void refreshShifts() {
        List<PlrSm> rows;
        try {
            rows = shiftTimeRepository.findActive();
        } catch (RuntimeException e) {
            LOGGER.warn("Could not read the shift times (PLR_SM): 08:00-20:00 and 20:00-08:00 are used.", e);
            rows = List.of();
        }
        List<ShiftTime> times = toShiftTimes(rows);
        shifts.set(times);
        LOGGER.info("Shift times: {}.", times);
    }

    /**
     * DAY and NIGHT times (in this order) from the PLR_SM rows. A row with an unknown SM or a time that cannot be
     * read is skipped; if there are two rows for a type, the first one is used. A type without a row gets the
     * default time. All of this is logged as a warning.
     */
    static List<ShiftTime> toShiftTimes(List<PlrSm> rows) {
        Map<ShiftType, ShiftTime> byType = new java.util.EnumMap<>(ShiftType.class);
        for (PlrSm row : rows) {
            ShiftTime time;
            try {
                ShiftType type = ShiftType.fromCode(row.code);
                time = new ShiftTime(type, ShiftTime.fromHoursMinutes(row.start), ShiftTime.fromHoursMinutes(row.end),
                        trimToNull(row.sng));
            } catch (IllegalArgumentException e) {
                LOGGER.warn("PLR_SM row {} is skipped: {}", row.id, e.getMessage());
                continue;
            }
            if (byType.putIfAbsent(time.type(), time) != null) {
                LOGGER.warn("PLR_SM has more than one row for SM {}: row {} is skipped.", time.type().getCode(),
                        row.id);
            }
        }
        List<ShiftTime> result = new ArrayList<>();
        for (ShiftType type : ShiftType.values()) {
            ShiftTime time = byType.get(type);
            if (time == null) {
                time = ShiftTime.defaultFor(type);
                if (!rows.isEmpty()) {
                    LOGGER.warn("PLR_SM has no row for SM {} ({}): {}-{} is used.", type.getCode(), type,
                            time.start(), time.end());
                }
            }
            result.add(time);
        }
        return List.copyOf(result);
    }

    /** DAY and NIGHT times (PLR_SM, or the defaults). */
    public List<ShiftTime> getShifts() {
        return shifts.get();
    }

    /** KRC (trimmed) -> name (PLR_PLINES.SNM); empty if PLR_PLINES cannot be read, then the KC is the name. */
    private List<PlrLines> loadLineNames() {
        try {
            return lineRepository.findActive();
        } catch (RuntimeException e) {
            LOGGER.warn("Could not read the line names (PLR_PLINES): the line codes (KC) are used as names.", e);
            return List.of();
        }
    }

    /**
     * Lines of the PLR_LC rows (not deleted, with a KC): id = KC, krc = KRC, both trimmed. The name is the
     * PLR_PLINES.SNM of the KRC (PLR_PLINES has one row per packaging type, the first name is used), or the KC.
     */
    static List<LineRow> toLines(List<PlrLc> plannerRows, List<PlrLines> nameRows) {
        Map<String, String> namesByKrc = new HashMap<>();
        for (PlrLines row : nameRows) {
            if (row.lineId != null && row.lineName != null && !row.lineName.isBlank()) {
                namesByKrc.putIfAbsent(row.lineId.trim(), row.lineName.trim());
            }
        }
        List<LineRow> result = new ArrayList<>();
        for (PlrLc row : plannerRows) {
            String kc = trimToNull(row.kc);
            if (kc == null || (row.deleted != null && row.deleted != 0)) {
                continue;
            }
            String krc = trimToNull(row.krc);
            String name = krc == null ? null : namesByKrc.get(krc);
            result.add(new LineRow(new Line(kc, krc, name == null ? kc : name), row.from, row.to));
        }
        return List.copyOf(result);
    }

    private static String trimToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }

    private void refreshCrews(YearMonth month) {
        List<String> departments = crewRepository.findDepartments().stream().map(String::trim).distinct().toList();
        ConcurrentMap<DepartmentMonth, CrewData> data = new ConcurrentHashMap<>();
        for (String department : departments) {
            data.put(new DepartmentMonth(department, month), loader.load(department, month));
        }
        crewData.set(data);
        LOGGER.info("Loaded crew data of {} for departments {}.", month, departments);
    }

    /** Data for one department and month. Data that was not loaded yet is loaded now. */
    public CrewData get(String department, YearMonth month) {
        return crewData.get().computeIfAbsent(new DepartmentMonth(department.trim(), month),
                key -> loader.load(key.department(), key.month()));
    }

    /** Data of all loaded departments of {@code month} together: crews of different departments can share it. */
    public CrewData getAll(YearMonth month) {
        List<CrewData> all = crewData.get().entrySet().stream()
                .filter(entry -> entry.getKey().month().equals(month))
                .map(Map.Entry::getValue)
                .toList();
        return new CrewData(
                all.stream().flatMap(data -> data.crews().stream()).distinct().toList(),
                all.stream().flatMap(data -> data.employees().stream()).distinct().toList());
    }

    /** The line with the code KC (any period). */
    public Optional<Line> getLine(String kc) {
        String id = kc.trim();
        return lines.get().stream().map(LineRow::line).filter(line -> line.getId().equals(id)).findFirst();
    }

    /** The line with the old code KRC (any period): line output is recorded by KRC. */
    public Optional<Line> getLineByKrc(String krc) {
        String code = krc.trim();
        return lines.get().stream().map(LineRow::line).filter(line -> code.equals(line.getKrc())).findFirst();
    }

    /** Lines that are active in {@code month} (their DTBEG..DTEND overlaps it), sorted by name. */
    public List<Line> getLines(YearMonth month) {
        return lines.get().stream()
                .filter(row -> row.isActiveIn(month))
                .map(LineRow::line)
                .distinct()
                .sorted(Comparator.comparing(Line::getName).thenComparing(Line::getId))
                .toList();
    }

    private record DepartmentMonth(String department, YearMonth month) {
    }

    /** A line and its period: DTBEG..DTEND, null = open. */
    record LineRow(Line line, LocalDate from, LocalDate to) {

        boolean isActiveIn(YearMonth month) {
            return (from == null || !from.isAfter(month.atEndOfMonth()))
                    && (to == null || !to.isBefore(month.atDay(1)));
        }

        @Override
        public String toString() {
            return line.getId() + (line.getKrc() == null ? "" : "/" + line.getKrc());
        }
    }
}
