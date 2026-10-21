package org.acme.employeescheduling.repository.plr;

import java.util.List;

import org.acme.employeescheduling.entity.plr.PlrRltb;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

/**
 * Employee -> role links (PLR_RLTB).
 */
public interface EmployeeRoleRepository extends JpaRepository<PlrRltb, PlrRltb.Key> {

    /**
     * Links to the roles of one department. Filtered by role, not by employee:
     * employees (BD_CARD) live in another database, so a subquery on them is not possible here.
     */
    @Query("select e from PlrRltb e where e.roleId in (select r.roleId from PlrRole r where r.department = ?1)")
    List<PlrRltb> findByDepartment(String department);
}
