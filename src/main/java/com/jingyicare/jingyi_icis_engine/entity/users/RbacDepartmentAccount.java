package com.jingyicare.jingyi_icis_engine.entity.users;

import jakarta.persistence.*;
import java.io.Serializable;

import lombok.*;

@NoArgsConstructor
@AllArgsConstructor
@Getter
@Setter
@Entity
@Table(name = "rbac_accounts_departments")
public class RbacDepartmentAccount {
    @EmbeddedId
    private RbacDepartmentAccountId id;

    @ManyToOne
    @JoinColumn(name = "account_id", insertable = false, updatable = false)
    private RbacAccount rbacAccount;

    @Column(name = "primary_role_id")
    private Integer primaryRoleId;
}
