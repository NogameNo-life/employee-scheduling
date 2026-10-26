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
    private final Role operator = new Role(11, "Operator");
    private CrewSchedule schedule;

    @BeforeEach
    void createSchedule() {
        Crew crew1 = new Crew(1, "Cr1");
        Crew crew2 = new Crew(2, "Cr2");
        CrewData data = new CrewData(List.of(crew1, crew2), List.of(packer, operator),
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
    void roleCountChangesTheStaffCountAndThePositionsOfTheCrewShifts() {
        editor.addCrewShift(schedule, request(DAY1, ShiftType.DAY, "L1", 1));
        editor.addCrewShift(schedule, request(DAY1, ShiftType.NIGHT, "L1", 2));
        Position kept = schedule.getPositions().get(0);
        kept.setEmployee(employee(100));

        editor.setRoleCount(schedule, 1, operator.getId(), 1, null);

        assertThat(crew(1).getStaffCount()).isEqualTo(3);
        assertThat(positionsOfCrew(1)).hasSize(3).contains(kept);
        assertThat(kept.getEmployee()).isEqualTo(employee(100));
        assertThat(positionsOfCrew(2)).hasSize(1);

        editor.setRoleCount(schedule, 1, packer.getId(), 0, null);

        assertThat(crew(1).getStaffCount()).isEqualTo(1);
        assertThat(positionsOfCrew(1)).extracting(Position::getRole).containsExactly(operator);
        assertThatThrownBy(() -> editor.setRoleCount(schedule, 1, packer.getId(), -1, null))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> editor.setRoleCount(schedule, 1, 99, 1, null))
                .isInstanceOf(NoSuchElementException.class);
    }

    @Test
    void compositionFromADateChangesOnlyTheCrewShiftsFromThatDate() {
        LocalDate day15 = DAY1.plusDays(14);
        editor.addCrewShift(schedule, request(DAY1, ShiftType.DAY, "L1", 1));
        editor.addCrewShift(schedule, request(day15, ShiftType.DAY, "L1", 1));
        Position early = positionsOn(DAY1).get(0);
        early.setEmployee(employee(100));

        editor.setRoleCount(schedule, 1, packer.getId(), 4, day15);

        assertThat(positionsOn(DAY1)).hasSize(2).contains(early);
        assertThat(early.getEmployee()).isEqualTo(employee(100));
        assertThat(positionsOn(day15)).hasSize(4);
        assertThat(crew(1).getStaffCount()).as("first day of the month").isEqualTo(2);
        // A crew shift added later gets the composition valid on its date
        editor.addCrewShift(schedule, request(day15.plusDays(1), ShiftType.NIGHT, "L1", 1));
        assertThat(positionsOn(day15.plusDays(1))).hasSize(4);

        editor.removeCompositionVersion(schedule, 1, day15);

        assertThat(positionsOn(day15)).hasSize(2);
        assertThat(positionsOn(day15.plusDays(1))).hasSize(2);
        assertThat(positionsOn(DAY1)).contains(early);
        assertThatThrownBy(() -> editor.removeCompositionVersion(schedule, 1, DAY1))
                .isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> editor.removeCompositionVersion(schedule, 1, day15))
                .isInstanceOf(NoSuchElementException.class);
        assertThatThrownBy(() -> editor.setRoleCount(schedule, 1, packer.getId(), 1, DAY1.minusDays(1)))
                .isInstanceOf(IllegalArgumentException.class);
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

    private List<Position> positionsOn(LocalDate date) {
        return schedule.getPositions().stream().filter(p -> p.getDate().equals(date)).toList();
    }

    private List<Position> positionsOfCrew(int crewId) {
        return schedule.getPositions().stream().filter(p -> p.getCrew().getId() == crewId).toList();
    }
}
