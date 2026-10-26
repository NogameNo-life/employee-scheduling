package org.acme.employeescheduling.repository.plr;

import java.util.List;

import org.acme.employeescheduling.entity.plr.PlrRlbr;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

/**
 * Crew composition (PLR_RLBR): how many people of each role a crew needs per shift.
 */
public interface CrewCompositionRepository extends JpaRepository<PlrRlbr, PlrRlbr.Key> {

    @Query("select c from PlrRlbr c where c.crewId in (select b.crewId from PlrBr b where b.department = ?1)")
    List<PlrRlbr> findByDepartment(String department);
}
