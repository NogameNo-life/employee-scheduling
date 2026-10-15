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
 * The table for linking roles to an employee
 */
@Entity
@Getter
@Setter
@Table(name = "PLR_RLTB", schema = "dbo")
public class PlrRltb extends PanacheEntityBase {

    @Id
    @Column(name = "F_GUID", nullable = false)
    public UUID id;

    @Column(name = "TABN")
    public Integer employeeId;

    @Column(name = "RLID")
    public Integer roleId;
}
