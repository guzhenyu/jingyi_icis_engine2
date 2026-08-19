package com.jingyicare.jingyi_icis_engine.service.users;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.List;

import org.junit.jupiter.api.Test;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.test.util.ReflectionTestUtils;

import com.jingyicare.jingyi_icis_engine.proto.IcisConfig.Config;
import com.jingyicare.jingyi_icis_engine.proto.IcisWebApi.StatusCode;
import com.jingyicare.jingyi_icis_engine.proto.config.IcisUser.UserConfigPB;
import com.jingyicare.jingyi_icis_engine.repository.users.DepartmentRepository;
import com.jingyicare.jingyi_icis_engine.repository.users.RbacAccountRepository;
import com.jingyicare.jingyi_icis_engine.repository.users.RbacDepartmentAccountPermissionRepository;
import com.jingyicare.jingyi_icis_engine.repository.users.RbacDepartmentAccountRepository;
import com.jingyicare.jingyi_icis_engine.repository.users.RbacDepartmentAccountRoleRepository;
import com.jingyicare.jingyi_icis_engine.repository.users.RbacDepartmentRepository;
import com.jingyicare.jingyi_icis_engine.repository.users.RbacPermissionRepository;
import com.jingyicare.jingyi_icis_engine.repository.users.RbacRolePermissionRepository;
import com.jingyicare.jingyi_icis_engine.repository.users.RbacRoleRepository;
import com.jingyicare.jingyi_icis_engine.repository.users.RbacRoleRoleRepository;
import com.jingyicare.jingyi_icis_engine.service.ConfigProtoService;
import com.jingyicare.jingyi_icis_engine.service.IcisBootstrapProperties;

class IcisBootstrapInitializationTests {
    @Test
    void emptyDatabaseUsesDeploymentBootstrapInsteadOfBundledDemoDepartment() {
        ConfigProtoService protoService = mock(ConfigProtoService.class);
        when(protoService.getConfig()).thenReturn(Config.newBuilder()
            .setZoneId("UTC")
            .setUser(UserConfigPB.newBuilder().setAdminRoleId(1))
            .build());

        UserBasicOperator userBasicOperator = mock(UserBasicOperator.class);
        when(userBasicOperator.addDepartment(
            "ICU01", "重症医学科", "重症医学科", "", "", "测试医院", "System"))
            .thenReturn(StatusCode.OK);

        DepartmentRepository departmentRepository = mock(DepartmentRepository.class);
        when(departmentRepository.count()).thenReturn(0L);

        IcisBootstrapProperties bootstrap = new IcisBootstrapProperties();
        bootstrap.setHospitalName("测试医院");
        IcisBootstrapProperties.DepartmentEntry department =
            new IcisBootstrapProperties.DepartmentEntry();
        department.setDeptCode("ICU01");
        department.setName("重症医学科");
        department.setAbbreviation("重症医学科");
        department.setWardCode("");
        department.setWardName("");
        bootstrap.setDepartments(List.of(department));

        UserConfig userConfig = new UserConfig(
            mock(ConfigurableApplicationContext.class),
            protoService,
            userBasicOperator,
            mock(RbacPermissionRepository.class),
            mock(RbacRoleRepository.class),
            mock(RbacRolePermissionRepository.class),
            mock(RbacRoleRoleRepository.class),
            mock(RbacAccountRepository.class),
            mock(RbacDepartmentRepository.class),
            mock(RbacDepartmentAccountRepository.class),
            mock(RbacDepartmentAccountRoleRepository.class),
            mock(RbacDepartmentAccountPermissionRepository.class),
            departmentRepository,
            bootstrap);

        ReflectionTestUtils.invokeMethod(userConfig, "initAccountsAndDepartments");

        verify(userBasicOperator).addDepartment(
            "ICU01", "重症医学科", "重症医学科", "", "", "测试医院", "System");
    }
}
