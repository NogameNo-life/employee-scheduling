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
     * Employees of this crew (BD_CARD.BRID). Not in the JSON: a crew is repeated in every position,
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
