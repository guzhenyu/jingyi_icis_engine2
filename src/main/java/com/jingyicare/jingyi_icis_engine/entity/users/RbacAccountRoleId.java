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
public class RbacAccountRoleId implements Serializable {
    @Column(name = "account_id")
    private String accountId;

    @Column(name = "role_id")
    private Integer roleId;
}