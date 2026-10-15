package org.acme.employeescheduling.repository;

import io.quarkus.hibernate.orm.panache.PanacheRepositoryBase;
import jakarta.enterprise.context.ApplicationScoped;
import org.acme.employeescheduling.entity.plr.PlrBr;

import java.util.List;

/**
 * Crews (PLR_BR).
 */
@ApplicationScoped
public class CrewRepository implements PanacheRepositoryBase<PlrBr, Integer> {

    public List<PlrBr> findByDepartment(String department) {
        return find("department = ?1", department).list();
    }
}
