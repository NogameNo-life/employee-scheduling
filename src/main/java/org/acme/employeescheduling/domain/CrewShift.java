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
 * itself still applies to all its shifts.
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

    /** Employees of the crew who do not work this shift (only this one). */
    @JsonIgnore
    private Set<Employee> absentEmployees = new HashSet<>();

    /** Employees of other crews who work this shift too (only this one). */
    @JsonIgnore
    private Set<Employee> extraEmployees = new HashSet<>();

    public CrewShift(String id, LocalDate date, ShiftType type, Line line, Crew crew) {
        this.id = id;
        this.date = date;
        this.type = type;
        this.line = line;
        this.crew = crew;
    }

    /** The people who work this shift: the crew's employees without the absent ones, plus the extra ones. */
    @JsonIgnore
    public List<Employee> getEmployees() {
        return Stream.concat(crew.getEmployees().stream().filter(employee -> !absentEmployees.contains(employee)),
                extraEmployees.stream()).toList();
    }

    public boolean isWorkedBy(Employee employee) {
        return extraEmployees.contains(employee)
                || (crew.getEmployees().contains(employee) && !absentEmployees.contains(employee));
    }

    /** Personnel numbers of {@link #getEmployees()}, sorted (ids only: a crew shift is in every position). */
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

    private static Set<Integer> ids(Stream<Employee> employees) {
        return employees.map(Employee::getId).collect(Collectors.toCollection(TreeSet::new));
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
