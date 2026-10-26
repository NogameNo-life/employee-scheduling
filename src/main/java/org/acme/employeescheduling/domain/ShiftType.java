package org.acme.employeescheduling.domain;

import java.time.LocalTime;

/**
 * Two 12-hour shifts per day. Adjust the times if your plant uses different
 * ones.
 */
public enum ShiftType {
    DAY(LocalTime.of(8, 0), LocalTime.of(20, 0)),
    NIGHT(LocalTime.of(20, 0), LocalTime.of(8, 0)); // ends next day

    private final LocalTime start;
    private final LocalTime end;

    ShiftType(LocalTime start, LocalTime end) {
        this.start = start;
        this.end = end;
    }

    public LocalTime getStart() {
        return start;
    }

    public LocalTime getEnd() {
        return end;
    }

    public int getHours() {
        return 12;
    }
}
