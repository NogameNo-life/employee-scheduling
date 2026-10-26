package org.acme.employeescheduling.domain;

import java.time.LocalDate;
import java.util.HashSet;
import java.util.Set;

import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/** Employee (BD_CARD.TABN). Can be assigned to a {@link Position}. */
@Getter
@Setter
@NoArgsConstructor
@EqualsAndHashCode(onlyExplicitlyIncluded = true)
public class Employee {

    @EqualsAndHashCode.Include
    private Integer id;

    private String fullName;

    /** The crew this employee belongs to (PLR_TBBR). */
    private Crew crew;

    /** Roles this employee can perform (PLR_RLTB). */
    private Set<Role> roles = new HashSet<>();

    /** Vacation, sick leave, etc. Empty until an absence table exists. */
    private Set<LocalDate> unavailableDates = new HashSet<>();

    public Employee(Integer id, String fullName, Crew crew, Set<Role> roles) {
        this.id = id;
        this.fullName = fullName;
        this.crew = crew;
        this.roles = roles;
    }

    public boolean canPerform(Role role) {
        return roles.contains(role);
    }

    public boolean isUnavailableOn(LocalDate date) {
        return unavailableDates.contains(date);
    }

    @Override
    public String toString() {
        return fullName + " (" + id + ")";
    }
}
