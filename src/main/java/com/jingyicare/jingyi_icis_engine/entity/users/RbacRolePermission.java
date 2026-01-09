package com.jingyicare.jingyi_icis_engine.entity.users;

import jakarta.persistence.*;
import java.io.Serializable;

import lombok.*;

@NoArgsConstructor
@AllArgsConstructor
@Getter
@Setter
@Entity
@Table(name = "rbac_roles_permissions")
public class RbacRolePermission {
    @EmbeddedId
    private RbacRolePermissionId id;

    @ManyToOne
    @JoinColumn(name = "role_id", insertable = false, updatable = false)
    private RbacRole rbacRole;

    @ManyToOne
    @JoinColumn(name = "permission_id", insertable = false, updatable = false)
    private RbacPermission rbacPermission;  
}