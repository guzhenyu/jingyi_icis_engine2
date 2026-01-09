package com.jingyicare.jingyi_icis_engine.entity.users;

import jakarta.persistence.*;
import lombok.*;

@NoArgsConstructor
@AllArgsConstructor
@Getter
@Setter
@Entity
@Table(name = "rbac_roles_roles")
public class RbacRoleRole {
    @EmbeddedId
    private RbacRoleRoleId id;
}