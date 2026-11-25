package org.acme.employeescheduling.domain;

import java.time.YearMonth;
import java.util.List;

import com.fasterxml.jackson.annotation.JsonIgnore;

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

    /** Lines active in the month (PLR_LC), also the ones without crew shifts. */
    private List<Line> lines;

    /** Times of the shift types (PLR_SM): DAY and NIGHT. In the JSON as {@code shifts}. */
    private List<ShiftTime> shifts;

    /** Crews, each with its own employees. */
    private List<Crew> crews;

    private List<Employee> employees;

    private List<CrewShift> crewShifts;

    public CrewSchedule(YearMonth month, List<Line> lines, List<ShiftTime> shifts, List<Crew> crews,
            List<Employee> employees, List<CrewShift> crewShifts) {
        this.month = month;
        this.lines = lines;
        this.shifts = shifts;
        this.crews = crews;
        this.employees = employees;
        this.crewShifts = crewShifts;
    }

    /** The times of a shift type; the default times if the version has none for it. */
    @JsonIgnore
    public ShiftTime getShift(ShiftType type) {
        return shifts.stream().filter(shift -> shift.type() == type).findFirst()
                .orElseGet(() -> ShiftTime.defaultFor(type));
    }
}
