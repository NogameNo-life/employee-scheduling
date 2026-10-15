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

    /** All departments (OBJ) that have crews. */
    public List<String> findDepartments() {
        return getEntityManager()
                .createQuery("select distinct b.department from PlrBr b where b.department is not null", String.class)
                .getResultList();
    }
}
