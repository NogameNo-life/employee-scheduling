package org.acme.employeescheduling.repository;

import io.quarkus.hibernate.orm.panache.PanacheRepositoryBase;
import jakarta.enterprise.context.ApplicationScoped;
import org.acme.employeescheduling.entity.tabu.BdCrd;

import java.util.List;

/**
 * Employees (BD_CARD). Reads the tabu2 database through the "tabu" persistence unit.
 */
@ApplicationScoped
public class EmployeeRepository implements PanacheRepositoryBase<BdCrd, Integer> {

    public List<BdCrd> findByDepartment(String department) {
        return find("department = ?1", department).list();
    }
}
