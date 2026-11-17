package org.acme.employeescheduling.domain;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;

import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * Times of a shift type (PLR_SM). A shift whose end is not after its start ends on the next day
 * (e.g. NIGHT 20:00-08:00).
 *
 * @param sng codes of PLR_SM.SNG (NS_TRD), separated by ';' as in the table, trimmed; null if empty
 */
public record ShiftTime(ShiftType type, LocalTime start, LocalTime end, String sng) {

    /** Used when PLR_SM cannot be read or has no row for the type: DAY 08:00-20:00, NIGHT 20:00-08:00. */
    public static ShiftTime defaultFor(ShiftType type) {
        return type == ShiftType.DAY
                ? new ShiftTime(type, LocalTime.of(8, 0), LocalTime.of(20, 0), null)
                : new ShiftTime(type, LocalTime.of(20, 0), LocalTime.of(8, 0), null);
    }

    /**
     * A PLR_SM.TMNR / TMKR value (N5,2) as a time. Read as "hours.minutes": 8.30 = 08:30, 20.00 = 20:00.
     * This is the only place that knows the format: change it here if the real values mean something else
     * (e.g. 8.50 = 08:30).
     *
     * @throws IllegalArgumentException null, negative, more than 2 decimals, minutes 60 or more, hours 24 or more
     */
    public static LocalTime fromHoursMinutes(BigDecimal value) {
        if (value == null || value.signum() < 0) {
            throw new IllegalArgumentException("Shift time must be hours.minutes, got: " + value);
        }
        BigDecimal scaled;
        try {
            scaled = value.setScale(2, RoundingMode.UNNECESSARY);
        } catch (ArithmeticException e) {
            throw new IllegalArgumentException("Shift time must be hours.minutes, got: " + value, e);
        }
        int hours = scaled.intValue();
        int minutes = scaled.subtract(BigDecimal.valueOf(hours)).movePointRight(2).intValue();
        if (hours >= 24 || minutes >= 60) {
            throw new IllegalArgumentException("Shift time must be hours.minutes (0.00-23.59), got: " + value);
        }
        return LocalTime.of(hours, minutes);
    }

    public LocalDateTime startOn(LocalDate date) {
        return date.atTime(start);
    }

    /** End of the shift that starts on {@code date}: the next day if the end is not after the start. */
    public LocalDateTime endOn(LocalDate date) {
        return (end.isAfter(start) ? date : date.plusDays(1)).atTime(end);
    }

    /** Length of the shift in hours, e.g. 12 or 11.5. */
    @JsonProperty
    public double hours() {
        return Duration.between(startOn(LocalDate.EPOCH), endOn(LocalDate.EPOCH)).toMinutes() / 60.0;
    }
}
