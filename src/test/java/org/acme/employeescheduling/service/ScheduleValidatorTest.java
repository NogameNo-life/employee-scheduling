package org.acme.employeescheduling.service;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.LocalDate;
import java.time.LocalTime;
import java.time.YearMonth;
import java.util.List;

import org.acme.employeescheduling.domain.AbsenceType;
import org.acme.employeescheduling.domain.Crew;
import org.acme.employeescheduling.domain.CrewSchedule;
import org.acme.employeescheduling.domain.CrewShift;
import org.acme.employeescheduling.domain.Employee;
import org.acme.employeescheduling.domain.Line;
import org.acme.employeescheduling.domain.ShiftTime;
import org.acme.employeescheduling.domain.ShiftType;
import org.acme.employeescheduling.service.CrewScheduleEditor.AbsenceRequest;
import org.acme.employeescheduling.service.CrewScheduleEditor.CrewShiftRequest;
import org.acme.employeescheduling.service.ScheduleValidator.ScheduleWarning;
import org.acme.employeescheduling.service.ScheduleValidator.WarningType;
import org.junit.jupiter.api.Test;

class ScheduleValidatorTest {

    private static final LocalDate DATE = LocalDate.of(2026, 11, 3);
    private static final List<ShiftTime> TWELVE_HOURS = List.of(ShiftTime.defaultFor(ShiftType.DAY),
            ShiftTime.defaultFor(ShiftType.NIGHT));

    private final CrewScheduleEditor editor = new CrewScheduleEditor();
    private final ScheduleValidator validator = new ScheduleValidator(12);

    /** Crew 1 (Anna, Boris), crew 2 (Clara), lines L1 and L2. */
    private CrewSchedule schedule(List<ShiftTime> shifts) {
        Crew crew1 = new Crew(1, "Cr1");
        Crew crew2 = new Crew(2, "Cr2");
        CrewData data = new CrewData(List.of(crew1, crew2), List.of(new Employee(100, "Anna", crew1),
                new Employee(101, "Boris", crew1), new Employee(200, "Clara", crew2)));
        return new CrewScheduleFactory().createEmpty(YearMonth.of(2026, 11),
                List.of(new Line("L1", null, "Line 1"), new Line("L2", null, "Line 2")), shifts, data);
    }

    private CrewShift add(CrewSchedule schedule, LocalDate date, ShiftType type, String lineId, int crewId) {
        return editor.addCrewShift(schedule, new CrewShiftRequest(date, type, lineId, crewId));
    }

    @Test
    void dayThenNightOnTheSameDate() {
        CrewSchedule schedule = schedule(TWELVE_HOURS);
        CrewShift day = add(schedule, DATE, ShiftType.DAY, "L1", 1);
        CrewShift night = add(schedule, DATE, ShiftType.NIGHT, "L1", 1);

        List<ScheduleWarning> warnings = validator.validate(schedule);

        // One crew warning; its employees (Anna, Boris) do not repeat it
        assertThat(warnings).singleElement().satisfies(warning -> {
            assertThat(warning.type()).isEqualTo(WarningType.CREW_REST);
            assertThat(warning.crewShiftId()).isEqualTo(night.getId());
            assertThat(warning.previousCrewShiftId()).isEqualTo(day.getId());
            assertThat(warning.crewId()).isEqualTo(1);
            assertThat(warning.employeeId()).isNull();
            assertThat(warning.restHours()).isZero();
            assertThat(warning.message()).isEqualTo("Бригада Cr1: отдых 0 ч между сменами 2026-11-03 день (L1)"
                    + " и 2026-11-03 ночь (L1), нужно не меньше 12 ч.");
        });
    }

    @Test
    void nightThenDayNextMorning() {
        CrewSchedule schedule = schedule(TWELVE_HOURS);
        add(schedule, DATE, ShiftType.NIGHT, "L1", 1);
        CrewShift day = add(schedule, DATE.plusDays(1), ShiftType.DAY, "L1", 1);

        assertThat(validator.validate(schedule)).singleElement()
                .extracting(ScheduleWarning::crewShiftId).isEqualTo(day.getId());
    }

