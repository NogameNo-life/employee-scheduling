package org.acme.employeescheduling.entity.plr;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.Setter;

import java.util.UUID;

/**
 * Production lines (PLR_PLINES), the same table food-packaging-quarkus uses.
 * KRC is repeated once per packaging type (GRF), so F_GUID stays the ID here.
 */
@Entity
@Getter
@Setter
@Table(name = "PLR_PLINES", schema = "dbo")
public class PlrLines {

    @Id
    @Column(name = "F_GUID", nullable = false)
    public UUID id;

    /** Line code. */
    @Column(name = "KRC")
    public String lineId;

    @Column(name = "SNM")
    public String lineName;

    @Column(name = "F_DEL")
    public Integer deleted;
}
