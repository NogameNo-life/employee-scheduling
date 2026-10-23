package org.acme.employeescheduling.repository.plr;

import java.util.List;

import org.acme.employeescheduling.entity.plr.PlrRole;
import org.springframework.data.jpa.repository.JpaRepository;

/**
 * Roles (PLR_ROLE).
 */
public interface RoleRepository extends JpaRepository<PlrRole, Integer> {

    List<PlrRole> findByDepartment(String department);
}
