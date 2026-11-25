package org.acme.employeescheduling.domain;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonProperty;

import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/**
 * "Crew X works on line Y, on date Z, day or night shift".
 * Entered by the user in the crew schedule editor.
 *
 * <p>
 * The people of the shift are the crew's employees (the default composition), but one shift can differ:
 * some crew employees do not work it ({@link #absentEmployees}, e.g. sick) and employees of other crews work it
 * too ({@link #extraEmployees}, e.g. as substitutes). Only the differences are kept, so a change of the crew
 * itself still applies to all its shifts. Employees on an {@link Absence} (vacation, sick leave, dismissal) never
 * work, and the crew members are the employees who are in the crew on the date of the shift
 * ({@link Employee#getCrewOn}).
 */
@Getter
@Setter
@NoArgsConstructor
@EqualsAndHashCode(onlyExplicitlyIncluded = true)
public class CrewShift {

    @EqualsAndHashCode.Include
    private String id;

    private LocalDate date;

    /** Type and times of the shift (PLR_SM of the version). */
    @JsonIgnore
    private ShiftTime time;

    private Line line;
    private Crew crew;

    /** Set by the user: a pinned crew shift cannot be moved or removed until it is unpinned. */
    private boolean pinned;

    /** Employees of the crew who do not work this shift (only this one). */
    @JsonIgnore
    private Set<Employee> absentEmployees = new HashSet<>();

    /** Employees of other crews who work this shift too (only this one). */
    @JsonIgnore
    private Set<Employee> extraEmployees = new HashSet<>();

    public CrewShift(String id, LocalDate date, ShiftTime time, Line line, Crew crew) {
        this.id = id;
        this.date = date;
        this.time = time;
        this.line = line;
        this.crew = crew;
    }

    /**
     * The people who work this shift: the crew's members without the absent ones, plus the extra ones,
     * without the employees on an absence.
     */
    @JsonIgnore
    public List<Employee> getEmployees() {
        return Stream.concat(
                crew.getEmployees().stream().filter(employee -> isMember(employee) && !absentEmployees.contains(employee)),
                extraEmployees.stream())
                .filter(employee -> !employee.isUnavailableOn(date))
                .toList();
    }

    public boolean isWorkedBy(Employee employee) {
        return !employee.isUnavailableOn(date)
                && (extraEmployees.contains(employee) || (isMember(employee) && !absentEmployees.contains(employee)));
    }

    /** The employee is in the crew on the date of this shift. */
    public boolean isMember(Employee employee) {
        return crew.getEmployees().contains(employee) && crew.equals(employee.getCrewOn(date));
    }

    /** Personnel numbers of {@link #getEmployees()}, sorted (ids only, the employees are in the version). */
    @JsonProperty
    public Set<Integer> getEmployeeIds() {
        return ids(getEmployees().stream());
    }

    @JsonProperty
    public Set<Integer> getAbsentEmployeeIds() {
        return ids(absentEmployees.stream());
    }

    @JsonProperty
    public Set<Integer> getExtraEmployeeIds() {
        return ids(extraEmployees.stream());
    }

    /** Crew members and extra employees who do not work this shift because of an {@link Absence}. */
    @JsonProperty
    public Set<Integer> getUnavailableEmployeeIds() {
        return ids(Stream.concat(crew.getEmployees().stream().filter(this::isMember), extraEmployees.stream())
                .filter(employee -> employee.isUnavailableOn(date)));
    }

    /** People needed in this shift: the crew's {@code staffCount}; null = not set. */
    @JsonProperty
    public Integer getStaffCount() {
        return crew.getStaffCount();
    }

    /** Fewer people than the crew needs. Never true when the crew has no {@code staffCount}. */
    @JsonIgnore
    public boolean isDeficit() {
        Integer staffCount = getStaffCount();
        return staffCount != null && getEmployees().size() < staffCount;
    }

    private static Set<Integer> ids(Stream<Employee> employees) {
        return employees.map(Employee::getId).collect(Collectors.toCollection(TreeSet::new));
    }

    public ShiftType getType() {
        return time.type();
    }

    /** Start on {@link #date}, from PLR_SM. */
    public LocalDateTime getStart() {
        return time.startOn(date);
    }

    /** End, from PLR_SM: on the next day if the end time is not after the start time (night shift). */
    public LocalDateTime getEnd() {
        return time.endOn(date);
    }

    /** Length of the shift in hours, from its times. */
    public double getHours() {
        return time.hours();
    }

    @Override
    public String toString() {
        return date + " " + getType() + " " + line + " " + crew;
    }
}
