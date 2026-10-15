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
 * Role table
 */
@Entity
@Getter
@Setter
@Table(name = "PLR_ROLE", schema = "dbo")
public class PlrRole extends PanacheEntityBase {

    @Id
    @Column(name = "F_GUID", nullable = false)
    public UUID id;

    @Column(name = "RLID")
    public Integer roleId;

    @Column(name = "NAME")
    public String roleName;

    @Column(name = "OBJ")
    public String department;
}
