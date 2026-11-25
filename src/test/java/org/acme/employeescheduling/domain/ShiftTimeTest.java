package org.acme.employeescheduling.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalTime;

import org.junit.jupiter.api.Test;

class ShiftTimeTest {

    private static final LocalDate DATE = LocalDate.of(2026, 11, 3);

    @Test
    void hoursMinutes() {
        assertThat(ShiftTime.fromHoursMinutes(new BigDecimal("8.00"))).isEqualTo(LocalTime.of(8, 0));
        assertThat(ShiftTime.fromHoursMinutes(new BigDecimal("8.30"))).isEqualTo(LocalTime.of(8, 30));
        assertThat(ShiftTime.fromHoursMinutes(new BigDecimal("20.00"))).isEqualTo(LocalTime.of(20, 0));
        assertThat(ShiftTime.fromHoursMinutes(new BigDecimal("0.00"))).isEqualTo(LocalTime.MIDNIGHT);
        assertThat(ShiftTime.fromHoursMinutes(new BigDecimal("23.59"))).isEqualTo(LocalTime.of(23, 59));
        assertThat(ShiftTime.fromHoursMinutes(new BigDecimal("8"))).isEqualTo(LocalTime.of(8, 0));
    }

    @Test
    void invalidHoursMinutes() {
        for (String value : new String[] { "8.60", "8.75", "24.00", "-1.00", "8.305" }) {
            assertThatThrownBy(() -> ShiftTime.fromHoursMinutes(new BigDecimal(value)))
                    .as(value).isInstanceOf(IllegalArgumentException.class);
        }
        assertThatThrownBy(() -> ShiftTime.fromHoursMinutes(null)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void shiftWhoseEndIsNotAfterItsStartEndsTheNextDay() {
        ShiftTime day = new ShiftTime(ShiftType.DAY, LocalTime.of(8, 0), LocalTime.of(20, 0), null);
        ShiftTime night = new ShiftTime(ShiftType.NIGHT, LocalTime.of(20, 0), LocalTime.of(8, 30), null);

        assertThat(day.endOn(DATE)).isEqualTo(DATE.atTime(20, 0));
        assertThat(day.hours()).isEqualTo(12.0);
        assertThat(night.startOn(DATE)).isEqualTo(DATE.atTime(20, 0));
        assertThat(night.endOn(DATE)).isEqualTo(DATE.plusDays(1).atTime(8, 30));
        assertThat(night.hours()).isEqualTo(12.5);
    }

    @Test
    void defaults() {
        assertThat(ShiftTime.defaultFor(ShiftType.DAY))
                .isEqualTo(new ShiftTime(ShiftType.DAY, LocalTime.of(8, 0), LocalTime.of(20, 0), null));
        assertThat(ShiftTime.defaultFor(ShiftType.NIGHT))
                .isEqualTo(new ShiftTime(ShiftType.NIGHT, LocalTime.of(20, 0), LocalTime.of(8, 0), null));
    }

    @Test
    void shiftTypeCodes() {
        assertThat(ShiftType.fromCode("1")).isEqualTo(ShiftType.DAY);
        assertThat(ShiftType.fromCode("2         ")).isEqualTo(ShiftType.NIGHT);
        assertThat(ShiftType.DAY.getCode()).isEqualTo("1");
        assertThat(ShiftType.NIGHT.getCode()).isEqualTo("2");
        assertThatThrownBy(() -> ShiftType.fromCode("3")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> ShiftType.fromCode(null)).isInstanceOf(IllegalArgumentException.class);
    }
}
