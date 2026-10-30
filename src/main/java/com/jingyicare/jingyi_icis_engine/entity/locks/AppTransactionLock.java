package com.jingyicare.jingyi_icis_engine.entity.locks;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.NoArgsConstructor;

@Entity
@Table(name = "app_transaction_locks")
@NoArgsConstructor
public class AppTransactionLock {

    @Id
    @Column(name = "resource_key", nullable = false, length = 255)
    private String resourceKey;
}