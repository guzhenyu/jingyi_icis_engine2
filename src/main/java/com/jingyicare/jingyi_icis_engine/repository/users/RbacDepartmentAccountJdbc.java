package com.jingyicare.jingyi_icis_engine.repository.users;

import java.time.LocalDateTime;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import org.springframework.jdbc.core.JdbcTemplate;

public final class RbacDepartmentAccountJdbc {
    private RbacDepartmentAccountJdbc() {}

    // 锁计划只读取科室标识，绕过 JPA 一级缓存以便锁后复查。
    public static Set<String> findDepartmentIds(JdbcTemplate jdbcTemplate, String rbacAcctId) {
        return new HashSet<>(jdbcTemplate.queryForList("""
            SELECT dept_id FROM rbac_accounts_departments WHERE account_id = ?
            """, String.class, rbacAcctId));
    }

    public record DepartmentAccountState(
        int id, long employeeId, String deptId, int primaryRoleId, LocalDateTime startDate,
        LocalDateTime deletedAt, String deletedBy
    ) {}

    public record DeletePlan(
        Set<String> rbacDeptIds, List<DepartmentAccountState> departmentAccounts
    ) {
        public Set<String> departmentIds() {
            Set<String> deptIds = new HashSet<>(rbacDeptIds);
            for (DepartmentAccountState account : departmentAccounts) {
                deptIds.add(account.deptId());
            }
            return deptIds;
        }
    }

    // 比较两张关系表；业务记录的替换、角色或其他字段变更也会使锁计划失效。
    public static DeletePlan findDeletePlan(JdbcTemplate jdbcTemplate, String rbacAcctId) {
        Set<String> rbacDeptIds = findDepartmentIds(jdbcTemplate, rbacAcctId);
        List<DepartmentAccountState> departmentAccounts = jdbcTemplate.query("""
            SELECT id, employee_id, dept_id, primary_role_id, start_date, deleted_at, deleted_by
            FROM accounts_departments
            WHERE account_id = ? AND is_deleted = false
            ORDER BY id
            """, (rs, rowNum) -> new DepartmentAccountState(
                rs.getInt("id"), rs.getLong("employee_id"), rs.getString("dept_id"),
                rs.getInt("primary_role_id"), rs.getObject("start_date", LocalDateTime.class),
                rs.getObject("deleted_at", LocalDateTime.class), rs.getString("deleted_by")),
            rbacAcctId);
        return new DeletePlan(rbacDeptIds, departmentAccounts);
    }
}
