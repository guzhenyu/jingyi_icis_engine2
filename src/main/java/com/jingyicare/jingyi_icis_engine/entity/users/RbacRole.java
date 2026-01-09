package com.jingyicare.jingyi_icis_engine.entity.users;

import jakarta.persistence.*;

import lombok.*;

@NoArgsConstructor
@AllArgsConstructor
@Getter
@Setter
@Entity
@Table(name = "rbac_roles")
public class RbacRole {
    @Id
    private Integer id;

    @Column(name = "name", nullable = false)
    private String name;

    @Column(name = "is_primary", nullable = false)
    private Boolean isPrimary;
}