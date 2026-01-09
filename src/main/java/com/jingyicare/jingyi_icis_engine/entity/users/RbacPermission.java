package com.jingyicare.jingyi_icis_engine.entity.users;

import jakarta.persistence.*;

import lombok.*;

@NoArgsConstructor
@AllArgsConstructor
@Getter
@Setter
@Entity
@Table(name = "rbac_permissions")
public class RbacPermission {
    @Id
    private Integer id;

    @Column(name = "name")
    private String name;
}