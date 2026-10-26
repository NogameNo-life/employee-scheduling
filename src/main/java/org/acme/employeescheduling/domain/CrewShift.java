package org.acme.employeescheduling.domain;

import java.time.LocalDate;
import java.time.LocalDateTime;

import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/**
 * "Crew X works on line Y, on date Z, day or night shift".
 * Entered by the user in the crew schedule editor.
 */
@Getter
@Setter
@NoArgsConstructor
@EqualsAndHashCode(onlyExplicitlyIncluded = true)
public class CrewShift {

    @EqualsAndHashCode.Include
    private String id;

    private LocalDate date;
    private ShiftType type;
    private Line line;
    private Crew crew;

    /** Set by the user: a pinned crew shift cannot be moved or removed until it is unpinned. */
    private boolean pinned;

    public CrewShift(String id, LocalDate date, ShiftType type, Line line, Crew crew) {
        this.id = id;
        this.date = date;
        this.type = type;
        this.line = line;
        this.crew = crew;
    }

    public LocalDateTime getStart() {
        return date.atTime(type.getStart());
    }

    public LocalDateTime getEnd() {
        return type == ShiftType.NIGHT
                ? date.plusDays(1).atTime(type.getEnd())
                : date.atTime(type.getEnd());
    }

    @Override
    public String toString() {
        return date + " " + type + " " + line + " " + crew;
    }
}
