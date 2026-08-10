package com.jingyicare.jingyi_icis_engine.service.ca;

import org.springframework.stereotype.Component;

import com.jingyicare.jingyi_icis_engine.entity.users.RbacDepartmentAccountId;
import com.jingyicare.jingyi_icis_engine.repository.users.RbacDepartmentAccountRepository;
import com.jingyicare.jingyi_icis_engine.service.ConfigProtoService;
import com.jingyicare.jingyi_icis_engine.service.users.UserService;
import com.jingyicare.jingyi_icis_engine.utils.StrUtils;

@Component
public class CaAccessPolicy {
    public CaAccessPolicy(
        ConfigProtoService protoService,
        UserService userService,
        RbacDepartmentAccountRepository deptAccountRepo
    ) {
        this.adminAccountId = protoService.getConfig().getUser().getAdminAccountId();
        this.userService = userService;
        this.deptAccountRepo = deptAccountRepo;
    }

    public boolean canCurrentUserAccessDept(String deptId) {
        String callerAccountId = userService.getCtxAccountId();
        if (StrUtils.isBlank(callerAccountId) || StrUtils.isBlank(deptId)) return false;
        return adminAccountId.equals(callerAccountId) || accountBelongsToDept(callerAccountId, deptId);
    }

    public boolean accountBelongsToDept(String accountId, String deptId) {
        if (StrUtils.isBlank(accountId) || StrUtils.isBlank(deptId)) return false;
        return deptAccountRepo.findById(new RbacDepartmentAccountId(deptId, accountId)).isPresent();
    }

    private final String adminAccountId;
    private final UserService userService;
    private final RbacDepartmentAccountRepository deptAccountRepo;
}
