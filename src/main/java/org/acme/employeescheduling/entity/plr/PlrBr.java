package org.acme.employeescheduling.entity.plr;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.Setter;

/**
 * Crew table
 */
@Entity
@Getter
@Setter
@Table(name = "PLR_BR", schema = "dbo")
public class PlrBr {

    /** Crew number, referenced by PLR_TB.BRID. */
    @Id
    @Column(name = "BRID")
    public Integer crewId;

    @Column(name = "NAME")
    public String crewName;

    @Column(name = "OBJ")
    public String department;

    /** People needed in the crew per shift. */
    @Column(name = "NTB")
    public Integer staffCount;

    /** Line of the crew: PLR_LC.KC, char(10). */
    @Column(name = "KC")
    public String lineId;
}
