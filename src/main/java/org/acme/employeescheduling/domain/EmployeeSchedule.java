package org.acme.employeescheduling.domain;

import java.util.List;

import ai.timefold.solver.core.api.domain.solution.PlanningEntityCollectionProperty;
import ai.timefold.solver.core.api.domain.solution.PlanningScore;
import ai.timefold.solver.core.api.domain.solution.PlanningSolution;
import ai.timefold.solver.core.api.domain.solution.ProblemFactCollectionProperty;
import ai.timefold.solver.core.api.domain.valuerange.ValueRangeProvider;
import ai.timefold.solver.core.api.score.buildin.hardsoftbigdecimal.HardSoftBigDecimalScore;
import ai.timefold.solver.core.api.solver.SolverStatus;
import lombok.Getter;
import lombok.Setter;

@Setter
@Getter
@PlanningSolution
public class EmployeeSchedule {

    @ProblemFactCollectionProperty
    @ValueRangeProvider
    private List<Employee> employees;

    @PlanningEntityCollectionProperty
    private List<Shift> shifts;

    @PlanningScore
    private HardSoftBigDecimalScore score;

    private SolverStatus solverStatus;

    // No-arg constructor required for Timefold
    public EmployeeSchedule() {}

    public EmployeeSchedule(List<Employee> employees, List<Shift> shifts) {
        this.employees = employees;
        this.shifts = shifts;
    }

    public EmployeeSchedule(HardSoftBigDecimalScore score, SolverStatus solverStatus) {
        this.score = score;
        this.solverStatus = solverStatus;
    }

}
