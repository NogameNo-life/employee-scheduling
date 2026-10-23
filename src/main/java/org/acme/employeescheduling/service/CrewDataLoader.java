package org.acme.employeescheduling.service;

import static java.util.stream.Collectors.groupingBy;
import static java.util.stream.Collectors.toMap;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.springframework.stereotype.Service;

import org.acme.employeescheduling.domain.Crew;
import org.acme.employeescheduling.domain.Employee;
import org.acme.employeescheduling.domain.Role;
import org.acme.employeescheduling.entity.plr.PlrBr;
import org.acme.employeescheduling.entity.plr.PlrRlbr;
import org.acme.employeescheduling.entity.plr.PlrRltb;
import org.acme.employeescheduling.entity.plr.PlrRole;
import org.acme.employeescheduling.entity.plr.PlrTbbr;
import org.acme.employeescheduling.entity.tabu.BdCard;
import org.acme.employeescheduling.repository.plr.CrewCompositionRepository;
import org.acme.employeescheduling.repository.plr.CrewRepository;
import org.acme.employeescheduling.repository.plr.EmployeeCrewRepository;
import org.acme.employeescheduling.repository.tabu.EmployeeRepository;
import org.acme.employeescheduling.repository.plr.EmployeeRoleRepository;
import org.acme.employeescheduling.repository.plr.RoleRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Loads crews, roles, employees and crew composition for one department.
 *
 * <p>
 * Exactly 6 queries, no matter how many employees there are (no N+1):
 * every table is read once and joined in memory with maps.
 * Subqueries are used instead of "IN (:ids)" because SQL Server allows
 * at most 2100 parameters per query.
 * Employees and their crews come from PLR_TBBR. Names (BD_CARD) are in another database (tabu2),
 * so they are joined with the other tables in memory only.
 * Roles are loaded, but not used for employees yet.
 */
@Service
public class CrewDataLoader {

        private static final Logger LOGGER = LoggerFactory.getLogger(CrewDataLoader.class);

        private final CrewRepository crewRepository;
        private final RoleRepository roleRepository;
        private final EmployeeRoleRepository employeeRoleRepository;
        private final EmployeeCrewRepository employeeCrewRepository;
        private final EmployeeRepository employeeRepository;
        private final CrewCompositionRepository crewCompositionRepository;

        public CrewDataLoader(CrewRepository crewRepository, RoleRepository roleRepository,
                        EmployeeRoleRepository employeeRoleRepository, EmployeeCrewRepository employeeCrewRepository,
                        EmployeeRepository employeeRepository, CrewCompositionRepository crewCompositionRepository) {
                this.crewRepository = crewRepository;
                this.roleRepository = roleRepository;
                this.employeeRoleRepository = employeeRoleRepository;
                this.employeeCrewRepository = employeeCrewRepository;
                this.employeeRepository = employeeRepository;
                this.crewCompositionRepository = crewCompositionRepository;
        }

        public CrewData load(String department) {
                // 1) Crews
                List<PlrBr> crewRows = crewRepository.findByDepartment(department);
                Map<Integer, Crew> crewsById = crewRows.stream()
                                .filter(r -> r.crewId != null)
                                .collect(toMap(r -> r.crewId, r -> new Crew(r.crewId, r.crewName), (a, b) -> a));

                // 2) Crew composition: number of people per crew, and crew -> role -> number of people per shift
                List<PlrRlbr> compositionRows = crewCompositionRepository.findByDepartment(department);
                staffCountByCrew(compositionRows).forEach((crewId, count) -> {
                        Crew crew = crewsById.get(crewId);
                        if (crew != null) {
                                crew.setStaffCount(count);
                        }
                });

                // 3) Roles
                List<PlrRole> roleRows = roleRepository.findByDepartment(department);
                Map<Integer, Role> rolesById = roleRows.stream()
                                .filter(r -> r.roleId != null)
                                .collect(toMap(r -> r.roleId, r -> new Role(r.roleId, r.roleName), (a, b) -> a));

                Map<Integer, Map<Role, Integer>> composition = new HashMap<>();
                compositionRows.stream()
                                .filter(r -> rolesById.containsKey(r.roleId) && r.numberOfStaff != null
                                                && r.numberOfStaff > 0)
                                .collect(groupingBy(r -> r.crewId))
                                .forEach((crewId, rows) -> composition.put(crewId, rows.stream()
                                                .collect(toMap(r -> rolesById.get(r.roleId), r -> r.numberOfStaff,
                                                                Integer::sum))));

                // 4) Employee -> roles
                List<PlrRltb> employeeRoleRows = employeeRoleRepository.findByDepartment(department);
                Map<Integer, Set<Role>> rolesByEmployeeId = new HashMap<>();
                for (PlrRltb row : employeeRoleRows) {
                        Role role = rolesById.get(row.roleId);
                        if (role != null) {
                                rolesByEmployeeId.computeIfAbsent(row.employeeId, id -> new HashSet<>()).add(role);
                        }
                }

                // 5) Employee -> crew, 6) names
                List<Employee> employees = loadEmployees(department, crewsById, rolesByEmployeeId);

                return new CrewData(
                                List.copyOf(crewsById.values()),
                                List.copyOf(rolesById.values()),
                                employees,
                                composition);
        }

        /**
         * Number of people per shift for every crew: PLR_RLBR has one row per crew and role, so the NPERS of all
         * rows of a crew are added up. Example: (BRID 1, RLID 1, NPERS 1) and (BRID 1, RLID 2, NPERS 3) give 4.
         */
        static Map<Integer, Integer> staffCountByCrew(List<PlrRlbr> compositionRows) {
                Map<Integer, Integer> staffCount = new HashMap<>();
                for (PlrRlbr row : compositionRows) {
                        if (row.crewId != null && row.numberOfStaff != null && row.numberOfStaff > 0) {
                                staffCount.merge(row.crewId, row.numberOfStaff, Integer::sum);
                        }
                }
                return staffCount;
        }

        /**
         * Employees of the department's crews from PLR_TBBR, and each crew's employee list.
         * Names come from BD_CARD (tabu2). If tabu2 cannot be read, or an employee is not in BD_CARD,
         * the personnel number is used as the name, so the crews are still complete.
         */
        private List<Employee> loadEmployees(String department, Map<Integer, Crew> crewsById,
                        Map<Integer, Set<Role>> rolesByEmployeeId) {
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
                        Employee employee = new Employee(employeeId, name, crew,
                                        rolesByEmployeeId.getOrDefault(employeeId, new HashSet<>()));
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
