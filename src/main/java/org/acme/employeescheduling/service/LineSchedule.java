package org.acme.employeescheduling.service;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonProperty;

import org.acme.employeescheduling.domain.CrewShift;
import org.acme.employeescheduling.domain.Line;
import org.acme.employeescheduling.domain.ShiftType;
import org.apache.commons.lang3.tuple.Pair;

/**
 * Crew schedule of one line, one entry per day that has at least one crew shift.
 * This is the view the frontend shows:
 * <pre>
 *          Day1  Day2  Day3  ...
 * DAY      Cr1   Cr3   Cr4
 * NIGHT    Cr2   Cr1   Cr3
 * </pre>
 */
public record LineSchedule(Line line, List<LineDay> days) {

    /** Groups crew shifts into one pair (day shift, night shift) per line and date, dates in order. */
    public static List<LineSchedule> of(List<CrewShift> crewShifts) {
        Map<Line, TreeMap<LocalDate, CrewShift[]>> byLine = new LinkedHashMap<>();
        for (CrewShift shift : crewShifts) {
            CrewShift[] pair = byLine.computeIfAbsent(shift.getLine(), l -> new TreeMap<>())
                    .computeIfAbsent(shift.getDate(), d -> new CrewShift[2]);
            pair[shift.getType() == ShiftType.DAY ? 0 : 1] = shift;
        }
        List<LineSchedule> result = new ArrayList<>();
        byLine.forEach((line, days) -> result.add(new LineSchedule(line, days.entrySet().stream()
                .map(e -> new LineDay(e.getKey(), Pair.of(e.getValue()[0], e.getValue()[1])))
                .toList())));
        return result;
    }

    /**
     * One day on the line.
     *
     * @param shifts left = day shift (08:00-20:00), right = night shift (20:00-08:00 next day); null = no crew
     */
    public record LineDay(LocalDate date, @JsonIgnore Pair<CrewShift, CrewShift> shifts) {

        /** Pair is a Map.Entry, so Jackson would write it as {"key": value}. Write named fields instead. */
        @JsonProperty
        public CrewShift dayShift() {
            return shifts.getLeft();
        }

        @JsonProperty
        public CrewShift nightShift() {
            return shifts.getRight();
        }
    }
}
