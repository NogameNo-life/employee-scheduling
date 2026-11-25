package org.acme.employeescheduling.service;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.YearMonth;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;
import java.util.stream.Collectors;

import org.springframework.stereotype.Service;

import org.acme.employeescheduling.domain.Crew;
import org.acme.employeescheduling.domain.CrewSchedule;
import org.acme.employeescheduling.domain.CrewShift;
import org.acme.employeescheduling.domain.Line;
import org.acme.employeescheduling.domain.ShiftType;
import org.acme.employeescheduling.service.CrewScheduleEditor.CrewShiftRequest;

/**
 * Fills the empty places of the crew schedule from {@code from} to {@code to} (default: the end of the month) with
 * the rotation "Day -> Night -> Sleep day -> Day off". Crew shifts outside that period are never changed.
 *
 * <p>
 * Every crew of a line's cycle has a <b>phase</b> (0..3): it works DAY on every date d with
 * {@code (epochDay(d) - phase) mod 4 == 0} and NIGHT on the next date. Every line is planned on its own. Sources of
 * the phases, in priority:
 * <ol>
 * <li>anchors = the crew shifts of the line that stay (pinned; with {@code overwrite = false} also the unpinned ones;
 * shifts before {@code from} and after {@code to} are never removed, so they stay too). For a date d, a crew follows its latest anchor on
 * or before d, else its earliest anchor after d;</li>
 * <li>TODO: the last week of the previous month, when the plan tables (PLR_SNAR) exist;</li>
 * <li>default: the line's own crews ({@link Crew#getLineId()}) sorted by id (c0..c3), DAY order [c0, c2, c3, c1]
 * from {@code from}, or from {@code startCrewId}.</li>
 * </ol>
 * Crew names are never used to decide anything. A place with no crew, with two crews, or whose crew is busy stays
 * empty with a message: the rest is still generated. Invalid requests throw before anything is changed.
 */
@Service
public class CrewRotationGenerator {

    /** Crews per line. */
    static final int CYCLE = 4;

    /** Index of crew c0..c3 at each place of the default day-shift order D = [c0, c2, c3, c1]. */
    private static final int[] DAY_ORDER = { 0, 2, 3, 1 };

    private final CrewScheduleEditor editor;

    public CrewRotationGenerator(CrewScheduleEditor editor) {
        this.editor = editor;
    }

