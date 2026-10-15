package org.acme.employeescheduling.service;

import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import jakarta.enterprise.context.ApplicationScoped;

import org.acme.employeescheduling.domain.Crew;
import org.acme.employeescheduling.domain.CrewShift;
import org.acme.employeescheduling.domain.Line;
import org.acme.employeescheduling.domain.ShiftType;

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
}
