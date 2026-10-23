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
 * A table for linking roles to a team
 */
@Entity
@Getter
@Setter
@Table(name = "PLR_RLBR", schema = "dbo")
@IdClass(PlrRlbr.Key.class)
public class PlrRlbr {

    @Id
    @Column(name = "BRID")
    public Integer crewId;

    @Id
    @Column(name = "RLID")
    public Integer roleId;

    @Column(name = "NPERS")
    public Integer numberOfStaff;

    /** Composite key: one row per crew and role. */
    @NoArgsConstructor
    @EqualsAndHashCode
    public static class Key implements Serializable {
        public Integer crewId;
        public Integer roleId;
    }
}
