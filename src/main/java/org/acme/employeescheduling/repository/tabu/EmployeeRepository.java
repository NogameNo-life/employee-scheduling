package org.acme.employeescheduling.repository.tabu;

import java.util.List;

import org.acme.employeescheduling.entity.tabu.BdCard;
import org.springframework.data.jpa.repository.JpaRepository;

/**
 * Employees (BD_CARD). Reads the tabu2 database (see TabuDataSourceConfig).
 */
public interface EmployeeRepository extends JpaRepository<BdCard, Integer> {

    List<BdCard> findByDepartment(String department);
}
