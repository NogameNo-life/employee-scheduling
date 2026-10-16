package org.acme.employeescheduling.domain;

import java.time.LocalDate;

import ai.timefold.solver.core.api.domain.entity.PlanningEntity;
import ai.timefold.solver.core.api.domain.entity.PlanningPin;
import ai.timefold.solver.core.api.domain.lookup.PlanningId;
import ai.timefold.solver.core.api.domain.variable.PlanningVariable;
import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/**
 * One seat in a crew shift that needs an employee with a specific role.
 * The number of seats per crew shift comes from PLR_RLBR (crew, role, NPERS).
 */
@PlanningEntity
@Getter
@Setter
@NoArgsConstructor
@EqualsAndHashCode(onlyExplicitlyIncluded = true)
public class Position {

    @PlanningId
    @EqualsAndHashCode.Include
    private String id;

    private CrewShift crewShift;
    private Role role;

    /** Manually assigned or historical (previous month): the solver will not change it. */
    @PlanningPin
    private boolean pinned;

    /** Chosen by the solver. May stay null when there are not enough people. */
    @PlanningVariable(allowsUnassigned = true)
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
