package com.jingyicare.jingyi_icis_engine.entity.medications;

import jakarta.persistence.*;

import lombok.*;

@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
@Entity
@Table(name = "administration_routes", indexes = {
    @Index(name = "idx_administration_routes_deptid_code", columnList = "dept_id, code", unique = true)
})
public class AdministrationRoute {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Integer id;

    @Column(name = "dept_id", nullable = false)
    private String deptId;

    @Column(name = "code", nullable = false)
    private String code;

    @Column(name = "name", nullable = false)
    private String name;

    @Column(name = "is_continuous", nullable = false)
    private Boolean isContinuous;

    @Column(name = "group_id", nullable = false)
    private Integer groupId;

    @Column(name = "intake_type_id", nullable = false)
    private Integer intakeTypeId;

    @Column(name = "is_valid", nullable = false)
    private Boolean isValid;
}