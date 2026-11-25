package org.acme.employeescheduling.repository.plr;

import java.util.List;
import java.util.UUID;

import org.acme.employeescheduling.entity.plr.PlrSm;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

/**
 * Shift types and times (PLR_SM).
 */
public interface ShiftTimeRepository extends JpaRepository<PlrSm, UUID> {

    @Query("select s from PlrSm s where coalesce(s.deleted, 0) = 0")
    List<PlrSm> findActive();
}
