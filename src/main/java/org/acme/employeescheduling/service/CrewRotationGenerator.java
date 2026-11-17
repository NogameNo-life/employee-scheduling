package org.acme.employeescheduling.service;

import java.time.LocalDate;
import java.time.YearMonth;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.NoSuchElementException;
import java.util.Optional;
import java.util.Set;

import org.springframework.stereotype.Service;

import org.acme.employeescheduling.domain.Crew;
import org.acme.employeescheduling.domain.CrewSchedule;
import org.acme.employeescheduling.domain.CrewShift;
import org.acme.employeescheduling.domain.Line;
import org.acme.employeescheduling.domain.ShiftType;
import org.acme.employeescheduling.service.CrewScheduleEditor.CrewShiftRequest;

/**
 * Fills the crew schedule of one version with the rotation "Day -> Night -> Sleep day -> Day off".
 *
 * <p>
 * Every line has 4 crews ({@link Crew#getLineId()} = {@link Line#getId()}). With the crews of a line sorted by id
 * (c0..c3), the day-shift order is D = [c0, c2, c3, c1]; with the start offset s:
 * DAY crew on day i = D[(i + s) mod 4], NIGHT crew on day i = D[(i + s - 1) mod 4] (the crew that worked DAY the
 * day before). Day i counts from the first generated date.
 *
 * <p>
 * Pinned crew shifts are never changed. Crew shifts are created with {@link CrewScheduleEditor#addCrewShift}, so
 * they have the same ids, times and checks as the ones the user adds. Errors: {@link NoSuchElementException}
 * (unknown line), {@link IllegalArgumentException} (invalid request), {@link IllegalStateException} (the earlier
 * crew shifts of a line do not fit one rotation). They are thrown before anything is changed.
 */
@Service
public class CrewRotationGenerator {

    /** Crews per line. */
    static final int CYCLE = 4;

    private final CrewScheduleEditor editor;

    public CrewRotationGenerator(CrewScheduleEditor editor) {
        this.editor = editor;
    }

    /** Index of crew c0..c3 at each place of the day-shift order D = [c0, c2, c3, c1]. */
    private static final int[] DAY_ORDER = { 0, 2, 3, 1 };

    public GenerateResult generate(CrewSchedule schedule, GenerateRequest request) {
        GenerateRequest checked = request == null ? new GenerateRequest(null, null, null, false) : request;
        YearMonth month = schedule.getMonth();
        LocalDate from = checked.from() == null ? month.atDay(1) : checked.from();
        if (!YearMonth.from(from).equals(month)) {
            throw new IllegalArgumentException("Дата " + from + " не в месяце " + month + ".");
        }
        if (checked.startCrewId() != null && checked.lineId() == null) {
            throw new IllegalArgumentException("Бригаду, которая начинает, можно выбрать только для одной линии.");
        }

        List<Line> lines;
        if (checked.lineId() == null) {
            lines = schedule.getLines();
        } else {
            String lineId = checked.lineId().trim();
            lines = List.of(schedule.getLines().stream().filter(line -> line.getId().equals(lineId)).findFirst()
                    .orElseThrow(() -> new NoSuchElementException("Line " + lineId
                            + " was not found in this schedule version.")));
        }

        // 1) Check every line and find its offset before anything changes
        List<String> messages = new ArrayList<>();
        List<LinePlan> plans = new ArrayList<>();
        for (Line line : lines) {
            List<Crew> crews = crewsOf(schedule, line);
            if (checked.startCrewId() != null && crews.stream().noneMatch(c -> c.getId().equals(checked.startCrewId()))) {
                throw new IllegalArgumentException("Бригада " + checked.startCrewId() + " не относится к линии "
                        + line.getId() + ".");
            }
            if (crews.size() != CYCLE) {
                messages.add("Линия " + line.getId() + ": " + crews.size() + " " + crewWord(crews.size())
                        + " вместо " + CYCLE + ", пропущена.");
                continue;
            }
            int offset = checked.startCrewId() != null
                    ? offsetOfStartCrew(crews, checked.startCrewId())
                    : offsetFromEarlierShifts(schedule, line, crews, from);
            plans.add(new LinePlan(line, crews, offset));
        }

        // 2) Generate
        int created = 0;
        int kept = 0;
        for (LinePlan plan : plans) {
            if (checked.overwrite()) {
                removeUnpinned(schedule, plan.line(), from);
            }
            for (LocalDate date = from; !date.isAfter(month.atEndOfMonth()); date = date.plusDays(1)) {
                int day = (int) ChronoUnit.DAYS.between(from, date);
                for (ShiftType type : ShiftType.values()) {
                    if (findShift(schedule, plan.line(), date, type).isPresent()) {
                        kept++;
                        continue;
                    }
                    Crew crew = crewOn(plan.crews(), plan.offset(), day, type);
                    try {
                        editor.addCrewShift(schedule, new CrewShiftRequest(date, type, plan.line().getId(),
                                crew.getId()));
                        created++;
                    } catch (IllegalStateException e) {
                        messages.add(busyMessage(schedule, plan.line(), date, type, crew));
                    }
                }
            }
        }
        return new GenerateResult(created, kept, List.copyOf(messages));
    }

