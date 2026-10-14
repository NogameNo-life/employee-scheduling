package org.acme.employeescheduling.entity;

import io.quarkus.hibernate.orm.panache.PanacheEntityBase;
import jakarta.persistence.Entity;
import lombok.Getter;
import lombok.Setter;
import jakarta.persistence.*;

import java.util.UUID;

/**
 * Employee table
 */
@Entity
@Getter
@Setter
@Table(name = "BD_CRD", schema = "dbo")
public class BD_CRD extends PanacheEntityBase {

    @Id
    @Column(name = "F_GUID", nullable = false)
    public UUID id;

    @Column(name = "BRID")
    public Integer crewId;

    @Column(name = "FIO")
    public String fullName;

    @Column(name = "TABN")
    public Integer employeeId;

    @Column(name = "OBJ")
    public String department;
}