    public GenerateResult generate(CrewSchedule schedule, GenerateRequest request) {
        GenerateRequest checked = request == null ? new GenerateRequest(null, null, null, false) : request;
        LocalDate from = resolveFrom(schedule, checked.from());
        LocalDate to = resolveTo(schedule, from, checked.to());
        if (checked.startCrewId() != null && checked.lineId() == null) {
            throw new IllegalArgumentException("Бригаду, которая начинает, можно выбрать только для одной линии.");
        }
        List<Line> lines = resolveLines(schedule, checked.lineId());
        if (checked.startCrewId() != null
                && ownCrews(schedule, lines.get(0)).stream().noneMatch(c -> c.getId().equals(checked.startCrewId()))) {
            throw new IllegalArgumentException("Бригада " + checked.startCrewId() + " не относится к линии "
                    + lines.get(0).getId() + ".");
        }

        // 1) Plan every line before anything changes
        Map<Line, List<CrewShift>> anchors = new LinkedHashMap<>();
        Set<Crew> taken = new HashSet<>();
        for (Line line : lines) {
            List<CrewShift> staying = schedule.getCrewShifts().stream()
                    .filter(shift -> shift.getLine().equals(line)
                            && (shift.isPinned() || !checked.overwrite() || shift.getDate().isBefore(from)
                                    || shift.getDate().isAfter(to)))
                    .toList();
            anchors.put(line, staying);
            staying.forEach(shift -> taken.add(shift.getCrew()));
        }
        List<String> messages = new ArrayList<>();
        List<LinePlan> plans = new ArrayList<>();
        for (Line line : lines) {
            LinePlan plan = planLine(schedule, line, anchors.get(line), taken, from, checked.startCrewId());
            if (plan.cycle().size() < CYCLE) {
                messages.add("Линия " + line.getId() + ": " + plan.cycle().size() + " " + crewWord(plan.cycle().size())
                        + " вместо " + CYCLE + (plan.cycle().isEmpty() ? ", пропущена." : ", часть смен останется пустой."));
            }
            if (plan.cycle().isEmpty()) {
                continue;
            }
            plans.add(plan);
            if (checked.startCrewId() != null) {
                startCrewMessage(plan, checked.startCrewId(), from).ifPresent(messages::add);
            }
        }

        // 2) Remove first on all lines, so a crew freed on one line can be used on another
        if (checked.overwrite()) {
            plans.forEach(plan -> removeUnpinned(schedule, plan.line(), from, to));
        }

        // 3) Fill the empty places
        int created = 0;
        int kept = 0;
        for (LinePlan plan : plans) {
            for (LocalDate date = from; !date.isAfter(to); date = date.plusDays(1)) {
                Map<Crew, Integer> phases = phasesOn(plan, date);
                for (ShiftType type : ShiftType.values()) {
                    if (findShift(schedule, plan.line(), date, type).isPresent()) {
                        kept++;
                        continue;
                    }
                    int wanted = phaseOfPlace(date, type);
                    List<Crew> crews = plan.cycle().stream().filter(crew -> Integer.valueOf(wanted).equals(phases.get(crew)))
                            .toList();
                    if (crews.size() != 1) {
                        messages.add(placeMessage(plan.line(), date, type, crews));
                        continue;
                    }
                    try {
                        CrewShift shift = editor.addCrewShift(schedule, new CrewShiftRequest(date, type,
                                plan.line().getId(), crews.get(0).getId()));
                        shift.setPinned(checked.pin());
                        created++;
                    } catch (IllegalStateException e) {
                        messages.add(busyMessage(schedule, plan.line(), date, type, crews.get(0)));
                    }
                }
            }
        }
        // 4) The kept crew shifts right after the period are not changed: only say if the chain does not fit them
        for (LinePlan plan : plans) {
            chainMessage(schedule, plan.line(), to).ifPresent(messages::add);
        }
        return new GenerateResult(created, kept, List.copyOf(messages));
    }

    /** {@link #preview(CrewSchedule, String, LocalDate, LocalDate)} up to the end of the month. */
    public GeneratePreview preview(CrewSchedule schedule, String lineId, LocalDate requestedFrom) {
        return preview(schedule, lineId, requestedFrom, null);
    }

    /** Counts the places from {@code from} to {@code to} (null: the end of the month), per line and in total. */
    public GeneratePreview preview(CrewSchedule schedule, String lineId, LocalDate requestedFrom, LocalDate requestedTo) {
        LocalDate from = resolveFrom(schedule, requestedFrom);
        LocalDate to = resolveTo(schedule, from, requestedTo);
        int places = (int) (ChronoUnit.DAYS.between(from, to) + 1) * ShiftType.values().length;
        List<LinePreview> result = new ArrayList<>();
        int totalPinned = 0;
        int totalUnpinned = 0;
        for (Line line : resolveLines(schedule, lineId)) {
            int pinned = 0;
            int unpinned = 0;
            for (CrewShift shift : schedule.getCrewShifts()) {
                if (shift.getLine().equals(line) && !shift.getDate().isBefore(from) && !shift.getDate().isAfter(to)) {
                    if (shift.isPinned()) {
                        pinned++;
                    } else {
                        unpinned++;
                    }
                }
            }
            result.add(new LinePreview(line.getId(), line.getName(), pinned, unpinned, places - pinned - unpinned));
            totalPinned += pinned;
            totalUnpinned += unpinned;
        }
        int totalEmpty = places * result.size() - totalPinned - totalUnpinned;
        return new GeneratePreview(from, to, result, new PlaceCounts(totalPinned, totalUnpinned, totalEmpty));
    }

    private static LocalDate resolveFrom(CrewSchedule schedule, LocalDate from) {
        LocalDate resolved = from == null ? schedule.getMonth().atDay(1) : from;
        if (!YearMonth.from(resolved).equals(schedule.getMonth())) {
            throw new IllegalArgumentException("Дата " + resolved + " не в месяце " + schedule.getMonth() + ".");
        }
        return resolved;
    }

