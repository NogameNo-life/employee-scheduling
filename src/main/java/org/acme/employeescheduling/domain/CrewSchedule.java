package org.acme.employeescheduling.domain;

import java.time.YearMonth;
import java.util.List;
import java.util.Map;

import com.fasterxml.jackson.annotation.JsonIgnore;

import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/**
 * One schedule version for one month: the crew schedule by line (entered by the user) and the employees
 * in every crew shift. Crews, employees and the composition are the version's own copies, so the user can
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

    private List<Role> roles;

    private List<Employee> employees;

    /**
     * Crew id -> composition versions (PLR_RLBR from the first day of the month, then the user's changes).
     * Not in the JSON: {@code GET /crew-schedules/crews} has it per crew.
     */
    @JsonIgnore
    private Map<Integer, CrewComposition> composition;

    private List<CrewShift> crewShifts;

    /** One seat per role and person in every crew shift, from {@link #composition}. */
    private List<Position> positions;

    public CrewSchedule(YearMonth month, List<Line> lines, List<Crew> crews, List<Role> roles,
            List<Employee> employees, Map<Integer, CrewComposition> composition, List<CrewShift> crewShifts,
            List<Position> positions) {
        this.month = month;
        this.lines = lines;
        this.crews = crews;
        this.roles = roles;
        this.employees = employees;
        this.composition = composition;
        this.crewShifts = crewShifts;
        this.positions = positions;
    }
}
