package org.acme.employeescheduling.service;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.acme.employeescheduling.domain.Crew;
import org.acme.employeescheduling.domain.CrewSchedule;
import org.acme.employeescheduling.domain.CrewShift;
import org.acme.employeescheduling.domain.Employee;
import org.acme.employeescheduling.domain.Line;
import org.acme.employeescheduling.domain.Position;
import org.acme.employeescheduling.domain.Role;
import org.acme.employeescheduling.domain.ShiftType;
import org.junit.jupiter.api.Test;

class CrewScheduleFactoryTest {

    private final CrewScheduleFactory factory = new CrewScheduleFactory();

    @Test
    void copyHasTheSameAssignmentsButItsOwnPositions() {
        Crew crew = new Crew(1, "Cr1");
        Role role = new Role(10, "Packer");
        Employee anna = new Employee(100, "Anna", crew, Set.of(role));
        CrewData data = new CrewData(List.of(crew), List.of(role), List.of(anna), Map.of(1, Map.of(role, 2)));
        CrewShift shift = new CrewShift("s1", LocalDate.of(2026, 11, 1), ShiftType.DAY, new Line("L1", "Line 1"), crew);

        CrewSchedule source = factory.create(data, List.of(shift));
        source.getPositions().get(0).setEmployee(anna);
        source.getPositions().get(0).setPinned(true);

        CrewSchedule copy = factory.copyOf(source, data);

        assertThat(copy.getPositions()).hasSize(2);
        Position copied = copy.getPositions().get(0);
        assertThat(copied).isNotSameAs(source.getPositions().get(0));
        assertThat(copied.getEmployee()).isEqualTo(anna);
        assertThat(copied.isPinned()).isTrue();
        assertThat(copy.getPositions().get(1).getEmployee()).isNull();

        // Changing the copy (e.g. by the solver) does not change the source
        copied.setEmployee(null);
        assertThat(source.getPositions().get(0).getEmployee()).isEqualTo(anna);
    }
}
