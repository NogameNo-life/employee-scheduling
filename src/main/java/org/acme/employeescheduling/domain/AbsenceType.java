package org.acme.employeescheduling.domain;

/** Why an employee does not work for a period (see {@link Absence}). */
public enum AbsenceType {
    VACATION,
    SICK_LEAVE,
    /** Left the company: the absence has no end. */
    DISMISSAL,
    OTHER
}
