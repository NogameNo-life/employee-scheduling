package org.acme.employeescheduling.repository.plr;

import java.util.List;

import org.acme.employeescheduling.entity.plr.PlrTbbr;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

/**
 * Employee -> crew links (PLR_TBBR).
 */
public interface EmployeeCrewRepository extends JpaRepository<PlrTbbr, Long> {

    /** Links that are not deleted, to the crews of one department. */
    @Query("select t from PlrTbbr t where coalesce(t.deleted, 0) = 0"
            + " and t.crewId in (select b.crewId from PlrBr b where b.department = ?1)")
    List<PlrTbbr> findByDepartment(String department);
}
