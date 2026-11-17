package org.acme.employeescheduling.entity.plr;

import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.Setter;

/**
 * Employees of a crew for one month (PLR_TB). Columns: F_GUID, F_ID, F_TM (rowversion, not mapped), F_DEL, BRID,
 * TABN, GGMM, POS. GGMM and POS are not filled yet (NULL): see CrewDataLoader for how such rows are used.
 */
@Entity
@Getter
@Setter
@Table(name = "PLR_TB", schema = "dbo")
public class PlrTb {

    @Id
    @Column(name = "F_GUID", nullable = false)
    public UUID id;

    @Column(name = "F_DEL")
    public Integer deleted;

    /** Crew (PLR_BR.BRID). */
    @Column(name = "BRID")
    public Integer crewId;

    /** Personnel number (BD_CARD.TABN). */
    @Column(name = "TABN")
    public Integer employeeId;

    /** Month as yyyyMM, e.g. 202611; null: not filled. */
    @Column(name = "GGMM")
    public Integer month;

    /** Position of the employee in the crew (for display); null: not filled. */
    @Column(name = "POS")
    public Integer position;
}
