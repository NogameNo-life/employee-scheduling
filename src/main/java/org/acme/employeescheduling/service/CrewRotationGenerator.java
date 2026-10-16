package org.acme.employeescheduling.service;

import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

import jakarta.enterprise.context.ApplicationScoped;

import org.acme.employeescheduling.domain.Crew;
import org.acme.employeescheduling.domain.CrewShift;
import org.acme.employeescheduling.domain.Line;
import org.acme.employeescheduling.domain.ShiftType;
import org.acme.employeescheduling.service.LineSchedule.LineDay;
import org.apache.commons.lang3.tuple.Pair;

/**
 * Generates the crew schedule (step 3 of the specification) with the
 * "Day - Night - Sleep day - Day off" rotation. No solver is needed for this.
 *
 * <p>For each line, pass 4 crews in the order they start their first day shift
 * on {@code anchorDate}. Example from the specification, line 1:
 * [Crew1, Crew3, Crew4, Crew2] gives
 * <pre>
 *          Day1  Day2  Day3  Day4  Day5
 * DAY      Cr1   Cr3   Cr4   Cr2   Cr1
 * NIGHT    Cr2   Cr1   Cr3   Cr4   Cr2
 * </pre>
 * Keep {@code anchorDate} fixed (e.g. the date the rotation started) so the
 * pattern continues correctly from one month to the next.
 */
@ApplicationScoped
public class CrewRotationGenerator {

    /** Day, Night, Sleep day, Day off. */
    public static final int CYCLE_LENGTH = 4;

    public List<CrewShift> generate(LocalDate fromInclusive, LocalDate toInclusive, LocalDate anchorDate,
            Map<Line, List<Crew>> crewsByLine) {
        List<CrewShift> result = new ArrayList<>();
        for (LocalDate date = fromInclusive; !date.isAfter(toInclusive); date = date.plusDays(1)) {
            long dayIndex = ChronoUnit.DAYS.between(anchorDate, date);
            for (Map.Entry<Line, List<Crew>> entry : crewsByLine.entrySet()) {
                Line line = entry.getKey();
                List<Crew> crews = entry.getValue();
                if (crews.size() != CYCLE_LENGTH) {
                    throw new IllegalArgumentException("Line " + line + " must have exactly "
                            + CYCLE_LENGTH + " crews, but has " + crews.size() + ".");
                }
                if (new HashSet<>(crews).size() != crews.size()) {
                    throw new IllegalArgumentException("Line " + line + " has the same crew more than once.");
                }
                for (int k = 0; k < crews.size(); k++) {
                    int phase = Math.floorMod(dayIndex - k, CYCLE_LENGTH);
                    ShiftType type = switch (phase) {
                        case 0 -> ShiftType.DAY;
                        case 1 -> ShiftType.NIGHT;
                        default -> null; // sleep day or day off
                    };
                    if (type != null) {
                        Crew crew = crews.get(k);
                        String id = date + "-" + type + "-" + line.getId() + "-" + crew.getId();
                        result.add(new CrewShift(id, date, type, line, crew));
                    }
                }
            }
        }
        return result;
    }

    /** Groups crew shifts into one pair (day crew, night crew) per line and date. */
    public List<LineSchedule> toLineSchedules(List<CrewShift> crewShifts) {
        Map<Line, TreeMap<LocalDate, Crew[]>> byLine = new LinkedHashMap<>();
        for (CrewShift shift : crewShifts) {
            Crew[] pair = byLine.computeIfAbsent(shift.getLine(), l -> new TreeMap<>())
                    .computeIfAbsent(shift.getDate(), d -> new Crew[2]);
            pair[shift.getType() == ShiftType.DAY ? 0 : 1] = shift.getCrew();
        }
        List<LineSchedule> result = new ArrayList<>();
        byLine.forEach((line, days) -> result.add(new LineSchedule(line, days.entrySet().stream()
                .map(e -> new LineDay(e.getKey(), Pair.of(e.getValue()[0], e.getValue()[1])))
                .toList())));
        return result;
    }
}
