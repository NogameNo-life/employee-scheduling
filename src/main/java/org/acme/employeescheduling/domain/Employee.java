package org.acme.employeescheduling.domain;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;

import com.fasterxml.jackson.annotation.JsonIgnore;

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

    /** The crew this employee belongs to at the start of the month (PLR_TBBR). */
    private Crew crew;

    /** Moves to another crew during the month, sorted by date (see {@link #getCrewOn}). */
    private List<CrewChange> crewChanges = new ArrayList<>();

    /** Roles this employee can perform (PLR_RLTB). */
    private Set<Role> roles = new HashSet<>();

    /** Vacation, sick leave, dismissal: entered by the user, they do not overlap. */
    private List<Absence> absences = new ArrayList<>();

    public Employee(Integer id, String fullName, Crew crew, Set<Role> roles) {
        this.id = id;
        this.fullName = fullName;
        this.crew = crew;
        this.roles = roles;
    }

    public boolean canPerform(Role role) {
        return roles.contains(role);
    }

    /** The crew on {@code date}: {@link #crew}, or the crew of the last change up to that date. */
    public Crew getCrewOn(LocalDate date) {
        Crew result = crew;
        for (CrewChange change : crewChanges) {
            if (!change.from().isAfter(date)) {
                result = change.crew();
            }
        }
        return result;
    }

    /** Every crew the employee is in during the month: {@link #crew} and the crews of the changes. */
    @JsonIgnore
    public Set<Crew> getCrewsOfMonth() {
        Set<Crew> crews = new LinkedHashSet<>();
        crews.add(crew);
        crewChanges.forEach(change -> crews.add(change.crew()));
        return crews;
    }

    public Optional<Absence> getAbsenceOn(LocalDate date) {
        return absences.stream().filter(absence -> absence.covers(date)).findFirst();
    }

    public boolean isUnavailableOn(LocalDate date) {
        return getAbsenceOn(date).isPresent();
    }

    @Override
    public String toString() {
        return fullName + " (" + id + ")";
    }
}
