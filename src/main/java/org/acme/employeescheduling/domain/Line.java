package org.acme.employeescheduling.domain;

import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/** Production line of the planner (PLR_LC). */
@Getter
@Setter
@NoArgsConstructor
@EqualsAndHashCode(onlyExplicitlyIncluded = true)
public class Line {

    /** Line code KC (PLR_LC.KC, trimmed), e.g. "БерЦГС1". Crews (PLR_BR.KC) refer to it. */
    @EqualsAndHashCode.Include
    private String id;

    /** Old line code (PLR_LC.KRC, trimmed): line output is recorded by it. */
    private String krc;

    /** PLR_PLINES.SNM of the KRC, or the KC when there is none. */
    private String name;

    public Line(String id, String krc, String name) {
        this.id = id;
        this.krc = krc;
        this.name = name;
    }

    @Override
    public String toString() {
        return name;
    }
}
