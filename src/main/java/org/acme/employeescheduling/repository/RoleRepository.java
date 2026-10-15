package org.acme.employeescheduling.repository;

import io.quarkus.hibernate.orm.panache.PanacheRepositoryBase;
import jakarta.enterprise.context.ApplicationScoped;
import org.acme.employeescheduling.entity.plr.PlrRole;

import java.util.List;

/**
 * Roles (PLR_ROLE).
 */
@ApplicationScoped
public class RoleRepository implements PanacheRepositoryBase<PlrRole, Integer> {

    public List<PlrRole> findByDepartment(String department) {
        return find("department = ?1", department).list();
    }
}