    private static LocalDate resolveTo(CrewSchedule schedule, LocalDate from, LocalDate to) {
        LocalDate resolved = to == null ? schedule.getMonth().atEndOfMonth() : to;
        if (!YearMonth.from(resolved).equals(schedule.getMonth())) {
            throw new IllegalArgumentException("Дата окончания " + resolved + " не в месяце " + schedule.getMonth() + ".");
        }
        if (resolved.isBefore(from)) {
            throw new IllegalArgumentException("Дата окончания " + resolved + " раньше даты начала " + from + ".");
        }
        return resolved;
    }

    /**
     * A message if a kept crew shift on {@code to + 1} does not continue the chain: NIGHT on to + 1 = the DAY crew of
     * {@code to}, DAY on to + 1 = the DAY crew of to - 3. Places without a shift are not checked.
     */
    private static Optional<String> chainMessage(CrewSchedule schedule, Line line, LocalDate to) {
        LocalDate next = to.plusDays(1);
        if (!YearMonth.from(next).equals(schedule.getMonth())) {
            return Optional.empty();
        }
        boolean fits = sameCrew(findShift(schedule, line, next, ShiftType.NIGHT), findShift(schedule, line, to, ShiftType.DAY))
                && sameCrew(findShift(schedule, line, next, ShiftType.DAY),
                        findShift(schedule, line, to.minusDays(CYCLE - 1L), ShiftType.DAY));
        return fits ? Optional.empty()
                : Optional.of("Линия " + line.getId() + ": цикл не совпадает со сменами после " + to + ".");
    }

    private static boolean sameCrew(Optional<CrewShift> kept, Optional<CrewShift> chain) {
        return kept.isEmpty() || chain.isEmpty() || kept.get().getCrew().equals(chain.get().getCrew());
    }

    private static List<Line> resolveLines(CrewSchedule schedule, String lineId) {
        if (lineId == null || lineId.isBlank()) {
            return schedule.getLines();
        }
        String trimmed = lineId.trim();
        return List.of(schedule.getLines().stream().filter(line -> line.getId().equals(trimmed)).findFirst()
                .orElseThrow(() -> new NoSuchElementException("Line " + trimmed
                        + " was not found in this schedule version.")));
    }

    /**
     * The cycle of a line: the crews of its anchors, filled up to 4 with its own crews (by id) that are not in the
     * cycle of another line in this run.
     */
    private static LinePlan planLine(CrewSchedule schedule, Line line, List<CrewShift> anchors, Set<Crew> taken,
            LocalDate from, Integer startCrewId) {
        Map<Crew, List<Anchor>> anchorsByCrew = new LinkedHashMap<>();
        anchors.stream().sorted(Comparator.comparing(CrewShift::getStart)).forEach(shift -> anchorsByCrew
                .computeIfAbsent(shift.getCrew(), crew -> new ArrayList<>())
                .add(new Anchor(shift.getDate(), shift.getStart(), phaseOfPlace(shift.getDate(), shift.getType()))));

        List<Crew> own = ownCrews(schedule, line);
        Map<Crew, Integer> places = new LinkedHashMap<>();
        for (int place = 0; place < CYCLE; place++) {
            if (DAY_ORDER[place] < own.size()) {
                places.put(own.get(DAY_ORDER[place]), place);
            }
        }

        Set<Crew> cycle = new LinkedHashSet<>(anchorsByCrew.keySet());
        List<Crew> filled = new ArrayList<>();
        for (Crew crew : own) {
            if (cycle.size() >= CYCLE) {
                break;
            }
            if (!taken.contains(crew)) {
                cycle.add(crew);
                filled.add(crew);
            }
        }
        // In the default order, so the crews without a phase take the free phases in that order
        filled.sort(Comparator.comparingInt(crew -> places.getOrDefault(crew, CYCLE)));

        int base = (int) Math.floorMod(from.toEpochDay(), (long) CYCLE);
        if (startCrewId != null) {
            base = Math.floorMod(base - places.getOrDefault(new Crew(startCrewId, null), 0), CYCLE);
        }
        return new LinePlan(line, List.copyOf(cycle), filled, places, anchorsByCrew, base, startCrewId != null);
    }

