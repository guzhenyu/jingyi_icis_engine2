package com.jingyicare.jingyi_icis_engine.entity.users;

import jakarta.persistence.*;
import java.io.Serializable;

import lombok.*;

@NoArgsConstructor
@AllArgsConstructor
@Getter
@Setter
@Entity
@Table(name = "rbac_accounts_roles")
public class RbacAccountRole {
    @EmbeddedId
    private RbacAccountRoleId id;

    @ManyToOne
    @JoinColumn(name = "account_id", insertable = false, updatable = false)
    private RbacAccount rbacAccount;

    @ManyToOne
    @JoinColumn(name = "role_id", insertable = false, updatable = false)
    private RbacRole rbacRole;
}