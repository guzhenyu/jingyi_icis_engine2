package com.jingyicare.jingyi_icis_engine.service.users;

import java.time.LocalDateTime;

import com.jingyicare.jingyi_icis_engine.entity.users.Account;
import com.jingyicare.jingyi_icis_engine.entity.users.RbacAccount;
import com.jingyicare.jingyi_icis_engine.proto.config.IcisUser.AccountPB;
import com.jingyicare.jingyi_icis_engine.utils.StrUtils;
import com.jingyicare.jingyi_icis_engine.utils.TimeUtils;

public final class UserMapper {
    private UserMapper() {}

    // 密码哈希由业务层使用配置的编码器设置。
    public static RbacAccount toRbacAccount(AccountPB accountPb) {
        RbacAccount account = new RbacAccount();
        account.setAccountId(accountPb.getAccountId());
        account.setAccountName(accountPb.getAccountName());
        return account;
    }

    public static Account toAccount(AccountPB accountPb) {
        Account account = new Account();
        account.setAccountId(accountPb.getAccountId());
        account.setName(accountPb.getAccountName());
        account.setGender(accountPb.getGender());
        LocalDateTime dateOfBirth = StrUtils.isBlank(accountPb.getDateOfBirthIso8601())
            ? null : TimeUtils.fromIso8601String(accountPb.getDateOfBirthIso8601(), "UTC");
        if (dateOfBirth != null) {
            account.setDateOfBirth(dateOfBirth);
        }
        if (!StrUtils.isBlank(accountPb.getTitle())) account.setTitle(accountPb.getTitle());
        if (!StrUtils.isBlank(accountPb.getEducationLevel())) {
            account.setEducationLevel(accountPb.getEducationLevel());
        }
        if (!StrUtils.isBlank(accountPb.getPhone())) account.setPhone(accountPb.getPhone());
        if (!StrUtils.isBlank(accountPb.getIdCardNumber())) {
            account.setIdCardNumber(accountPb.getIdCardNumber());
        }
        if (!StrUtils.isBlank(accountPb.getSignPic())) account.setSignPic(accountPb.getSignPic());
        account.setIsDeleted(false);
        return account;
    }
}
