package org.acme.employeescheduling.domain;

import java.util.List;

import ai.timefold.solver.core.api.domain.solution.PlanningEntityCollectionProperty;
import ai.timefold.solver.core.api.domain.solution.PlanningScore;
import ai.timefold.solver.core.api.domain.solution.PlanningSolution;
import ai.timefold.solver.core.api.domain.solution.ProblemFactCollectionProperty;
import ai.timefold.solver.core.api.domain.valuerange.ValueRangeProvider;
import ai.timefold.solver.core.api.score.buildin.hardmediumsoft.HardMediumSoftScore;
import ai.timefold.solver.core.api.solver.SolverStatus;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

@PlanningSolution
@Getter
@Setter
@NoArgsConstructor
public class CrewSchedule {

    /** Production lines (PLR_PLINES), also the ones that got no crews. */
    @ProblemFactCollectionProperty
    private List<Line> lines;

    /** Crews, each with its own employees. */
    @ProblemFactCollectionProperty
    private List<Crew> crews;

    @ProblemFactCollectionProperty
    @ValueRangeProvider
    private List<Employee> employees;

    @ProblemFactCollectionProperty
    private List<CrewShift> crewShifts;

    @PlanningEntityCollectionProperty
    private List<Position> positions;

    /** hard = rules, medium = unfilled seats, soft = fairness. */
    @PlanningScore
    private HardMediumSoftScore score;

    private SolverStatus solverStatus;

    public CrewSchedule(List<Line> lines, List<Crew> crews, List<Employee> employees, List<CrewShift> crewShifts,
            List<Position> positions) {
        this.lines = lines;
        this.crews = crews;
        this.employees = employees;
        this.crewShifts = crewShifts;
        this.positions = positions;
    }
}
