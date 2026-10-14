package org.acme.employeescheduling.domain;

import java.time.LocalDate;
import java.util.Objects;
import java.util.Set;

import ai.timefold.solver.core.api.domain.lookup.PlanningId;
import lombok.Getter;
import lombok.Setter;

@Setter
@Getter
public class Employee {
    @PlanningId
    private String name;
    private Set<String> skills;

    private Set<LocalDate> unavailableDates;
    private Set<LocalDate> undesiredDates;
    private Set<LocalDate> desiredDates;

    public Employee() {

    }

    public Employee(String name, Set<String> skills,
        Set<LocalDate> unavailableDates, Set<LocalDate> undesiredDates, Set<LocalDate> desiredDates) {
        this.name = name;
        this.skills = skills;
        this.unavailableDates = unavailableDates;
        this.undesiredDates = undesiredDates;
        this.desiredDates = desiredDates;
    }

    @Override
    public String toString() {
        return name;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (!(o instanceof Employee employee)) {
            return false;
        }
        return Objects.equals(getName(), employee.getName());
    }

    @Override
    public int hashCode() {
        return getName().hashCode();
    }
}
