package com.jingyicare.jingyi_icis_engine.entity.users;

import jakarta.persistence.*;

import lombok.*;

@NoArgsConstructor
@AllArgsConstructor
@Getter
@Setter
@Entity
@Table(name = "rbac_accounts")
public class RbacAccount {
    @Id
    @Column(name = "account_id")
    private String accountId;

    @Column(name = "account_name")
    private String accountName;

    @Column(name = "password_hash")
    private String passwordHash;
}