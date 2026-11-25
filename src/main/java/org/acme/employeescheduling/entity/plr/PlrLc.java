package org.acme.employeescheduling.entity.plr;

import java.time.LocalDate;
import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.Setter;

/**
 * Lines of the planner (PLR_LC). Maps the new line code KC ("БерЦГС1") to the old code KRC ("170610060000"),
 * which line output uses. The table has no name column: names come from PLR_PLINES.SNM by KRC.
 */
@Entity
@Getter
@Setter
@Table(name = "PLR_LC", schema = "dbo")
public class PlrLc {

    @Id
    @Column(name = "F_GUID", nullable = false)
    public UUID id;

    @Column(name = "F_DEL")
    public Integer deleted;

    /** Old line code, as in PLR_PLINES.KRC. */
    @Column(name = "KRC")
    public String krc;

    /** New line code, char(10); null on deleted rows. Referenced by PLR_BR.KC. */
    @Column(name = "KC")
    public String kc;

    /*
     * DTBEG and DTEND are datetime NULL columns. They are read as LocalDate: Hibernate reads them with
     * ResultSet.getDate, which keeps only the date. Only the day matters to check if the line is active in a month.
     */

    /** First day of the line (null: no start). */
    @Column(name = "DTBEG")
    public LocalDate from;

    /** Last day of the line (null: still active). */
    @Column(name = "DTEND")
    public LocalDate to;
}