    /** The phase of every crew of the cycle on one date; a crew without a free phase is not in the map. */
    private static Map<Crew, Integer> phasesOn(LinePlan plan, LocalDate date) {
        Map<Crew, Integer> phases = new LinkedHashMap<>();
        plan.anchors().forEach((crew, anchors) -> phases.put(crew, phaseOn(anchors, date)));

        int rotation = plan.fixedStart() ? 0 : bestRotation(plan, phases);
        TreeSet<Integer> free = new TreeSet<>(List.of(0, 1, 2, 3));
        free.removeAll(phases.values());
        List<Crew> waiting = new ArrayList<>();
        for (Crew crew : plan.filled()) {
            Integer place = plan.places().get(crew);
            if (place != null && free.remove(Math.floorMod(plan.base() + place + rotation, CYCLE))) {
                phases.put(crew, Math.floorMod(plan.base() + place + rotation, CYCLE));
            } else {
                waiting.add(crew);
            }
        }
        for (Crew crew : waiting) {
            Integer phase = free.pollFirst();
            if (phase != null) {
                phases.put(crew, phase);
            }
        }
        return phases;
    }

    /** The turn of the default order that agrees with most anchored own crews (ties: the smallest turn). */
    private static int bestRotation(LinePlan plan, Map<Crew, Integer> anchored) {
        int best = 0;
        int bestScore = -1;
        for (int rotation = 0; rotation < CYCLE; rotation++) {
            int score = 0;
            for (Map.Entry<Crew, Integer> entry : anchored.entrySet()) {
                Integer place = plan.places().get(entry.getKey());
                if (place != null && Math.floorMod(plan.base() + place + rotation, CYCLE) == entry.getValue()) {
                    score++;
                }
            }
            if (score > bestScore) {
                bestScore = score;
                best = rotation;
            }
        }
        return best;
    }

    /** The latest anchor on or before the date, else the earliest one after it. Anchors are sorted by start. */
    private static int phaseOn(List<Anchor> anchors, LocalDate date) {
        Anchor result = anchors.get(0);
        for (Anchor anchor : anchors) {
            if (!anchor.date().isAfter(date)) {
                result = anchor;
            }
        }
        return result.phase();
    }

    /** The phase of the crew that works this place: DAY on x = x mod 4, NIGHT on x = (x - 1) mod 4. */
    static int phaseOfPlace(LocalDate date, ShiftType type) {
        long day = type == ShiftType.DAY ? date.toEpochDay() : date.toEpochDay() - 1;
        return (int) Math.floorMod(day, (long) CYCLE);
    }

    /** The line's own crews (PLR_BR.KC), sorted by id. */
    static List<Crew> ownCrews(CrewSchedule schedule, Line line) {
        return schedule.getCrews().stream()
                .filter(crew -> line.getId().equals(crew.getLineId()))
                .sorted(Comparator.comparing(Crew::getId))
                .toList();
    }

    private static Optional<String> startCrewMessage(LinePlan plan, int startCrewId, LocalDate from) {
        Crew start = new Crew(startCrewId, null);
        if (Integer.valueOf(phaseOfPlace(from, ShiftType.DAY)).equals(phasesOn(plan, from).get(start))) {
            return Optional.empty();
        }
        return Optional.of("Линия " + plan.line().getId() + ": бригада " + startCrewId + " не начинает " + from
                + ", потому что очередь задана оставшимися сменами.");
    }

    private void removeUnpinned(CrewSchedule schedule, Line line, LocalDate from, LocalDate to) {
        schedule.getCrewShifts().stream()
                .filter(shift -> shift.getLine().equals(line) && !shift.getDate().isBefore(from)
                        && !shift.getDate().isAfter(to) && !shift.isPinned())
                .map(CrewShift::getId)
                .toList()
                .forEach(id -> editor.removeCrewShift(schedule, id));
    }

