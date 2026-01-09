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
public class RbacAccountPermissionId implements Serializable {
    @Column(name = "account_id")
    private String accountId;

    @Column(name = "permission_id")
    private Integer permissionId;
}