package org.acme.employeescheduling.service;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.LocalDate;
import java.time.YearMonth;
import java.util.HashSet;
import java.util.List;
import java.util.Map;

import org.acme.employeescheduling.domain.Crew;
import org.acme.employeescheduling.domain.CrewComposition;
import org.acme.employeescheduling.domain.CrewSchedule;
import org.acme.employeescheduling.domain.CrewShift;
import org.acme.employeescheduling.domain.Employee;
import org.acme.employeescheduling.domain.Line;
import org.acme.employeescheduling.domain.Role;
import org.acme.employeescheduling.domain.ShiftType;
import org.acme.employeescheduling.service.CrewRosterService.CompositionVersion;
import org.acme.employeescheduling.service.CrewRosterService.CrewDetails;
import org.acme.employeescheduling.service.CrewRosterService.RoleCount;
import org.junit.jupiter.api.Test;

class CrewRosterServiceTest {

    private static final List<Line> LINES = List.of(new Line("L1", "Line 1"), new Line("L2", "Line 2"),
            new Line("L3", "Line 3"));

    @Test
    void crewDetailsHaveLinesRolesAndEmployeesSortedByName() {
        Crew crew1 = new Crew(1, "Cr1");
        Crew crew2 = new Crew(2, "Cr2");
        Line line = LINES.get(0);
        LocalDate date = LocalDate.of(2026, 11, 1);
        List<CrewShift> crewShifts = List.of(
                new CrewShift("s1", date, ShiftType.DAY, line, crew1),
                new CrewShift("s2", date, ShiftType.NIGHT, line, crew1));
        List<Employee> employees = List.of(
                new Employee(10, "Petrov", crew1, new HashSet<>()),
                new Employee(11, "Ivanov", crew1, new HashSet<>()),
                new Employee(20, "Sidorov", crew2, new HashSet<>()));
        Role packer = new Role(1, "Packer");
        Role operator = new Role(2, "Operator");
        CrewComposition composition = new CrewComposition(date, Map.of(packer, 3, operator, 1));
        composition.setRoleCount(date.plusDays(14), packer, 4);
        CrewSchedule schedule = new CrewSchedule(YearMonth.of(2026, 11), LINES, List.of(crew2, crew1),
                List.of(packer, operator), employees, Map.of(1, composition), crewShifts, List.of());
        CrewRosterService service = new CrewRosterService(null, null);

        List<CrewDetails> details = service.toCrewDetails(schedule);

        assertThat(details).extracting(CrewDetails::crew).containsExactly(crew1, crew2);
        assertThat(details.get(0).lines()).containsExactly(line);
        assertThat(details.get(0).composition()).containsExactly(
                new CompositionVersion(date, 4, List.of(new RoleCount(operator, 1), new RoleCount(packer, 3))),
                new CompositionVersion(date.plusDays(14), 5,
                        List.of(new RoleCount(operator, 1), new RoleCount(packer, 4))));
        assertThat(details.get(1).composition()).isEmpty();
        assertThat(details.get(0).employees()).extracting(Employee::getFullName).containsExactly("Ivanov", "Petrov");
        assertThat(details.get(1).lines()).isEmpty();
        assertThat(details.get(1).employees()).extracting(Employee::getFullName).containsExactly("Sidorov");
    }
}
