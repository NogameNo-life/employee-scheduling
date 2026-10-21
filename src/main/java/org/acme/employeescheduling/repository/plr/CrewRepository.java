package org.acme.employeescheduling.repository.plr;

import java.util.List;

import org.acme.employeescheduling.entity.plr.PlrBr;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

/**
 * Crews (PLR_BR).
 */
public interface CrewRepository extends JpaRepository<PlrBr, Integer> {

    List<PlrBr> findByDepartment(String department);

    /** All departments (OBJ) that have crews. */
    @Query("select distinct b.department from PlrBr b where b.department is not null")
    List<String> findDepartments();
}
