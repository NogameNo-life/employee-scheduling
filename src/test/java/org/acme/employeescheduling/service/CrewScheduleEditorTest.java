package org.acme.employeescheduling.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.LocalDate;
import java.time.YearMonth;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;

import org.acme.employeescheduling.domain.Crew;
import org.acme.employeescheduling.domain.CrewSchedule;
import org.acme.employeescheduling.domain.CrewShift;
import org.acme.employeescheduling.domain.Employee;
import org.acme.employeescheduling.domain.Line;
import org.acme.employeescheduling.domain.Position;
import org.acme.employeescheduling.domain.Role;
import org.acme.employeescheduling.domain.ShiftType;
import org.acme.employeescheduling.service.CrewScheduleEditor.CrewShiftRequest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class CrewScheduleEditorTest {

    private static final LocalDate DAY1 = LocalDate.of(2026, 11, 1);

    private final CrewScheduleFactory factory = new CrewScheduleFactory();
    private final CrewScheduleEditor editor = new CrewScheduleEditor(factory);
    private final Role packer = new Role(10, "Packer");
    private CrewSchedule schedule;

    @BeforeEach
    void createSchedule() {
        Crew crew1 = new Crew(1, "Cr1");
        Crew crew2 = new Crew(2, "Cr2");
        CrewData data = new CrewData(List.of(crew1, crew2), List.of(packer),
                List.of(new Employee(100, "Anna", crew1, new HashSet<>()),
                        new Employee(200, "Boris", crew2, new HashSet<>())),
                Map.of(1, Map.of(packer, 2), 2, Map.of(packer, 1)));
        schedule = factory.createEmpty(YearMonth.of(2026, 11),
                List.of(new Line("L1", "Line 1"), new Line("L2", "Line 2")), data);
    }

    @Test
    void addedCrewShiftGetsPositionsFromTheComposition() {
        CrewShift crewShift = editor.addCrewShift(schedule, request(DAY1, ShiftType.DAY, "L1", 1));

        assertThat(schedule.getCrewShifts()).containsExactly(crewShift);
        assertThat(crewShift.getCrew()).isSameAs(crew(1));
        assertThat(schedule.getPositions()).hasSize(2)
                .allSatisfy(position -> assertThat(position.getCrewShift()).isSameAs(crewShift));
    }

    @Test
    void lineShiftHasAtMostOneCrew() {
        editor.addCrewShift(schedule, request(DAY1, ShiftType.DAY, "L1", 1));

        assertThatThrownBy(() -> editor.addCrewShift(schedule, request(DAY1, ShiftType.DAY, "L1", 2)))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("already has crew Cr1");
        // The other shift of the same day is free
        editor.addCrewShift(schedule, request(DAY1, ShiftType.NIGHT, "L1", 2));
        assertThat(schedule.getCrewShifts()).hasSize(2);
    }

    @Test
    void crewCannotWorkOnTwoLinesAtTheSameTime() {
        editor.addCrewShift(schedule, request(DAY1, ShiftType.DAY, "L1", 1));

        assertThatThrownBy(() -> editor.addCrewShift(schedule, request(DAY1, ShiftType.DAY, "L2", 1)))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("already works on line Line 1");
    }

    @Test
    void rejectsInvalidRequests() {
        assertThatThrownBy(() -> editor.addCrewShift(schedule, request(DAY1.minusDays(1), ShiftType.DAY, "L1", 1)))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("is not in 2026-11");
        assertThatThrownBy(() -> editor.addCrewShift(schedule, request(DAY1, null, "L1", 1)))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> editor.addCrewShift(schedule, request(DAY1, ShiftType.DAY, "L9", 1)))
                .isInstanceOf(NoSuchElementException.class);
        assertThatThrownBy(() -> editor.addCrewShift(schedule, request(DAY1, ShiftType.DAY, "L1", 9)))
                .isInstanceOf(NoSuchElementException.class);
        assertThat(schedule.getCrewShifts()).isEmpty();
    }

    @Test
    void moveChangesTheLineShiftAndCrew() {
        CrewShift crewShift = editor.addCrewShift(schedule, request(DAY1, ShiftType.DAY, "L1", 1));

        CrewShift moved = editor.moveCrewShift(schedule, crewShift.getId(), request(DAY1, ShiftType.NIGHT, "L2", 2));

        assertThat(schedule.getCrewShifts()).containsExactly(moved);
        assertThat(moved.getLine().getId()).isEqualTo("L2");
        assertThat(moved.getType()).isEqualTo(ShiftType.NIGHT);
        assertThat(moved.getCrew()).isSameAs(crew(2));
        assertThat(schedule.getPositions()).hasSize(1)
                .allSatisfy(position -> assertThat(position.getCrewShift()).isSameAs(moved));
    }

    @Test
    void moveToTheSamePlaceKeepsTheCrewShift() {
        CrewShift crewShift = editor.addCrewShift(schedule, request(DAY1, ShiftType.DAY, "L1", 1));
        schedule.getPositions().get(0).setEmployee(employee(100));

        CrewShift moved = editor.moveCrewShift(schedule, crewShift.getId(), request(DAY1, ShiftType.DAY, "L1", 1));

        assertThat(moved).isSameAs(crewShift);
        assertThat(schedule.getPositions().get(0).getEmployee()).isEqualTo(employee(100));
    }

    @Test
    void pinnedCrewShiftCannotBeMovedOrRemoved() {
        CrewShift crewShift = editor.addCrewShift(schedule, request(DAY1, ShiftType.DAY, "L1", 1));
        editor.setPinned(schedule, crewShift.getId(), true);

        assertThatThrownBy(() -> editor.removeCrewShift(schedule, crewShift.getId()))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("pinned");
        assertThatThrownBy(() -> editor.moveCrewShift(schedule, crewShift.getId(),
                request(DAY1, ShiftType.NIGHT, "L1", 1)))
                .isInstanceOf(IllegalStateException.class);

        editor.setPinned(schedule, crewShift.getId(), false);
        editor.removeCrewShift(schedule, crewShift.getId());
        assertThat(schedule.getCrewShifts()).isEmpty();
        assertThat(schedule.getPositions()).isEmpty();
    }

    @Test
    void replacementChangesOnlyOneCrewShift() {
        CrewShift day = editor.addCrewShift(schedule, request(DAY1, ShiftType.DAY, "L1", 1));
        CrewShift night = editor.addCrewShift(schedule, request(DAY1.plusDays(1), ShiftType.NIGHT, "L1", 1));

        editor.replaceEmployee(schedule, day.getId(), 100, 200);

        assertThat(day.getEmployees()).extracting(Employee::getId).containsExactly(200);
        assertThat(day.getAbsentEmployeeIds()).containsExactly(100);
        assertThat(day.getExtraEmployeeIds()).containsExactly(200);
        // The crew and its other shifts do not change
        assertThat(crew(1).getEmployees()).extracting(Employee::getId).containsExactly(100);
        assertThat(night.getEmployeeIds()).containsExactly(100);
    }

    @Test
    void substituteMustNotWorkAnotherShiftAtTheSameTime() {
        CrewShift crew1Shift = editor.addCrewShift(schedule, request(DAY1, ShiftType.DAY, "L1", 1));
        CrewShift crew2Shift = editor.addCrewShift(schedule, request(DAY1, ShiftType.DAY, "L2", 2));

        assertThatThrownBy(() -> editor.replaceEmployee(schedule, crew1Shift.getId(), 100, 200))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("already works on line Line 2");
        assertThat(crew1Shift.getEmployeeIds()).as("nothing changed").containsExactly(100);

        // Boris is free when he does not work his own crew's shift
        editor.removeEmployeeFromShift(schedule, crew2Shift.getId(), 200);
        editor.replaceEmployee(schedule, crew1Shift.getId(), 100, 200);
        assertThat(crew1Shift.getEmployeeIds()).containsExactly(200);
        assertThat(crew2Shift.getEmployeeIds()).isEmpty();
    }

    @Test
    void employeeCanBeTakenOffAndAddedBack() {
        CrewShift crewShift = editor.addCrewShift(schedule, request(DAY1, ShiftType.DAY, "L1", 1));

        editor.removeEmployeeFromShift(schedule, crewShift.getId(), 100);
        assertThat(crewShift.getEmployeeIds()).isEmpty();
        editor.addEmployeeToShift(schedule, crewShift.getId(), 100);
        assertThat(crewShift.getEmployeeIds()).containsExactly(100);
        assertThat(crewShift.getAbsentEmployees()).isEmpty();

        editor.addEmployeeToShift(schedule, crewShift.getId(), 200);
        assertThat(crewShift.getEmployeeIds()).containsExactly(100, 200);
        editor.removeEmployeeFromShift(schedule, crewShift.getId(), 200);
        assertThat(crewShift.getExtraEmployees()).isEmpty();

        assertThatThrownBy(() -> editor.removeEmployeeFromShift(schedule, crewShift.getId(), 200))
                .isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> editor.addEmployeeToShift(schedule, crewShift.getId(), 100))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("already works");
        assertThatThrownBy(() -> editor.replaceEmployee(schedule, crewShift.getId(), 100, 100))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> editor.addEmployeeToShift(schedule, crewShift.getId(), 999))
                .isInstanceOf(NoSuchElementException.class);
    }

    @Test
    void crewCannotStartAShiftWhileOneOfItsEmployeesSubstitutesElsewhere() {
        CrewShift crewShift = editor.addCrewShift(schedule, request(DAY1, ShiftType.DAY, "L1", 1));
        editor.addEmployeeToShift(schedule, crewShift.getId(), 200);

        assertThatThrownBy(() -> editor.addCrewShift(schedule, request(DAY1, ShiftType.DAY, "L2", 2)))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("as a substitute");
    }

    @Test
    void movingACrewShiftKeepsReplacementsOnlyAtTheSameTime() {
        CrewShift crewShift = editor.addCrewShift(schedule, request(DAY1, ShiftType.DAY, "L1", 1));
        editor.replaceEmployee(schedule, crewShift.getId(), 100, 200);

        CrewShift otherLine = editor.moveCrewShift(schedule, crewShift.getId(), request(DAY1, ShiftType.DAY, "L2", 1));
        assertThat(otherLine.getEmployeeIds()).containsExactly(200);

        CrewShift otherDay = editor.moveCrewShift(schedule, otherLine.getId(),
                request(DAY1.plusDays(1), ShiftType.DAY, "L2", 1));
        assertThat(otherDay.getEmployeeIds()).containsExactly(100);
    }

    @Test
    void movedEmployeeNoLongerNeedsToBeAnExtraOfTheNewCrew() {
        CrewShift crewShift = editor.addCrewShift(schedule, request(DAY1, ShiftType.DAY, "L1", 1));
        editor.addEmployeeToShift(schedule, crewShift.getId(), 200);

        editor.moveEmployee(schedule, 200, 1);

        assertThat(crewShift.getExtraEmployees()).isEmpty();
        assertThat(crewShift.getEmployeeIds()).containsExactly(100, 200);
    }

    @Test
    void movedEmployeeLeavesThePositionsOfTheOldCrew() {
        editor.addCrewShift(schedule, request(DAY1, ShiftType.DAY, "L1", 1));
        Employee anna = employee(100);
        Position position = schedule.getPositions().get(0);
        position.setEmployee(anna);
        position.setPinned(true);

        editor.moveEmployee(schedule, 100, 2);

        assertThat(anna.getCrew()).isSameAs(crew(2));
        assertThat(crew(1).getEmployees()).isEmpty();
        assertThat(crew(2).getEmployees()).extracting(Employee::getId).containsExactlyInAnyOrder(100, 200);
        assertThat(position.getEmployee()).isNull();
        assertThat(position.isPinned()).isFalse();
    }

    private static CrewShiftRequest request(LocalDate date, ShiftType type, String lineId, Integer crewId) {
        return new CrewShiftRequest(date, type, lineId, crewId);
    }

    private Crew crew(int id) {
        return schedule.getCrews().stream().filter(crew -> crew.getId() == id).findFirst().orElseThrow();
    }

    private Employee employee(int id) {
        return schedule.getEmployees().stream().filter(e -> e.getId() == id).findFirst().orElseThrow();
    }

}
