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
import org.acme.employeescheduling.entity.BdCrd;
import org.acme.employeescheduling.entity.PlrBr;
import org.acme.employeescheduling.entity.PlrRlbr;
import org.acme.employeescheduling.entity.PlrRltb;
import org.acme.employeescheduling.entity.PlrRole;

/**
 * Loads crews, roles, employees and crew composition for one department.
 *
 * <p>
 * Exactly 5 queries, no matter how many employees there are (no N+1):
 * every table is read once and joined in memory with maps.
 * Subqueries are used instead of "IN (:ids)" because SQL Server allows
 * at most 2100 parameters per query.
 */
@ApplicationScoped
public class CrewDataLoader {

        public CrewData load(String department) {
                // 1) Crews
                List<PlrBr> crewRows = PlrBr.list("department", department);
                Map<Integer, Crew> crewsById = crewRows.stream()
                                .filter(r -> r.crewId != null)
                                .collect(toMap(r -> r.crewId, r -> new Crew(r.crewId, r.crewName), (a, b) -> a));

                // 2) Roles
                List<PlrRole> roleRows = PlrRole.list("department", department);
                Map<Integer, Role> rolesById = roleRows.stream()
                                .filter(r -> r.roleId != null)
                                .collect(toMap(r -> r.roleId, r -> new Role(r.roleId, r.roleName), (a, b) -> a));

                // 3) Employee -> roles
                List<PlrRltb> employeeRoleRows = PlrRltb.list(
                                "employeeId in (select e.employeeId from BdCrd e where e.department = ?1)", department);
                Map<Integer, Set<Role>> rolesByEmployeeId = new HashMap<>();
                for (PlrRltb row : employeeRoleRows) {
                        Role role = rolesById.get(row.roleId);
                        if (role != null) {
                                rolesByEmployeeId.computeIfAbsent(row.employeeId, id -> new HashSet<>()).add(role);
                        }
                }

                // 4) Employees (only those who belong to a known crew)
                List<BdCrd> employeeRows = BdCrd.list("department", department);
                List<Employee> employees = employeeRows.stream()
                                .filter(r -> r.employeeId != null && crewsById.containsKey(r.crewId))
                                .map(r -> new Employee(r.employeeId, r.fullName, crewsById.get(r.crewId),
                                                rolesByEmployeeId.getOrDefault(r.employeeId, new HashSet<>())))
                                .toList();

                // 5) Crew composition: crew -> role -> number of people per shift
                List<PlrRlbr> compositionRows = PlrRlbr.list(
                                "crewId in (select b.crewId from PlrBr b where b.department = ?1)", department);
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
