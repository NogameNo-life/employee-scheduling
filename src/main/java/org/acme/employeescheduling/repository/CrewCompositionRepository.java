package org.acme.employeescheduling.repository;

import io.quarkus.hibernate.orm.panache.PanacheRepositoryBase;
import jakarta.enterprise.context.ApplicationScoped;
import org.acme.employeescheduling.entity.plr.PlrRlbr;

import java.util.List;

/**
 * Crew composition (PLR_RLBR): how many people of each role a crew needs per shift.
 */
@ApplicationScoped
public class CrewCompositionRepository implements PanacheRepositoryBase<PlrRlbr, PlrRlbr.Key> {

    public List<PlrRlbr> findByDepartment(String department) {
        return find("crewId in (select b.crewId from PlrBr b where b.department = ?1)", department).list();
    }
}
