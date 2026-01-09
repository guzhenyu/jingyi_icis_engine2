package com.jingyicare.jingyi_icis_engine.entity.users;

import jakarta.persistence.*;
import java.io.Serializable;

import lombok.*;

@NoArgsConstructor
@AllArgsConstructor
@Getter
@Setter
@EqualsAndHashCode
@Embeddable
public class RbacRoleRoleId implements Serializable {
    @Column(name = "parent_role_id", nullable = false)
    private Integer parentRoleId;

    @Column(name = "child_role_id", nullable = false)
    private Integer childRoleId;
}