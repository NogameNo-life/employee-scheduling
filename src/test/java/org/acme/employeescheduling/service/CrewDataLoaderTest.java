package org.acme.employeescheduling.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.YearMonth;
import java.util.List;
import java.util.Map;

import org.acme.employeescheduling.domain.Crew;
import org.acme.employeescheduling.domain.Employee;
import org.acme.employeescheduling.entity.plr.PlrBr;
import org.acme.employeescheduling.entity.plr.PlrTb;
import org.acme.employeescheduling.repository.plr.EmployeeCrewRepository;
import org.acme.employeescheduling.entity.tabu.BdCard;
import org.junit.jupiter.api.Test;

class CrewDataLoaderTest {

    private static final YearMonth NOVEMBER = YearMonth.of(2026, 11);

    private final EmployeeCrewRepository employeeCrewRepository = mock(EmployeeCrewRepository.class);
    private final CrewDataLoader loader = new CrewDataLoader(null, employeeCrewRepository, null);

    @Test
    void ggmmIsYearAndMonth() {
        assertThat(CrewDataLoader.toGgmm(NOVEMBER)).isEqualTo(202611);
        assertThat(CrewDataLoader.toGgmm(YearMonth.of(2027, 1))).isEqualTo(202701);
    }

    @Test
    void rowsOfTheMonth() {
        List<PlrTb> rows = List.of(tb(1, 10, 202611, 1));
        when(employeeCrewRepository.findLatestMonth("D", 202611)).thenReturn(202611);
        when(employeeCrewRepository.findByDepartmentAndMonth("D", 202611)).thenReturn(rows);

        assertThat(loader.findCrewRows("D", NOVEMBER)).isSameAs(rows);
        verify(employeeCrewRepository, never()).findByDepartmentWithoutMonth("D");
    }

    @Test
    void rowsOfTheLatestEarlierMonthWhenTheMonthHasNone() {
        List<PlrTb> rows = List.of(tb(1, 10, 202609, 1));
        when(employeeCrewRepository.findLatestMonth("D", 202611)).thenReturn(202609);
        when(employeeCrewRepository.findByDepartmentAndMonth("D", 202609)).thenReturn(rows);

        assertThat(loader.findCrewRows("D", NOVEMBER)).isSameAs(rows);
    }

    @Test
    void rowsWithoutAMonthWhenNoMonthHasRows() {
        List<PlrTb> rows = List.of(tb(1, 10, null, null));
        when(employeeCrewRepository.findLatestMonth("D", 202611)).thenReturn(null);
        when(employeeCrewRepository.findByDepartmentWithoutMonth("D")).thenReturn(rows);

        assertThat(loader.findCrewRows("D", NOVEMBER)).isSameAs(rows);
    }

    @Test
    void employeesOfACrewAreSortedByPositionThenName() {
        Crew crew = new Crew(1, "Cr1");
        List<PlrTb> rows = List.of(
                tb(1, 10, null, null), tb(1, 11, null, 2), tb(1, 12, null, 1), tb(1, 13, null, null),
                tb(1, 10, null, 5), // second row of employee 10: ignored
                tb(9, 14, null, 1)); // unknown crew: ignored
        Map<Integer, String> names = Map.of(10, "Petrov", 11, "Sidorov", 12, "Zaitsev", 13, "Ivanov");

        List<Employee> employees = CrewDataLoader.toEmployees("D", rows, Map.of(1, crew), names);

        assertThat(employees).extracting(Employee::getId).containsExactly(10, 11, 12, 13);
        assertThat(crew.getEmployees()).extracting(Employee::getFullName)
                .containsExactly("Zaitsev", "Sidorov", "Ivanov", "Petrov");
        assertThat(crew.getEmployees()).extracting(Employee::getPosition).containsExactly(1, 2, null, null);
    }

    @Test
    void personnelNumberIsTheNameWhenTabuHasNone() {
        Crew crew = new Crew(1, "Cr1");

        List<Employee> employees = CrewDataLoader.toEmployees("D", List.of(tb(1, 10, null, null)), Map.of(1, crew),
                Map.of());

        assertThat(employees).singleElement().extracting(Employee::getFullName).isEqualTo("10");
    }

    @Test
    void fullNameIsOneTrimmedString() {
        assertThat(CrewDataLoader.fullName(row(1, "Иванов   Иван Иванович                    ")))
                .isEqualTo("Иванов Иван Иванович");
    }

    @Test
    void personnelNumberWhenThereIsNoName() {
        assertThat(CrewDataLoader.fullName(row(1234, "   "))).isEqualTo("1234");
        assertThat(CrewDataLoader.fullName(row(1234, null))).isEqualTo("1234");
    }

    @Test
    void crewHasATrimmedNameAndTheStaffCountOfNtb() {
        PlrBr row = new PlrBr();
        row.crewId = 5;
        row.crewName = "Line2/1            ";
        row.staffCount = 5;
        row.lineId = "БерЦГС2   ";

        Crew crew = CrewDataLoader.toCrew(row);

        assertThat(crew.getId()).isEqualTo(5);
        assertThat(crew.getName()).isEqualTo("Line2/1");
        assertThat(crew.getStaffCount()).isEqualTo(5);
        assertThat(crew.getLineId()).isEqualTo("БерЦГС2");
    }

    @Test
    void crewWithoutNtbNeedsNoPeople() {
        PlrBr row = new PlrBr();
        row.crewId = 5;

        Crew crew = CrewDataLoader.toCrew(row);

        assertThat(crew.getName()).isEqualTo("5");
        assertThat(crew.getStaffCount()).isZero();
        assertThat(crew.getLineId()).isNull();
    }

    private static BdCard row(int employeeId, String fullName) {
        BdCard row = new BdCard();
        row.employeeId = employeeId;
        row.fullName = fullName;
        return row;
    }

    private static PlrTb tb(int crewId, int employeeId, Integer month, Integer position) {
        PlrTb row = new PlrTb();
        row.crewId = crewId;
        row.employeeId = employeeId;
        row.month = month;
        row.position = position;
        row.deleted = 0;
        return row;
    }
}
