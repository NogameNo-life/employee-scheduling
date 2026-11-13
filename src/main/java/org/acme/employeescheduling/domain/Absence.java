package org.acme.employeescheduling.domain;

import java.time.LocalDate;

/**
 * The employee works no shift that starts on a date from {@code from} to {@code to} (both included), e.g. vacation
 * or sick leave. {@code to} is null for a dismissal: from {@code from} on. Entered by the user; there is no
 * absence table yet, so absences live in the schedule version only.
 */
public record Absence(String id, AbsenceType type, LocalDate from, LocalDate to) {

    public boolean covers(LocalDate date) {
        return !date.isBefore(from) && (to == null || !date.isAfter(to));
    }

    public boolean overlaps(Absence other) {
        return (other.to == null || !from.isAfter(other.to)) && (to == null || !other.from.isAfter(to));
    }
}
