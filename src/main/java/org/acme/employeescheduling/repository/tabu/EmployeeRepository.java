package org.acme.employeescheduling.repository.tabu;

import java.util.List;

import org.acme.employeescheduling.entity.tabu.BdCrd;
import org.springframework.data.jpa.repository.JpaRepository;

/**
 * Employees (BD_CARD). Reads the tabu2 database (see TabuDataSourceConfig).
 */
public interface EmployeeRepository extends JpaRepository<BdCrd, Integer> {

    List<BdCrd> findByDepartment(String department);
}
