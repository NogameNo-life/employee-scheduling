package org.acme.employeescheduling.service;

import java.util.List;

import org.acme.employeescheduling.domain.Crew;
import org.acme.employeescheduling.domain.Employee;

/**
 * Master data loaded from the database for one department.
 */
public record CrewData(
        List<Crew> crews,
        List<Employee> employees) {
}
