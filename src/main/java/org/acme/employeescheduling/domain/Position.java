package org.acme.employeescheduling.domain;

import java.time.LocalDate;

import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/**
 * One seat in a crew shift that needs an employee with a specific role.
 * The number of seats per crew shift comes from PLR_RLBR (crew, role, NPERS).
 */
@Getter
@Setter
@NoArgsConstructor
@EqualsAndHashCode(onlyExplicitlyIncluded = true)
public class Position {

    @EqualsAndHashCode.Include
    private String id;

    private CrewShift crewShift;
    private Role role;

    /** Manually assigned or historical (previous month): re-planning will not change it. */
    private boolean pinned;

    /** Set in the editor. May stay null when there are not enough people. */
    private Employee employee;

    public Position(String id, CrewShift crewShift, Role role) {
        this.id = id;
        this.crewShift = crewShift;
        this.role = role;
    }

    public LocalDate getDate() {
        return crewShift.getDate();
    }

    public Crew getCrew() {
        return crewShift.getCrew();
    }

    public ShiftType getShiftType() {
        return crewShift.getType();
    }

    @Override
    public String toString() {
        return crewShift + " " + role + " -> " + employee;
    }
}
