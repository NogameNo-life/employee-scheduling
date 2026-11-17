package org.acme.employeescheduling.domain;

import java.time.YearMonth;
import java.util.List;

import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/**
 * One schedule version for one month: the crew schedule by line (entered by the user) and the employees
 * in every crew shift. Crews and employees are the version's own copies, so the user can
 * change them without changing other versions. The lists are mutable: the editor changes them in place.
 */
@Getter
@Setter
@NoArgsConstructor
public class CrewSchedule {

    private YearMonth month;

    /** Production lines (PLR_PLINES), also the ones without crew shifts. */
    private List<Line> lines;

    /** Crews, each with its own employees. */
    private List<Crew> crews;

    private List<Employee> employees;

    private List<CrewShift> crewShifts;

    public CrewSchedule(YearMonth month, List<Line> lines, List<Crew> crews, List<Employee> employees,
            List<CrewShift> crewShifts) {
        this.month = month;
        this.lines = lines;
        this.crews = crews;
        this.employees = employees;
        this.crewShifts = crewShifts;
    }
}
