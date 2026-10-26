package org.acme.employeescheduling.domain;

import java.util.List;

import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/** One schedule version for one month: the crew schedule by line and the employees in every crew shift. */
@Getter
@Setter
@NoArgsConstructor
public class CrewSchedule {

    /** Production lines (PLR_PLINES), also the ones that got no crews. */
    private List<Line> lines;

    /** Crews, each with its own employees. */
    private List<Crew> crews;

    private List<Employee> employees;

    private List<CrewShift> crewShifts;

    /** One seat per role and person in every crew shift (PLR_RLBR). */
    private List<Position> positions;

    public CrewSchedule(List<Line> lines, List<Crew> crews, List<Employee> employees, List<CrewShift> crewShifts,
            List<Position> positions) {
        this.lines = lines;
        this.crews = crews;
        this.employees = employees;
        this.crewShifts = crewShifts;
        this.positions = positions;
    }
}
