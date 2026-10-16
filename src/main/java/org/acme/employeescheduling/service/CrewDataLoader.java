package org.acme.employeescheduling.service;

import static java.util.stream.Collectors.groupingBy;
import static java.util.stream.Collectors.toMap;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import jakarta.enterprise.context.ApplicationScoped;

import org.acme.employeescheduling.domain.Crew;
import org.acme.employeescheduling.domain.Employee;
import org.acme.employeescheduling.domain.Role;
import org.acme.employeescheduling.entity.plr.PlrBr;
import org.acme.employeescheduling.entity.plr.PlrRlbr;
import org.acme.employeescheduling.entity.plr.PlrRltb;
import org.acme.employeescheduling.entity.plr.PlrRole;
import org.acme.employeescheduling.entity.tabu.BdCrd;
import org.acme.employeescheduling.repository.CrewCompositionRepository;
import org.acme.employeescheduling.repository.CrewRepository;
import org.acme.employeescheduling.repository.EmployeeRepository;
import org.acme.employeescheduling.repository.EmployeeRoleRepository;
import org.acme.employeescheduling.repository.RoleRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Loads crews, roles, employees and crew composition for one department.
 *
 * <p>
 * Exactly 5 queries, no matter how many employees there are (no N+1):
 * every table is read once and joined in memory with maps.
 * Subqueries are used instead of "IN (:ids)" because SQL Server allows
 * at most 2100 parameters per query.
 * Employees (BD_CARD: name and crew) are in another database (tabu2), so they are joined
 * with the other tables in memory only.
 */
@ApplicationScoped
public class CrewDataLoader {

        private static final Logger LOGGER = LoggerFactory.getLogger(CrewDataLoader.class);

        private final CrewRepository crewRepository;
        private final RoleRepository roleRepository;
        private final EmployeeRoleRepository employeeRoleRepository;
        private final EmployeeRepository employeeRepository;
        private final CrewCompositionRepository crewCompositionRepository;

        public CrewDataLoader(CrewRepository crewRepository, RoleRepository roleRepository,
                        EmployeeRoleRepository employeeRoleRepository, EmployeeRepository employeeRepository,
                        CrewCompositionRepository crewCompositionRepository) {
                this.crewRepository = crewRepository;
                this.roleRepository = roleRepository;
                this.employeeRoleRepository = employeeRoleRepository;
                this.employeeRepository = employeeRepository;
                this.crewCompositionRepository = crewCompositionRepository;
        }

        public CrewData load(String department) {
                // 1) Crews
                List<PlrBr> crewRows = crewRepository.findByDepartment(department);
                Map<Integer, Crew> crewsById = crewRows.stream()
                                .filter(r -> r.crewId != null)
                                .collect(toMap(r -> r.crewId, r -> new Crew(r.crewId, r.crewName), (a, b) -> a));

                // 2) Roles
                List<PlrRole> roleRows = roleRepository.findByDepartment(department);
                Map<Integer, Role> rolesById = roleRows.stream()
                                .filter(r -> r.roleId != null)
                                .collect(toMap(r -> r.roleId, r -> new Role(r.roleId, r.roleName), (a, b) -> a));

                // 3) Employee -> roles
                List<PlrRltb> employeeRoleRows = employeeRoleRepository.findByDepartment(department);
                Map<Integer, Set<Role>> rolesByEmployeeId = new HashMap<>();
                for (PlrRltb row : employeeRoleRows) {
                        Role role = rolesById.get(row.roleId);
                        if (role != null) {
                                rolesByEmployeeId.computeIfAbsent(row.employeeId, id -> new HashSet<>()).add(role);
                        }
                }

                // 4) Employees with name and crew
                List<Employee> employees = loadEmployees(department, crewsById, rolesByEmployeeId);

                // 5) Crew composition: crew -> role -> number of people per shift
                List<PlrRlbr> compositionRows = crewCompositionRepository.findByDepartment(department);
                Map<Integer, Map<Role, Integer>> composition = new HashMap<>();
                compositionRows.stream()
                                .filter(r -> rolesById.containsKey(r.roleId) && r.numberOfStaff != null
                                                && r.numberOfStaff > 0)
                                .collect(groupingBy(r -> r.crewId))
                                .forEach((crewId, rows) -> composition.put(crewId, rows.stream()
                                                .collect(toMap(r -> rolesById.get(r.roleId), r -> r.numberOfStaff,
                                                                Integer::sum))));

                return new CrewData(
                                List.copyOf(crewsById.values()),
                                List.copyOf(rolesById.values()),
                                employees,
                                composition);
        }

        /**
         * Employees of the department's crews from BD_CARD (tabu2), and each crew's employee list.
         * If tabu2 cannot be read, everyone with a role in PLR_RLTB is used, without name and crew,
         * so the crew schedule can still be shown.
         */
        private List<Employee> loadEmployees(String department, Map<Integer, Crew> crewsById,
                        Map<Integer, Set<Role>> rolesByEmployeeId) {
                List<BdCrd> employeeRows;
                try {
                        employeeRows = employeeRepository.findByDepartment(department);
                } catch (RuntimeException e) {
                        LOGGER.warn("Could not read employees (BD_CARD, tabu2) of department {}: names and crews are"
                                        + " unknown, personnel numbers are used instead.", department, e);
                        return rolesByEmployeeId.entrySet().stream()
                                        .filter(entry -> entry.getKey() != null)
                                        .map(entry -> new Employee(entry.getKey(), String.valueOf(entry.getKey()), null,
                                                        entry.getValue()))
                                        .toList();
                }

                List<Employee> employees = new ArrayList<>();
                int withoutCrew = 0;
                for (BdCrd row : employeeRows) {
                        Crew crew = crewsById.get(row.crewId);
                        if (row.employeeId == null || crew == null) {
                                withoutCrew++;
                                continue;
                        }
                        Employee employee = new Employee(row.employeeId, fullName(row), crew,
                                        rolesByEmployeeId.getOrDefault(row.employeeId, new HashSet<>()));
                        crew.getEmployees().add(employee);
                        employees.add(employee);
                }
                if (withoutCrew > 0) {
                        LOGGER.info("Department {}: {} employees in BD_CARD have no crew from PLR_BR and are skipped.",
                                        department, withoutCrew);
                }
                return employees;
        }

        /** FIO is one CHAR(70) column ("Last First Middle"): trimmed, with single spaces. */
        static String fullName(BdCrd row) {
                if (row.fullName == null || row.fullName.isBlank()) {
                        return String.valueOf(row.employeeId);
                }
                return row.fullName.trim().replaceAll("\\s+", " ");
        }
}