    private static Optional<CrewShift> findShift(CrewSchedule schedule, Line line, LocalDate date, ShiftType type) {
        return schedule.getCrewShifts().stream()
                .filter(shift -> shift.getLine().equals(line) && shift.getDate().equals(date)
                        && shift.getType() == type)
                .findFirst();
    }

    private static String placeMessage(Line line, LocalDate date, ShiftType type, List<Crew> crews) {
        String reason = crews.isEmpty() ? "нет бригады для этого места в цикле"
                : "на это место в цикле претендуют бригады "
                        + crews.stream().map(Crew::getName).collect(Collectors.joining(", "));
        return "Линия " + line.getId() + ", " + date + ", " + shiftName(type) + ": " + reason
                + ", смена оставлена пустой.";
    }

    private static String busyMessage(CrewSchedule schedule, Line line, LocalDate date, ShiftType type, Crew crew) {
        String where = schedule.getCrewShifts().stream()
                .filter(shift -> shift.getCrew().equals(crew) && shift.getDate().equals(date)
                        && shift.getType() == type)
                .findFirst()
                .map(shift -> "на линии " + shift.getLine().getId())
                .orElse("(её сотрудник на замене)");
        return "Линия " + line.getId() + ", " + date + ", " + shiftName(type) + ": бригада " + crew.getName()
                + " уже работает в это время " + where + ", смена оставлена пустой.";
    }

    static String shiftName(ShiftType type) {
        return type == ShiftType.DAY ? "дневная смена" : "ночная смена";
    }

    private static String crewWord(int count) {
        int lastTwo = count % 100;
        int last = count % 10;
        if (lastTwo >= 11 && lastTwo <= 14) {
            return "бригад";
        }
        return last == 1 ? "бригада" : last >= 2 && last <= 4 ? "бригады" : "бригад";
    }

    private record Anchor(LocalDate date, LocalDateTime start, int phase) {
    }

    /**
     * @param cycle      all crews of the line's cycle
     * @param filled     the crews of the cycle without an anchor, in the default order
     * @param places     place in the default order of the line's own crews
     * @param anchors    anchors per crew, sorted by start
     * @param base       the phase of default place 0
     * @param fixedStart a start crew was chosen: the default order is not turned to fit the anchors
     */
    private record LinePlan(Line line, List<Crew> cycle, List<Crew> filled, Map<Crew, Integer> places,
            Map<Crew, List<Anchor>> anchors, int base, boolean fixedStart) {
    }

    /**
     * @param lineId      null: every line of the version
     * @param from        first generated date; null: the 1st of the version's month
     * @param to          last generated date (included); null: the last day of the version's month
     * @param startCrewId own crew of the line that works DAY on {@code from} in the default order; only with a
     *                    {@code lineId}; anchors have priority
     * @param overwrite   true: remove the unpinned crew shifts of the generated lines in {@code from}..{@code to} first;
     *                    false: keep them as anchors and only fill the empty places
     * @param pin         true: the crew shifts created by the generator are pinned
     */
    public record GenerateRequest(String lineId, LocalDate from, LocalDate to, Integer startCrewId, boolean overwrite,
            boolean pin) {

        /** Up to the end of the month, not pinned. */
        public GenerateRequest(String lineId, LocalDate from, Integer startCrewId, boolean overwrite) {
            this(lineId, from, null, startCrewId, overwrite, false);
        }

        /** Up to the end of the month. */
        public GenerateRequest(String lineId, LocalDate from, Integer startCrewId, boolean overwrite, boolean pin) {
            this(lineId, from, null, startCrewId, overwrite, pin);
        }
    }

    /**
     * @param created  crew shifts added
     * @param kept     line shifts that already had a crew (pinned, or {@code overwrite = false}) and were kept
     * @param messages for the user, in Russian: skipped lines, line shifts left empty
     */
    public record GenerateResult(int created, int kept, List<String> messages) {
    }

    public record PlaceCounts(int pinned, int unpinned, int empty) {
    }

    public record LinePreview(String lineId, String lineName, int pinned, int unpinned, int empty) {
    }

    /** Places (line, date, shift) from {@code from} to {@code to}, both included. */
    public record GeneratePreview(LocalDate from, LocalDate to, List<LinePreview> lines, PlaceCounts total) {
    }
}
