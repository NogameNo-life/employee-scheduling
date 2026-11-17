package org.acme.employeescheduling.service;

import static java.util.stream.Collectors.toMap;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.springframework.stereotype.Service;

import org.acme.employeescheduling.domain.Crew;
import org.acme.employeescheduling.domain.Employee;
import org.acme.employeescheduling.entity.plr.PlrBr;
import org.acme.employeescheduling.entity.plr.PlrTbbr;
import org.acme.employeescheduling.entity.tabu.BdCard;
import org.acme.employeescheduling.repository.plr.CrewRepository;
import org.acme.employeescheduling.repository.plr.EmployeeCrewRepository;
import org.acme.employeescheduling.repository.tabu.EmployeeRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Loads crews and employees for one department.
 *
 * <p>
 * Exactly 3 queries, no matter how many employees there are (no N+1):
 * every table is read once and joined in memory with maps.
 * Subqueries are used instead of "IN (:ids)" because SQL Server allows
 * at most 2100 parameters per query.
 * Employees and their crews come from PLR_TBBR. Names (BD_CARD) are in another database (tabu2),
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

        public CrewData load(String department) {
                // 1) Crews, with the number of people per shift (PLR_BR.NTB)
                List<PlrBr> crewRows = crewRepository.findByDepartment(department);
                Map<Integer, Crew> crewsById = crewRows.stream()
                                .filter(r -> r.crewId != null)
                                .collect(toMap(r -> r.crewId, CrewDataLoader::toCrew, (a, b) -> a));

                // 2) Employee -> crew, 3) names
                List<Employee> employees = loadEmployees(department, crewsById);

                return new CrewData(List.copyOf(crewsById.values()), employees);
        }

        /** The crew of a PLR_BR row: the name is trimmed (CHAR column), no NTB means 0 people. */
        static Crew toCrew(PlrBr row) {
                Crew crew = new Crew(row.crewId, row.crewName == null ? String.valueOf(row.crewId) : row.crewName.trim());
                crew.setStaffCount(row.staffCount == null ? 0 : Math.max(row.staffCount, 0));
                return crew;
        }

        /**
         * Employees of the department's crews from PLR_TBBR, and each crew's employee list.
         * Names come from BD_CARD (tabu2). If tabu2 cannot be read, or an employee is not in BD_CARD,
         * the personnel number is used as the name, so the crews are still complete.
         */
        private List<Employee> loadEmployees(String department, Map<Integer, Crew> crewsById) {
                // One crew per employee; if PLR_TBBR has more, the first one is used
                Map<Integer, Crew> crewByEmployeeId = new LinkedHashMap<>();
                int withMoreCrews = 0;
                for (PlrTbbr row : employeeCrewRepository.findByDepartment(department)) {
                        Crew crew = crewsById.get(row.crewId);
                        if (row.employeeId != null && crew != null
                                        && crewByEmployeeId.putIfAbsent(row.employeeId, crew) != null) {
                                withMoreCrews++;
                        }
                }
                if (withMoreCrews > 0) {
                        LOGGER.warn("Department {}: {} rows in PLR_TBBR give an employee a second crew;"
                                        + " only the first crew is used.", department, withMoreCrews);
                }

                Map<Integer, String> namesById = loadNames(department);
                List<Employee> employees = new ArrayList<>();
                int withoutName = 0;
                for (Map.Entry<Integer, Crew> entry : crewByEmployeeId.entrySet()) {
                        Integer employeeId = entry.getKey();
                        String name = namesById.get(employeeId);
                        if (name == null) {
                                withoutName++;
                                name = String.valueOf(employeeId);
                        }
                        Crew crew = entry.getValue();
                        Employee employee = new Employee(employeeId, name, crew);
                        crew.getEmployees().add(employee);
                        employees.add(employee);
                }
                if (withoutName > 0 && !namesById.isEmpty()) {
                        LOGGER.info("Department {}: {} employees from PLR_TBBR are not in BD_CARD;"
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
