package org.acme.employeescheduling.repository.plr;

import java.util.List;
import java.util.UUID;

import org.acme.employeescheduling.entity.plr.PlrLines;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

/**
 * Production lines (PLR_PLINES).
 */
public interface LineRepository extends JpaRepository<PlrLines, UUID> {

    /** Lines that are not deleted and have a name. One row per line and packaging type. */
    @Query("select l from PlrLines l where l.deleted = 0 and l.lineId is not null and l.lineName is not null")
    List<PlrLines> findActive();
}
