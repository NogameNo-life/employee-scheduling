package org.acme.employeescheduling.service;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.LocalDate;
import java.time.YearMonth;
import java.util.List;

import org.acme.employeescheduling.domain.Crew;
import org.acme.employeescheduling.domain.CrewSchedule;
import org.acme.employeescheduling.domain.Employee;
import org.acme.employeescheduling.domain.Line;
import org.acme.employeescheduling.domain.ShiftTime;
import org.acme.employeescheduling.domain.ShiftType;
import org.acme.employeescheduling.service.CrewScheduleEditor.CrewShiftRequest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class CrewScheduleFactoryTest {

    private static final YearMonth MONTH = YearMonth.of(2026, 11);

    private static final List<ShiftTime> SHIFTS = List.of(ShiftTime.defaultFor(ShiftType.DAY),
            ShiftTime.defaultFor(ShiftType.NIGHT));

    private final CrewScheduleFactory factory = new CrewScheduleFactory();
    private final Crew crew = new Crew(1, "Cr1");
    private final Employee anna = new Employee(100, "Anna", crew);
    private final Line line = new Line("L1", null, "Line 1");
    private final CrewData data = new CrewData(List.of(crew), List.of(anna));

    @BeforeEach
    void fillCrew() {
        crew.setStaffCount(5);
        crew.setLineId("L1");
        crew.getEmployees().add(anna);
    }

    @Test
    void emptyVersionHasItsOwnCrewsAndEmployees() {
        CrewSchedule schedule = factory.createEmpty(MONTH, List.of(line), SHIFTS, data);

        assertThat(schedule.getMonth()).isEqualTo(MONTH);
        assertThat(schedule.getShifts()).isEqualTo(SHIFTS);
        assertThat(schedule.getCrewShifts()).isEmpty();
        Crew copiedCrew = schedule.getCrews().get(0);
        assertThat(copiedCrew).isEqualTo(crew).isNotSameAs(crew);
        assertThat(copiedCrew.getStaffCount()).isEqualTo(5);
        assertThat(copiedCrew.getLineId()).isEqualTo("L1");
        assertThat(copiedCrew.getEmployees()).hasSize(1);
        Employee copiedAnna = copiedCrew.getEmployees().get(0);
        assertThat(copiedAnna).isNotSameAs(anna).isSameAs(schedule.getEmployees().get(0));
        assertThat(copiedAnna.getCrew()).isSameAs(copiedCrew);

        // Changing the version does not change the master data
        copiedCrew.setStaffCount(7);
        copiedAnna.setCrew(null);
        assertThat(crew.getStaffCount()).isEqualTo(5);
        assertThat(anna.getCrew()).isSameAs(crew);
    }

    @Test
    void copyHasTheSameCrewShiftsButItsOwnObjects() {
        CrewSchedule source = factory.createEmpty(MONTH, List.of(line), SHIFTS, data);
        new CrewScheduleEditor().addCrewShift(source,
                new CrewShiftRequest(LocalDate.of(2026, 11, 1), ShiftType.DAY, "L1", 1));
        source.getCrewShifts().get(0).setPinned(true);
        source.getCrewShifts().get(0).getAbsentEmployees().add(source.getEmployees().get(0));

        CrewSchedule copy = factory.copyOf(source);

        assertThat(copy.getShifts()).isEqualTo(SHIFTS);
        assertThat(copy.getCrewShifts()).hasSize(1);
        assertThat(copy.getCrewShifts().get(0).getTime()).isEqualTo(SHIFTS.get(0));
        assertThat(copy.getCrewShifts().get(0)).isNotSameAs(source.getCrewShifts().get(0));
        assertThat(copy.getCrewShifts().get(0).isPinned()).isTrue();
        assertThat(copy.getCrewShifts().get(0).getCrew()).isSameAs(copy.getCrews().get(0));
        assertThat(copy.getCrewShifts().get(0).getStaffCount()).isEqualTo(5);
        assertThat(copy.getCrewShifts().get(0).getAbsentEmployees()).singleElement()
                .isSameAs(copy.getEmployees().get(0));

        // Changing the copy (e.g. in the editor) does not change the source
        copy.getCrewShifts().get(0).getAbsentEmployees().clear();
        copy.getCrewShifts().clear();
        assertThat(source.getCrewShifts()).hasSize(1);
        assertThat(source.getCrewShifts().get(0).getAbsentEmployees()).hasSize(1);
    }
}
