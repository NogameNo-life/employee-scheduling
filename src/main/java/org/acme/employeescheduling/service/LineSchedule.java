package org.acme.employeescheduling.service;

import java.time.LocalDate;
import java.util.List;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonProperty;

import org.acme.employeescheduling.domain.Crew;
import org.acme.employeescheduling.domain.Line;
import org.apache.commons.lang3.tuple.Pair;

/**
 * Crew schedule of one line, one entry per day. This is the view the frontend shows:
 * <pre>
 *          Day1  Day2  Day3  ...
 * DAY      Cr1   Cr3   Cr4
 * NIGHT    Cr2   Cr1   Cr3
 * </pre>
 */
public record LineSchedule(Line line, List<LineDay> days) {

    /**
     * One day on the line.
     *
     * @param crews left = day crew (08:00-20:00), right = night crew (20:00-08:00 next day)
     */
    public record LineDay(LocalDate date, @JsonIgnore Pair<Crew, Crew> crews) {

        /** Pair is a Map.Entry, so Jackson would write it as {"Cr1": {...}}. Write named fields instead. */
        @JsonProperty
        public Crew dayCrew() {
            return crews.getLeft();
        }

        @JsonProperty
        public Crew nightCrew() {
            return crews.getRight();
        }
    }
}
