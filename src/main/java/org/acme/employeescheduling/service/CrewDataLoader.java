package org.acme.employeescheduling.service;

import static java.util.stream.Collectors.groupingBy;
import static java.util.stream.Collectors.toMap;

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

/**
 * Loads crews, roles, employees and crew composition for one department.
 *
 * <p>
 * Exactly 5 queries, no matter how many employees there are (no N+1):
 * every table is read once and joined in memory with maps.
 * Subqueries are used instead of "IN (:ids)" because SQL Server allows
 * at most 2100 parameters per query.
 * Employees (BD_CARD) are in another database (tabu2), so they are joined
 * with the other tables in memory only.
 */
@ApplicationScoped
public class CrewDataLoader {

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

                // 4) Employees (only those who belong to a known crew)
                List<BdCrd> employeeRows = employeeRepository.findByDepartment(department);
                List<Employee> employees = employeeRows.stream()
                                .filter(r -> r.employeeId != null && crewsById.containsKey(r.crewId))
                                .map(r -> new Employee(r.employeeId, r.fullName, crewsById.get(r.crewId),
                                                rolesByEmployeeId.getOrDefault(r.employeeId, new HashSet<>())))
                                .toList();

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
}
