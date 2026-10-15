package org.acme.employeescheduling.entity.tabu;

import io.quarkus.hibernate.orm.panache.PanacheEntityBase;
import jakarta.persistence.Entity;
import lombok.Getter;
import lombok.Setter;
import jakarta.persistence.*;

/**
 * Employee table (BD_CARD in the tabu2 database, "tabu" persistence unit)
 */
@Entity
@Getter
@Setter
@Table(name = "BD_CARD", schema = "dbo")
public class BdCrd extends PanacheEntityBase {

    @Column(name = "BRID")
    public Integer crewId;

    @Column(name = "FIO")
    public String fullName;

    @Id
    @Column(name = "TABN")
    public Integer employeeId;

    @Column(name = "OBJ")
    public String department;
}
