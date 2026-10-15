package org.acme.employeescheduling.domain;

import ai.timefold.solver.core.api.domain.lookup.PlanningId;
import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/** Role an employee can perform (PLR_ROLE.RLID). Problem fact. */
@Getter
@Setter
@NoArgsConstructor
@EqualsAndHashCode(onlyExplicitlyIncluded = true)
public class Role {

    @PlanningId
    @EqualsAndHashCode.Include
    private Integer id;

    private String name;

    public Role(Integer id, String name) {
        this.id = id;
        this.name = name;
    }

    @Override
    public String toString() {
        return name;
    }
}
