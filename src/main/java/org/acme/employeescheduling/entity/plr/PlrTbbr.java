package org.acme.employeescheduling.entity.plr;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.Setter;

/**
 * Empolyees table
 */
@Entity
@Getter
@Setter
@Table(name = "PLR_TBBR", schema = "dbo")
public class PlrTbbr {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "F_ID")
    private Long fId;

    @Column(name = "BRID")
    public Integer crewId;

    @Column(name = "TABN")
    public Integer employeeId;

    @Column(name = "F_DEL")
    public Integer deleted;

}
