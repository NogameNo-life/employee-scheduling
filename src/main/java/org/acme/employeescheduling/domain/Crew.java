package org.acme.employeescheduling.domain;

import java.util.ArrayList;
import java.util.List;

import com.fasterxml.jackson.annotation.JsonIgnore;

import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/** Crew / brigade (PLR_BR.BRID). */
@Getter
@Setter
@NoArgsConstructor
@EqualsAndHashCode(onlyExplicitlyIncluded = true)
public class Crew {

    @EqualsAndHashCode.Include
    private Integer id;

    private String name;

    /** People needed in this crew per shift (PLR_BR.NTB). */
    private int staffCount;

    /**
     * Employees of this crew (PLR_TBBR). Not in the JSON: a crew is repeated in every crew shift,
     * so the list would be sent many times. Every employee has {@code crew} instead.
     */
    @JsonIgnore
    private List<Employee> employees = new ArrayList<>();

    public Crew(Integer id, String name) {
        this.id = id;
        this.name = name;
    }

    @Override
    public String toString() {
        return name;
    }
}
