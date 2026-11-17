package org.acme.employeescheduling.entity.plr;

import java.math.BigDecimal;
import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.Setter;

/**
 * Shift types and times (PLR_SM). Columns: F_GUID, F_ID, F_TM (rowversion, not mapped), F_DEL, SM, TMNR, TMKR, SNG.
 * Real rows: SM 1, 8.00-20.00, SNG "446"; SM 2, 20.00-8.00, SNG "60;61".
 */
@Entity
@Getter
@Setter
@Table(name = "PLR_SM", schema = "dbo")
public class PlrSm {

    @Id
    @Column(name = "F_GUID", nullable = false)
    public UUID id;

    @Column(name = "F_DEL")
    public Integer deleted;

    /** Shift type, C10: "1" = DAY, "2" = NIGHT. */
    @Column(name = "SM")
    public String code;

    /** Start, N5,2 "hours.minutes". */
    @Column(name = "TMNR")
    public BigDecimal start;

    /** End, N5,2 "hours.minutes". */
    @Column(name = "TMKR")
    public BigDecimal end;

    /** Codes separated by ';' (NS_TRD). */
    @Column(name = "SNG")
    public String sng;
}