    @Test
    void dayThenDayNextDateIsEnoughRest() {
        CrewSchedule schedule = schedule(TWELVE_HOURS);
        add(schedule, DATE, ShiftType.DAY, "L1", 1);
        add(schedule, DATE.plusDays(1), ShiftType.DAY, "L1", 1);
        add(schedule, DATE.plusDays(1), ShiftType.NIGHT, "L1", 2);

        assertThat(validator.validate(schedule)).isEmpty();
    }

    @Test
    void crewOnTwoLines() {
        CrewSchedule schedule = schedule(TWELVE_HOURS);
        add(schedule, DATE, ShiftType.DAY, "L1", 1);
        CrewShift night = add(schedule, DATE, ShiftType.NIGHT, "L2", 1);

        assertThat(validator.validate(schedule)).singleElement().satisfies(warning -> {
            assertThat(warning.crewShiftId()).isEqualTo(night.getId());
            assertThat(warning.message()).contains("(L1)", "(L2)");
        });
    }

    @Test
    void substituteFromAnotherCrew() {
        CrewSchedule schedule = schedule(TWELVE_HOURS);
        add(schedule, DATE, ShiftType.DAY, "L1", 1);
        CrewShift night = add(schedule, DATE, ShiftType.NIGHT, "L2", 2);
        editor.addEmployeeToShift(schedule, night.getId(), 100);

        assertThat(validator.validate(schedule)).singleElement().satisfies(warning -> {
            assertThat(warning.type()).isEqualTo(WarningType.EMPLOYEE_REST);
            assertThat(warning.employeeId()).isEqualTo(100);
            assertThat(warning.crewId()).isEqualTo(2);
            assertThat(warning.crewShiftId()).isEqualTo(night.getId());
            assertThat(warning.message()).startsWith("Anna (100): отдых 0 ч");
        });
    }

    @Test
    void employeeOnAnAbsenceIsNotCounted() {
        CrewSchedule schedule = schedule(TWELVE_HOURS);
        add(schedule, DATE, ShiftType.DAY, "L1", 1);
        CrewShift night = add(schedule, DATE, ShiftType.NIGHT, "L2", 2);
        editor.addEmployeeToShift(schedule, night.getId(), 100);
        editor.addAbsence(schedule, 100, new AbsenceRequest(AbsenceType.SICK_LEAVE, DATE, DATE));

        assertThat(validator.validate(schedule)).isEmpty();
    }

    @Test
    void shiftTimesFromPlrSm() {
        // 13-hour day shifts: only 11 hours between two day shifts
        CrewSchedule schedule = schedule(List.of(
                new ShiftTime(ShiftType.DAY, LocalTime.of(8, 0), LocalTime.of(21, 0), null),
                new ShiftTime(ShiftType.NIGHT, LocalTime.of(21, 0), LocalTime.of(8, 0), null)));
        add(schedule, DATE, ShiftType.DAY, "L1", 1);
        add(schedule, DATE.plusDays(1), ShiftType.DAY, "L1", 1);

        assertThat(validator.validate(schedule)).singleElement().satisfies(warning -> {
            assertThat(warning.restHours()).isEqualTo(11.0);
            assertThat(warning.message()).contains("отдых 11 ч");
        });
        assertThat(new ScheduleValidator(11).validate(schedule)).isEmpty();
    }

    @Test
    void rotationHasNoWarnings() {
        CrewSchedule schedule = schedule(TWELVE_HOURS);
        schedule.getCrews().forEach(crew -> crew.setLineId("L1"));
        // The generator needs 4 crews on the line
        for (int id = 3; id <= 4; id++) {
            Crew crew = new Crew(id, "Cr" + id);
            crew.setLineId("L1");
            schedule.getCrews().add(crew);
        }
        new CrewRotationGenerator(editor).generate(schedule,
                new CrewRotationGenerator.GenerateRequest("L1", null, null, false));

        assertThat(validator.validate(schedule)).isEmpty();
    }
}
