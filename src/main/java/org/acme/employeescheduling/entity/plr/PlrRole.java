package org.acme.employeescheduling.entity.plr;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.Setter;

/**
 * Role table
 */
@Entity
@Getter
@Setter
@Table(name = "PLR_ROLE", schema = "dbo")
public class PlrRole {

    @Id
    @Column(name = "RLID")
    public Integer roleId;

    @Column(name = "NAME")
    public String roleName;

    @Column(name = "OBJ")
    public String department;
}
