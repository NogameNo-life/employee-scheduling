package org.acme.employeescheduling.service;

import java.util.List;
import java.util.Map;

import org.acme.employeescheduling.domain.Crew;
import org.acme.employeescheduling.domain.Employee;
import org.acme.employeescheduling.domain.Role;

/**
 * Master data loaded from the database for one department.
 *
 * @param composition crew id -> (role -> number of people needed per shift), from PLR_RLBR
 */
public record CrewData(
        List<Crew> crews,
        List<Role> roles,
        List<Employee> employees,
        Map<Integer, Map<Role, Integer>> composition) {
}
