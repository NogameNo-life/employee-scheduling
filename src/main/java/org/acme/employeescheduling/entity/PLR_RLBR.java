package org.acme.employeescheduling.entity;

import io.quarkus.hibernate.orm.panache.PanacheEntityBase;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.Setter;

import java.util.UUID;

/**
 * A table for linking roles to a team
 */
@Entity
@Getter
@Setter
@Table(name = "PLR_RLBR", schema = "dbo")
public class PLR_RLBR extends PanacheEntityBase {

    @Id
    @Column(name = "F_GUID", nullable = false)
    public UUID id;

    @Column(name = "BRID")
    public Integer crewId;

    @Column(name = "NPERS")
    public Integer numberOfStaff;

    @Column(name = "RLID")
    public Integer roleId;
}
