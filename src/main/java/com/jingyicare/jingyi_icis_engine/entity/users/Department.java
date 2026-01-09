package com.jingyicare.jingyi_icis_engine.entity.users;

import jakarta.persistence.*;
import lombok.*;
import java.time.LocalDateTime;

@NoArgsConstructor
@AllArgsConstructor
@Getter
@Setter
@Entity
@Table(name = "departments")
public class Department {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Integer id; // 自增主键

    @Column(name = "dept_id", nullable = false, unique = true)
    private String deptId; // 部门编码

    @Column(name = "name", nullable = false, unique = true)
    private String name; // 部门名称

    @Column(name = "abbreviation", nullable = false)
    private String abbreviation; // 部门简称

    @Column(name = "ward_code")
    private String wardCode; // 病区编码

    @Column(name = "ward_name")
    private String wardName; // 病区名称

    @Column(name = "hospital_name", nullable = false)
    private String hospitalName; // 所属医院名称

    @Column(name = "is_deleted", nullable = false, columnDefinition = "boolean default false")
    private Boolean isDeleted = false; // 是否已删除

    @Column(name = "deleted_at")
    private LocalDateTime deletedAt; // 删除时间

    @Column(name = "deleted_by")
    private String deletedBy; // 删除人

    @Column(name = "modified_at")
    private LocalDateTime modifiedAt; // 修改时间

    @Column(name = "modified_by")
    private String modifiedBy; // 修改人
}