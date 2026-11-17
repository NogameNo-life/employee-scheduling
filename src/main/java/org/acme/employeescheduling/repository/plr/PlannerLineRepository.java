package org.acme.employeescheduling.repository.plr;

import java.util.List;
import java.util.UUID;

import org.acme.employeescheduling.entity.plr.PlrLc;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

/**
 * Lines of the planner (PLR_LC).
 */
public interface PlannerLineRepository extends JpaRepository<PlrLc, UUID> {

    /** Rows that are not deleted and have a KC, for every period (the month is checked in memory). */
    @Query("select l from PlrLc l where coalesce(l.deleted, 0) = 0 and l.kc is not null")
    List<PlrLc> findActive();
}
