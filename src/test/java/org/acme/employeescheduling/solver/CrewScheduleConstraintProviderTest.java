package org.acme.employeescheduling.solver;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import ai.timefold.solver.test.api.score.stream.ConstraintVerifier;

import org.acme.employeescheduling.domain.Crew;
import org.acme.employeescheduling.domain.CrewSchedule;
import org.acme.employeescheduling.domain.CrewShift;
import org.acme.employeescheduling.domain.Employee;
import org.acme.employeescheduling.domain.Line;
import org.acme.employeescheduling.domain.Position;
import org.acme.employeescheduling.domain.Role;
import org.acme.employeescheduling.domain.ShiftType;
import org.junit.jupiter.api.Test;

class CrewScheduleConstraintProviderTest {

    private static final LocalDate MONDAY = LocalDate.of(2026, 11, 2);
    private static final Line LINE_1 = new Line("L1", "Line 1");
    private static final Crew CREW_1 = new Crew(1, "Crew 1");
    private static final Crew CREW_2 = new Crew(2, "Crew 2");
    private static final Role OPERATOR = new Role(10, "Operator");
    private static final Role PACKER = new Role(20, "Packer");

    private final ConstraintVerifier<CrewScheduleConstraintProvider, CrewSchedule> verifier =
            ConstraintVerifier.build(new CrewScheduleConstraintProvider(), CrewSchedule.class, Position.class);

    private int idCounter = 0;

    private Position position(LocalDate date, ShiftType type, Crew crew, Role role, Employee employee) {
        String id = String.valueOf(idCounter++);
        Position position = new Position(id, new CrewShift(id, date, type, LINE_1, crew), role);
        position.setEmployee(employee);
        return position;
    }

    private static Employee employee(int id, Crew crew, Role... roles) {
        return new Employee(id, "Employee " + id, crew, Set.of(roles));
    }

    @Test
    void missingRole() {
        Employee ann = employee(1, CREW_1, OPERATOR);
        verifier.verifyThat(CrewScheduleConstraintProvider::missingRole)
                .given(position(MONDAY, ShiftType.DAY, CREW_1, OPERATOR, ann),
                        position(MONDAY.plusDays(1), ShiftType.DAY, CREW_1, PACKER, ann))
                .penalizesBy(1);
    }

    @Test
    void wrongCrew() {
        Employee ann = employee(1, CREW_1, OPERATOR);
        verifier.verifyThat(CrewScheduleConstraintProvider::wrongCrew)
                .given(position(MONDAY, ShiftType.DAY, CREW_1, OPERATOR, ann),
                        position(MONDAY.plusDays(1), ShiftType.DAY, CREW_2, OPERATOR, ann))
                .penalizesBy(1);
    }

    @Test
    void unavailableEmployee() {
        Employee ann = employee(1, CREW_1, OPERATOR);
        ann.getUnavailableDates().add(MONDAY);
        verifier.verifyThat(CrewScheduleConstraintProvider::unavailableEmployee)
                .given(position(MONDAY, ShiftType.DAY, CREW_1, OPERATOR, ann),
                        position(MONDAY.plusDays(1), ShiftType.DAY, CREW_1, OPERATOR, ann))
                .penalizesBy(1);
    }

    @Test
    void oneShiftPerDay() {
        Employee ann = employee(1, CREW_1, OPERATOR, PACKER);
        Employee bob = employee(2, CREW_1, OPERATOR);
        verifier.verifyThat(CrewScheduleConstraintProvider::oneShiftPerDay)
                .given(position(MONDAY, ShiftType.DAY, CREW_1, OPERATOR, ann),
                        position(MONDAY, ShiftType.DAY, CREW_1, PACKER, ann),
                        position(MONDAY, ShiftType.DAY, CREW_1, OPERATOR, bob))
                .penalizesBy(1);
    }

    @Test
    void restAfterNightShift() {
        Employee ann = employee(1, CREW_1, OPERATOR);
        Employee bob = employee(2, CREW_1, OPERATOR);
        verifier.verifyThat(CrewScheduleConstraintProvider::restAfterNightShift)
                .given(position(MONDAY, ShiftType.NIGHT, CREW_1, OPERATOR, ann),
                        position(MONDAY.plusDays(1), ShiftType.DAY, CREW_1, OPERATOR, ann), // broken
                        position(MONDAY, ShiftType.DAY, CREW_1, OPERATOR, bob),
                        position(MONDAY.plusDays(1), ShiftType.NIGHT, CREW_1, OPERATOR, bob)) // OK: day -> night
                .penalizesBy(1);
    }

    @Test
    void maxConsecutiveWorkDays_sevenDaysIsPenalized() {
        Employee ann = employee(1, CREW_1, OPERATOR);
        verifier.verifyThat(CrewScheduleConstraintProvider::maxConsecutiveWorkDays)
                .given(daysInARow(ann, 7).toArray())
                .penalizesBy(1);
    }

    @Test
    void maxConsecutiveWorkDays_sixDaysIsOk() {
        Employee ann = employee(1, CREW_1, OPERATOR);
        verifier.verifyThat(CrewScheduleConstraintProvider::maxConsecutiveWorkDays)
                .given(daysInARow(ann, 6).toArray())
                .penalizesBy(0);
    }

    @Test
    void unfilledPosition() {
        Employee ann = employee(1, CREW_1, OPERATOR);
        verifier.verifyThat(CrewScheduleConstraintProvider::unfilledPosition)
                .given(position(MONDAY, ShiftType.DAY, CREW_1, OPERATOR, ann),
                        position(MONDAY, ShiftType.DAY, CREW_1, OPERATOR, null))
                .penalizesBy(1);
    }

    @Test
    void balanceShiftsPerEmployee() {
        Employee ann = employee(1, CREW_1, OPERATOR);
        Employee bob = employee(2, CREW_1, OPERATOR);
        // ann: 2 shifts (2*2 = 4), bob: 1 shift (1*1 = 1)
        verifier.verifyThat(CrewScheduleConstraintProvider::balanceShiftsPerEmployee)
                .given(position(MONDAY, ShiftType.DAY, CREW_1, OPERATOR, ann),
                        position(MONDAY.plusDays(1), ShiftType.DAY, CREW_1, OPERATOR, ann),
                        position(MONDAY, ShiftType.DAY, CREW_1, OPERATOR, bob))
                .penalizesBy(5);
    }

    private List<Position> daysInARow(Employee employee, int days) {
        List<Position> positions = new ArrayList<>();
        for (int i = 0; i < days; i++) {
            positions.add(position(MONDAY.plusDays(i), ShiftType.DAY, CREW_1, OPERATOR, employee));
        }
        return positions;
    }
}