    /** The crew of the rotation on day {@code day} (0 = first generated date). */
    static Crew crewOn(List<Crew> crews, int offset, int day, ShiftType type) {
        int place = type == ShiftType.DAY ? day + offset : day + offset - 1;
        return crews.get(DAY_ORDER[Math.floorMod(place, CYCLE)]);
    }

    /** Crews of the line, sorted by id. */
    static List<Crew> crewsOf(CrewSchedule schedule, Line line) {
        return schedule.getCrews().stream()
                .filter(crew -> line.getId().equals(crew.getLineId()))
                .sorted(Comparator.comparing(Crew::getId))
                .toList();
    }

    /** The start crew works DAY on day 0: its place in D is the offset. */
    private static int offsetOfStartCrew(List<Crew> crews, int startCrewId) {
        return placeInDayOrder(crews, crews.stream().filter(c -> c.getId().equals(startCrewId)).findFirst()
                .orElseThrow());
    }

    /**
     * The offset that continues the crew shifts of the line before {@code from}: for every crew of the line, its
     * last crew shift on this line before {@code from} (DAY on x = cycle day 0 on x, NIGHT on x = cycle day 1 on x),
     * moved forward to {@code from}. 0 if there are none.
     */
    private static int offsetFromEarlierShifts(CrewSchedule schedule, Line line, List<Crew> crews, LocalDate from) {
        Set<Integer> offsets = new HashSet<>();
        for (Crew crew : crews) {
            Optional<CrewShift> last = schedule.getCrewShifts().stream()
                    .filter(shift -> shift.getLine().equals(line) && shift.getCrew().equals(crew)
                            && shift.getDate().isBefore(from))
                    .max(Comparator.comparing(CrewShift::getStart));
            if (last.isEmpty()) {
                continue;
            }
            // Day index of the shift (negative: before from), +1 for a night shift (the day after its DAY)
            long day = ChronoUnit.DAYS.between(from, last.get().getDate());
            long dayShiftDay = last.get().getType() == ShiftType.DAY ? day : day - 1;
            offsets.add(Math.floorMod(placeInDayOrder(crews, crew) - dayShiftDay, CYCLE));
        }
        if (offsets.size() > 1) {
            throw new IllegalStateException("Линия " + line.getId() + ": смены бригад до " + from
                    + " не подходят под один цикл. Выберите бригаду, которая начинает.");
        }
        return offsets.isEmpty() ? 0 : offsets.iterator().next();
    }

    private static int placeInDayOrder(List<Crew> crews, Crew crew) {
        int index = crews.indexOf(crew);
        for (int place = 0; place < CYCLE; place++) {
            if (DAY_ORDER[place] == index) {
                return place;
            }
        }
        throw new IllegalArgumentException("Crew " + crew + " is not a crew of the line.");
    }

    private void removeUnpinned(CrewSchedule schedule, Line line, LocalDate from) {
        schedule.getCrewShifts().stream()
                .filter(shift -> shift.getLine().equals(line) && !shift.getDate().isBefore(from) && !shift.isPinned())
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

    private record LinePlan(Line line, List<Crew> crews, int offset) {
    }

    /**
     * @param lineId      null: every line of the version
     * @param from        first generated date; null: the 1st of the version's month
     * @param startCrewId this crew works DAY on {@code from}; only with a {@code lineId}; null: continue the earlier
     *                    crew shifts of the line (none: c0)
     * @param overwrite   true: remove the unpinned crew shifts of the line from {@code from} on first;
     *                    false: only fill empty line shifts
     */
    public record GenerateRequest(String lineId, LocalDate from, Integer startCrewId, boolean overwrite) {
    }

    /**
     * @param created  crew shifts added
     * @param kept     line shifts that already had a crew (pinned, or {@code overwrite = false}) and were kept
     * @param messages for the user, in Russian: skipped lines, line shifts left empty
     */
    public record GenerateResult(int created, int kept, List<String> messages) {
    }
}
