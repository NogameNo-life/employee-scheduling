package org.acme.employeescheduling.service;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.LocalDate;
import java.time.YearMonth;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.acme.employeescheduling.domain.Crew;
import org.acme.employeescheduling.domain.CrewSchedule;
import org.acme.employeescheduling.domain.Employee;
import org.acme.employeescheduling.domain.Line;
import org.acme.employeescheduling.domain.Position;
import org.acme.employeescheduling.domain.Role;
import org.acme.employeescheduling.domain.ShiftType;
import org.acme.employeescheduling.service.CrewScheduleEditor.CrewShiftRequest;
import org.junit.jupiter.api.Test;

class CrewScheduleFactoryTest {

    private static final YearMonth MONTH = YearMonth.of(2026, 11);

    private final CrewScheduleFactory factory = new CrewScheduleFactory();
    private final Crew crew = new Crew(1, "Cr1");
    private final Role role = new Role(10, "Packer");
    private final Employee anna = new Employee(100, "Anna", crew, new HashSet<>(Set.of(role)));
    private final Line line = new Line("L1", "Line 1");
    private final CrewData data = new CrewData(List.of(crew), List.of(role), List.of(anna), Map.of(1, Map.of(role, 2)));

    @Test
    void emptyVersionHasItsOwnCrewsAndEmployees() {
        crew.getEmployees().add(anna);

        CrewSchedule schedule = factory.createEmpty(MONTH, List.of(line), data);

        assertThat(schedule.getMonth()).isEqualTo(MONTH);
        assertThat(schedule.getCrewShifts()).isEmpty();
        assertThat(schedule.getPositions()).isEmpty();
        Crew copiedCrew = schedule.getCrews().get(0);
        assertThat(copiedCrew).isEqualTo(crew).isNotSameAs(crew);
        assertThat(copiedCrew.getEmployees()).hasSize(1);
        Employee copiedAnna = copiedCrew.getEmployees().get(0);
        assertThat(copiedAnna).isNotSameAs(anna).isSameAs(schedule.getEmployees().get(0));
        assertThat(copiedAnna.getCrew()).isSameAs(copiedCrew);
        // The composition from the DB is valid from the first day of the month
        assertThat(schedule.getComposition().get(1).getVersions()).containsOnlyKeys(MONTH.atDay(1));
        assertThat(schedule.getComposition().get(1).rolesNeededOn(MONTH.atEndOfMonth())).containsEntry(role, 2);

        // Changing the version does not change the master data
        schedule.getComposition().get(1).setRoleCount(MONTH.atDay(1), role, 5);
        copiedAnna.setCrew(null);
        assertThat(data.composition().get(1)).containsEntry(role, 2);
        assertThat(anna.getCrew()).isSameAs(crew);
    }

    @Test
    void copyHasTheSameCrewShiftsAndAssignmentsButItsOwnObjects() {
        CrewSchedule source = factory.createEmpty(MONTH, List.of(line), data);
        new CrewScheduleEditor(factory).addCrewShift(source,
                new CrewShiftRequest(LocalDate.of(2026, 11, 1), ShiftType.DAY, "L1", 1));
        source.getCrewShifts().get(0).setPinned(true);
        Position sourcePosition = source.getPositions().get(0);
        sourcePosition.setEmployee(source.getEmployees().get(0));
        sourcePosition.setPinned(true);

        CrewSchedule copy = factory.copyOf(source);

        assertThat(copy.getCrewShifts()).hasSize(1);
        assertThat(copy.getCrewShifts().get(0)).isNotSameAs(source.getCrewShifts().get(0));
        assertThat(copy.getCrewShifts().get(0).isPinned()).isTrue();
        assertThat(copy.getCrewShifts().get(0).getCrew()).isSameAs(copy.getCrews().get(0));
        assertThat(copy.getPositions()).hasSize(2);
        Position copied = copy.getPositions().get(0);
        assertThat(copied).isNotSameAs(sourcePosition);
        assertThat(copied.getCrewShift()).isSameAs(copy.getCrewShifts().get(0));
        assertThat(copied.getEmployee()).isSameAs(copy.getEmployees().get(0));
        assertThat(copied.isPinned()).isTrue();
        assertThat(copy.getPositions().get(1).getEmployee()).isNull();

        // Changing the copy (e.g. in the editor) does not change the source
        copied.setEmployee(null);
        copy.getCrewShifts().clear();
        assertThat(sourcePosition.getEmployee()).isNotNull();
        assertThat(source.getCrewShifts()).hasSize(1);
    }
}
