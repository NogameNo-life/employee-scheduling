package org.acme.employeescheduling.repository.plr;

import java.util.List;
import java.util.UUID;

import org.acme.employeescheduling.entity.plr.PlrTb;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/**
 * Employees of a crew by month (PLR_TB). Only rows that are not deleted, of the crews of one department
 * (a subquery, not "IN (:ids)": SQL Server allows at most 2100 parameters).
 */
public interface EmployeeCrewRepository extends JpaRepository<PlrTb, UUID> {

    String OF_DEPARTMENT = " and coalesce(t.deleted, 0) = 0"
            + " and t.crewId in (select b.crewId from PlrBr b where b.department = :department)";

    /** The latest month (yyyyMM) up to {@code month} that has rows; null if there is none. */
    @Query("select max(t.month) from PlrTb t where t.month <= :month" + OF_DEPARTMENT)
    Integer findLatestMonth(@Param("department") String department, @Param("month") int month);

    /** Rows of one month (yyyyMM). */
    @Query("select t from PlrTb t where t.month = :month" + OF_DEPARTMENT)
    List<PlrTb> findByDepartmentAndMonth(@Param("department") String department, @Param("month") int month);

    /** Rows without a month (GGMM is NULL). */
    @Query("select t from PlrTb t where t.month is null" + OF_DEPARTMENT)
    List<PlrTb> findByDepartmentWithoutMonth(@Param("department") String department);
}
