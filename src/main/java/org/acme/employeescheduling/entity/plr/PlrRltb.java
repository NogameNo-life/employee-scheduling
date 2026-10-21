package org.acme.employeescheduling.entity.plr;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.IdClass;
import jakarta.persistence.Table;
import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.io.Serializable;

/**
 * The table for linking roles to an employee
 */
@Entity
@Getter
@Setter
@Table(name = "PLR_RLTB", schema = "dbo")
@IdClass(PlrRltb.Key.class)
public class PlrRltb {

    @Id
    @Column(name = "TABN")
    public Integer employeeId;

    @Id
    @Column(name = "RLID")
    public Integer roleId;

    /** Composite key: one row per employee and role. */
    @NoArgsConstructor
    @EqualsAndHashCode
    public static class Key implements Serializable {
        public Integer employeeId;
        public Integer roleId;
    }
}
