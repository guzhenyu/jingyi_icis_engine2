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
public class RbacDepartmentAccountId implements Serializable {
    @Column(name = "dept_id")
    private String deptId;

    @Column(name = "account_id")
    private String accountId;
}