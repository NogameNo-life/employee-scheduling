package org.acme.employeescheduling.domain;

import ai.timefold.solver.core.api.domain.lookup.PlanningId;

import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/** Production line. Problem fact. */
@Getter
@Setter
@NoArgsConstructor
@EqualsAndHashCode(onlyExplicitlyIncluded = true)
public class Line {

    @PlanningId
    @EqualsAndHashCode.Include
    private String id;

    private String name;

    public Line(String id, String name) {
        this.id = id;
        this.name = name;
    }

    @Override
    public String toString() {
        return name;
    }
}
