package org.acme.employeescheduling.domain;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
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
 * ({@link Employee#getCrewOn}). The number of people per role can also be changed for this shift only
 * ({@link #composition}).
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

    /** People needed per role in this shift only (instead of PLR_RLBR); null: the composition of the crew. */
    @JsonIgnore
    private Map<Role, Integer> composition;

    public CrewShift(String id, LocalDate date, ShiftType type, Line line, Crew crew) {
        this.id = id;
        this.date = date;
        this.type = type;
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

    /** Crew members and extra employees who do not work this shift because of an {@link Absence}. */
    @JsonProperty
    public Set<Integer> getUnavailableEmployeeIds() {
        return ids(Stream.concat(crew.getEmployees().stream().filter(this::isMember), extraEmployees.stream())
                .filter(employee -> employee.isUnavailableOn(date)));
    }

    /** Role id -> people needed in this shift, or null when the shift has the composition of the crew. */
    @JsonProperty
    public Map<Integer, Integer> getRoleCounts() {
        return composition == null ? null : composition.entrySet().stream()
                .collect(Collectors.toMap(entry -> entry.getKey().getId(), Map.Entry::getValue, Integer::sum, TreeMap::new));
    }

    /** People needed in this shift: from {@link #composition}, or the crew's {@code staffCount}. */
    @JsonProperty
    public int getStaffCount() {
        return composition == null ? crew.getStaffCount()
                : composition.values().stream().mapToInt(Integer::intValue).sum();
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
