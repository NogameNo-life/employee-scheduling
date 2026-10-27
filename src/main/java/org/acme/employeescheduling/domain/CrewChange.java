package org.acme.employeescheduling.domain;

import java.time.LocalDate;

/** From {@code from} on (during the month) the employee is in {@code crew}. */
public record CrewChange(LocalDate from, Crew crew) {
}
