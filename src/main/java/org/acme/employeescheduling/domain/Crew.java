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

    /**
     * People needed in this crew per shift: the sum of PLR_RLBR.NPERS over all roles of the crew.
     * In a schedule version: on the first day of the month (the composition can change from a later date).
     */
    private int staffCount;

    /**
     * Employees of this crew (PLR_TBBR). Not in the JSON: a crew is repeated in every position,
     * so the list would be sent thousands of times. Every employee has {@code crew} instead.
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
