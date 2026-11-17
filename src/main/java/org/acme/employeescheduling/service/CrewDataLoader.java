package org.acme.employeescheduling.service;

import static java.util.stream.Collectors.toMap;

import java.time.YearMonth;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.springframework.stereotype.Service;

import org.acme.employeescheduling.domain.Crew;
import org.acme.employeescheduling.domain.Employee;
import org.acme.employeescheduling.entity.plr.PlrBr;
import org.acme.employeescheduling.entity.plr.PlrTb;
import org.acme.employeescheduling.entity.tabu.BdCard;
import org.acme.employeescheduling.repository.plr.CrewRepository;
import org.acme.employeescheduling.repository.plr.EmployeeCrewRepository;
import org.acme.employeescheduling.repository.tabu.EmployeeRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Loads crews and employees of one department for one month.
 *
 * <p>
 * Every table is read once and joined in memory with maps (no N+1), whatever the number of employees.
 * Subqueries are used instead of "IN (:ids)" because SQL Server allows at most 2100 parameters per query.
 * Employees and their crews come from PLR_TB. Names (BD_CARD) are in another database (tabu2),
 * so they are joined with the other tables in memory only.
 */
@Service
public class CrewDataLoader {

    private static final Logger LOGGER = LoggerFactory.getLogger(CrewDataLoader.class);

    private final CrewRepository crewRepository;
    private final EmployeeCrewRepository employeeCrewRepository;
    private final EmployeeRepository employeeRepository;

    public CrewDataLoader(CrewRepository crewRepository, EmployeeCrewRepository employeeCrewRepository,
            EmployeeRepository employeeRepository) {
        this.crewRepository = crewRepository;
        this.employeeCrewRepository = employeeCrewRepository;
        this.employeeRepository = employeeRepository;
    }

    public CrewData load(String department, YearMonth month) {
        // 1) Crews, with the number of people per shift (PLR_BR.NTB) and their line (PLR_BR.KC)
        List<PlrBr> crewRows = crewRepository.findByDepartment(department);
        Map<Integer, Crew> crewsById = crewRows.stream()
                .filter(r -> r.crewId != null)
                .collect(toMap(r -> r.crewId, CrewDataLoader::toCrew, (a, b) -> a, LinkedHashMap::new));

        // 2) Employee -> crew of the month, 3) names
        List<Employee> employees = toEmployees(department, findCrewRows(department, month), crewsById,
                loadNames(department));

        return new CrewData(List.copyOf(crewsById.values()), employees);
    }

    /** The crew of a PLR_BR row: name and line (KC) are trimmed (CHAR columns), no NTB means 0 people. */
    static Crew toCrew(PlrBr row) {
        Crew crew = new Crew(row.crewId, row.crewName == null ? String.valueOf(row.crewId) : row.crewName.trim());
        crew.setStaffCount(row.staffCount == null ? 0 : Math.max(row.staffCount, 0));
        crew.setLineId(row.lineId == null || row.lineId.isBlank() ? null : row.lineId.trim());
        return crew;
    }

    /** GGMM of a month: yyyyMM as a number, e.g. 202611. */
    static int toGgmm(YearMonth month) {
        return month.getYear() * 100 + month.getMonthValue();
    }

    /**
     * PLR_TB rows of the month. If the month has none: the rows of the latest earlier month; if no month has
     * rows: the rows without a month (GGMM NULL, the column is not filled yet). Both cases are logged.
     */
    List<PlrTb> findCrewRows(String department, YearMonth month) {
        int ggmm = toGgmm(month);
        Integer latest = employeeCrewRepository.findLatestMonth(department, ggmm);
        if (latest != null) {
            if (latest != ggmm) {
                LOGGER.info("Department {}: PLR_TB has no employees for {}, the ones of {} are used.",
                        department, ggmm, latest);
            }
            return employeeCrewRepository.findByDepartmentAndMonth(department, latest);
        }
        LOGGER.info("Department {}: PLR_TB has no employees for {} or earlier, the rows without a month"
                + " (GGMM NULL) are used.", department, ggmm);
        return employeeCrewRepository.findByDepartmentWithoutMonth(department);
    }

    /**
     * Employees of the PLR_TB rows with their crew and position, and each crew's employee list, sorted by position
     * and name. If an employee is in more than one crew, the first row is used. If an employee has no name
     * (tabu2 cannot be read, or not in BD_CARD), the personnel number is the name, so the crews are still complete.
     */
    static List<Employee> toEmployees(String department, List<PlrTb> rows, Map<Integer, Crew> crewsById,
            Map<Integer, String> namesById) {
        Map<Integer, PlrTb> rowByEmployeeId = new LinkedHashMap<>();
        int withMoreCrews = 0;
        for (PlrTb row : rows) {
            if (row.employeeId != null && crewsById.containsKey(row.crewId)
                    && rowByEmployeeId.putIfAbsent(row.employeeId, row) != null) {
                withMoreCrews++;
            }
        }
        if (withMoreCrews > 0) {
            LOGGER.warn("Department {}: {} rows in PLR_TB give an employee a second crew;"
                    + " only the first crew is used.", department, withMoreCrews);
        }

        List<Employee> employees = new ArrayList<>();
        int withoutName = 0;
        for (PlrTb row : rowByEmployeeId.values()) {
            String name = namesById.get(row.employeeId);
            if (name == null) {
                withoutName++;
                name = String.valueOf(row.employeeId);
            }
            Crew crew = crewsById.get(row.crewId);
            Employee employee = new Employee(row.employeeId, name, crew);
            employee.setPosition(row.position);
            crew.getEmployees().add(employee);
            employees.add(employee);
        }
        crewsById.values().forEach(crew -> crew.getEmployees().sort(Employee.BY_POSITION_AND_NAME));
        if (withoutName > 0 && !namesById.isEmpty()) {
            LOGGER.info("Department {}: {} employees from PLR_TB are not in BD_CARD;"
                    + " personnel numbers are used as names.", department, withoutName);
        }
        return employees;
    }

    /** Personnel number -> full name from BD_CARD (tabu2); empty if tabu2 cannot be read. */
    private Map<Integer, String> loadNames(String department) {
        try {
            return employeeRepository.findByDepartment(department).stream()
                    .filter(row -> row.employeeId != null)
                    .collect(toMap(row -> row.employeeId, CrewDataLoader::fullName, (a, b) -> a));
        } catch (RuntimeException e) {
            LOGGER.warn("Could not read employees (BD_CARD, tabu2) of department {}: names are unknown,"
                    + " personnel numbers are used instead.", department, e);
            return Map.of();
        }
    }

    /** FIO is one CHAR(70) column ("Last First Middle"): trimmed, with single spaces. */
    static String fullName(BdCard row) {
        if (row.fullName == null || row.fullName.isBlank()) {
            return String.valueOf(row.employeeId);
        }
        return row.fullName.trim().replaceAll("\\s+", " ");
    }
}
