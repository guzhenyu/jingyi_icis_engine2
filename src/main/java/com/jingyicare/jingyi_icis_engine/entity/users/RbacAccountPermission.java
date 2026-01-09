package com.jingyicare.jingyi_icis_engine.entity.users;

import jakarta.persistence.*;

import lombok.*;

@NoArgsConstructor
@AllArgsConstructor
@Getter
@Setter
@Entity
@Table(name = "rbac_accounts_permissions")
public class RbacAccountPermission {
    @EmbeddedId
    private RbacAccountPermissionId id;
}