package org.acme.employeescheduling.service;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.LocalDate;
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
import org.acme.employeescheduling.service.EmployeeDayStatus.Code;
import org.acme.employeescheduling.service.EmployeeDayStatus.DayStatus;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class EmployeeDayStatusTest {

    private static final LocalDate DAY1 = LocalDate.of(2026, 11, 1);

    private final CrewScheduleEditor editor = new CrewScheduleEditor();
    private CrewSchedule schedule;

    /** Crew 1: Anna (100), Clara (300); crew 2: Boris (200). Lines L1, L2. */
    @BeforeEach
    void createSchedule() {
        Crew crew1 = new Crew(1, "Cr1");
        Crew crew2 = new Crew(2, "Cr2");
        schedule = new CrewScheduleFactory().createEmpty(YearMonth.of(2026, 11),
                List.of(new Line("L1", null, "Line 1"), new Line("L2", null, "Line 2")),
                List.of(ShiftTime.defaultFor(ShiftType.DAY), ShiftTime.defaultFor(ShiftType.NIGHT)),
                new CrewData(List.of(crew1, crew2), List.of(new Employee(100, "Anna", crew1),
                        new Employee(200, "Boris", crew2), new Employee(300, "Clara", crew1))));
    }

    private void add(LocalDate date, ShiftType type, String lineId, int crewId) {
        editor.addCrewShift(schedule, new CrewShiftRequest(date, type, lineId, crewId));
    }

    private Employee employee(int id) {
        return schedule.getEmployees().stream().filter(e -> e.getId() == id).findFirst().orElseThrow();
    }

    private DayStatus status(int employeeId, LocalDate date) {
        return EmployeeDayStatus.of(schedule).get(employee(employeeId), date);
    }

    @Test
    void dayNightSleepAndDayOff() {
        add(DAY1, ShiftType.DAY, "L1", 1);
        add(DAY1, ShiftType.NIGHT, "L1", 2);

        assertThat(status(100, DAY1).code()).isEqualTo(Code.DAY);
        assertThat(status(200, DAY1).code()).isEqualTo(Code.NIGHT);
        assertThat(status(200, DAY1.plusDays(1)).code()).isEqualTo(Code.SLEEP);
        assertThat(status(100, DAY1.plusDays(1)).code()).isEqualTo(Code.OFF);
        assertThat(status(200, DAY1.plusDays(2)).code()).isEqualTo(Code.OFF);
        assertThat(status(100, DAY1).hours()).isEqualTo(12.0);
        assertThat(Code.SLEEP.getLetter()).isEqualTo("О");
    }

    @Test
    void absencesHaveTheirCodes() {
        add(DAY1, ShiftType.DAY, "L1", 1);
        editor.addAbsence(schedule, 300, new AbsenceRequest(AbsenceType.SICK_LEAVE, DAY1, DAY1.plusDays(1)));
        editor.addAbsence(schedule, 300, new AbsenceRequest(AbsenceType.VACATION, DAY1.plusDays(2), DAY1.plusDays(3)));
        editor.addAbsence(schedule, 300, new AbsenceRequest(AbsenceType.OTHER, DAY1.plusDays(4), DAY1.plusDays(4)));
        editor.addAbsence(schedule, 300, new AbsenceRequest(AbsenceType.DISMISSAL, DAY1.plusDays(10), null));

        DayStatus sick = status(300, DAY1);
        assertThat(sick.code()).isEqualTo(Code.SICK_LEAVE);
        assertThat(sick.code().getLetter()).isEqualTo("Б");
        assertThat(sick.worked()).isEmpty();
        assertThat(sick.absence().type()).isEqualTo(AbsenceType.SICK_LEAVE);
        assertThat(status(300, DAY1.plusDays(2)).code().getLetter()).isEqualTo("ОТ");
        assertThat(status(300, DAY1.plusDays(4)).code().getLetter()).isEqualTo("ПР");
        assertThat(status(300, DAY1.plusDays(20)).code().getLetter()).isEqualTo("У");
        assertThat(status(300, DAY1.plusDays(5)).code()).isEqualTo(Code.OFF);
    }

    @Test
    void substituteAndTakenOff() {
        add(DAY1, ShiftType.DAY, "L1", 1);
        String shiftId = DAY1 + "-DAY-L1";
        editor.replaceEmployee(schedule, shiftId, 100, 200);

        DayStatus boris = status(200, DAY1);
        assertThat(boris.code()).isEqualTo(Code.DAY);
        assertThat(boris.substitute()).extracting(CrewShift::getId).containsExactly(shiftId);
        DayStatus anna = status(100, DAY1);
        assertThat(anna.code()).isEqualTo(Code.OFF);
        assertThat(anna.takenOff()).extracting(CrewShift::getId).containsExactly(shiftId);
        assertThat(status(300, DAY1).substitute()).isEmpty();
    }

    @Test
    void dayAndNightOnTheSameDate() {
        add(DAY1, ShiftType.DAY, "L1", 1);
        add(DAY1, ShiftType.NIGHT, "L2", 1);

        DayStatus anna = status(100, DAY1);
        assertThat(anna.code()).isEqualTo(Code.DAY_AND_NIGHT);
        assertThat(anna.code().getLetter()).isEqualTo("ДН");
        assertThat(anna.worked()).extracting(CrewShift::getType).containsExactly(ShiftType.DAY, ShiftType.NIGHT);
        assertThat(anna.hours()).isEqualTo(24.0);
        assertThat(status(100, DAY1.plusDays(1)).code()).isEqualTo(Code.SLEEP);
    }

    @Test
    void theFirstOfTheMonthIsADayOffWithoutTheMonthBefore() {
        assertThat(status(100, DAY1).code()).isEqualTo(Code.OFF);
    }
}
