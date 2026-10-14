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
 * Crew table
 */
@Entity
@Getter
@Setter
@Table(name = "PLR_BR", schema = "dbo")
public class PLR_BR extends PanacheEntityBase {

    @Id
    @Column(name = "F_GUID", nullable = false)
    public UUID id;

    @Column(name = "NAME")
    public String crewName;

    @Column(name = "OBJ")
    public String department;
}
