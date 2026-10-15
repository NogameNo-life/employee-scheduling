package org.acme.employeescheduling.repository;

import io.quarkus.hibernate.orm.panache.PanacheRepositoryBase;
import jakarta.enterprise.context.ApplicationScoped;
import org.acme.employeescheduling.entity.plr.PlrRltb;

import java.util.List;

/**
 * Employee -> role links (PLR_RLTB).
 */
@ApplicationScoped
public class EmployeeRoleRepository implements PanacheRepositoryBase<PlrRltb, PlrRltb.Key> {

    /**
     * Links to the roles of one department. Filtered by role, not by employee:
     * employees (BD_CARD) live in another database, so a subquery on them is not possible here.
     */
    public List<PlrRltb> findByDepartment(String department) {
        return find("roleId in (select r.roleId from PlrRole r where r.department = ?1)", department).list();
    }
}
