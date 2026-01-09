package com.jingyicare.jingyi_icis_engine.entity.users;

import jakarta.persistence.*;
import java.io.Serializable;

import lombok.*;

@NoArgsConstructor
@AllArgsConstructor
@Getter
@Setter
@Entity
@Table(name = "rbac_accounts_departments_roles")
public class RbacDepartmentAccountRole {
    @EmbeddedId
    private RbacDepartmentAccountRoleId id;

    @ManyToOne
    @JoinColumns({
        @JoinColumn(name="account_id", referencedColumnName="account_id", insertable = false, updatable = false),
        @JoinColumn(name="dept_id", referencedColumnName="dept_id", insertable = false, updatable = false)
    })
    private RbacDepartmentAccount rbacDepartmentAccount;
}