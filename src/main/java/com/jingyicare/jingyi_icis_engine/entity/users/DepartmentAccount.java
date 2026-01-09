package com.jingyicare.jingyi_icis_engine.entity.users;

import java.time.LocalDateTime;
import jakarta.persistence.*;

import lombok.*;

@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
@Entity
@Table(
    name = "accounts_departments",
    indexes = {
        @Index(name = "idx_accounts_departments", columnList = "account_id, dept_id")
    }
)
public class DepartmentAccount {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Integer id; // 自增主键

    @Column(name = "employee_id", nullable = false)
    private Long employeeId; // 员工ID，对应 accounts.id

    @Column(name = "account_id", nullable = false)
    private String accountId; // 账号，对应 accounts.account_id

    @Column(name = "dept_id", nullable = false)
    private String deptId; // 部门，对应 departments.dept_id

    @Column(name = "primary_role_id", nullable = false)
    private Integer primaryRoleId; // 主要角色

    @Column(name = "start_date", nullable = false)
    private LocalDateTime startDate; // 开始时间

    @Column(name = "is_deleted", nullable = false)
    private Boolean isDeleted; // 是否已删除

    @Column(name = "deleted_at")
    private LocalDateTime deletedAt; // 删除时间

    @Column(name = "deleted_by")
    private String deletedBy; // 删除人
}