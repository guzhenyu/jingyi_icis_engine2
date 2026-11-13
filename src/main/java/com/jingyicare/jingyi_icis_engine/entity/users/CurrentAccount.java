package com.jingyicare.jingyi_icis_engine.entity.users;

/**
 * 当前登录账号的轻量信息：RBAC 编码用于鉴权，自增 ID 和姓名用于业务记录。
 * 没有有效业务账号时，autoAcctId 和 acctName 回退为 RBAC 账号编码和姓名。
 */
public record CurrentAccount(String rbacAcctId, String autoAcctId, String acctName) {}
