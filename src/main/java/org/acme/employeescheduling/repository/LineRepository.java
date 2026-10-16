package org.acme.employeescheduling.repository;

import io.quarkus.hibernate.orm.panache.PanacheRepositoryBase;
import jakarta.enterprise.context.ApplicationScoped;
import org.acme.employeescheduling.entity.plr.PlrLines;

import java.util.List;
import java.util.UUID;

/**
 * Production lines (PLR_PLINES).
 */
@ApplicationScoped
public class LineRepository implements PanacheRepositoryBase<PlrLines, UUID> {

    /** Lines that are not deleted and have a name. One row per line and packaging type. */
    public List<PlrLines> findActive() {
        return find("deleted = 0 and lineId is not null and lineName is not null").list();
    }
}
