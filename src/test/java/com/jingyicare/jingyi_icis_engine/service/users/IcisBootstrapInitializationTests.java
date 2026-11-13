package com.jingyicare.jingyi_icis_engine.service.users;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.util.List;

import org.junit.jupiter.api.Test;
import org.mockito.InOrder;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.test.util.ReflectionTestUtils;

import com.jingyicare.jingyi_icis_engine.proto.IcisConfig.Config;
import com.jingyicare.jingyi_icis_engine.proto.IcisWebApi.StatusCode;
import com.jingyicare.jingyi_icis_engine.proto.config.IcisUser.AccountDepartmentPB;
import com.jingyicare.jingyi_icis_engine.proto.config.IcisUser.AccountPB;
import com.jingyicare.jingyi_icis_engine.proto.config.IcisUser.UserConfigPB;
import com.jingyicare.jingyi_icis_engine.repository.users.DepartmentRepository;
import com.jingyicare.jingyi_icis_engine.repository.users.RbacAccountRepository;
import com.jingyicare.jingyi_icis_engine.repository.users.RbacDepartmentAccountPermissionRepository;
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
        AccountPB account = AccountPB.newBuilder()
            .setAccountId("bootstrap-test-user")
            .setAccountName("初始化测试用户")
            .addDepartment(AccountDepartmentPB.newBuilder().setDeptId("ICU01").setRoleId(1))
            .build();
        ConfigProtoService protoService = mock(ConfigProtoService.class);
        when(protoService.getConfig()).thenReturn(Config.newBuilder()
            .setZoneId("UTC")
            .setUser(UserConfigPB.newBuilder().setAdminRoleId(1).addAccount(account))
            .build());

        UserCommand userCommand = mock(UserCommand.class);
        when(userCommand.addDepartment(
            "ICU01", "重症医学科", "重症医学科", "", "", "测试医院",
            UserQuery.SYSTEM_ACCOUNT_ID))
            .thenReturn(StatusCode.OK);
        when(userCommand.addAccount(account, UserQuery.SYSTEM_ACCOUNT_ID))
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
            userCommand,
            mock(RbacPermissionRepository.class),
            mock(RbacRoleRepository.class),
            mock(RbacRolePermissionRepository.class),
            mock(RbacRoleRoleRepository.class),
            mock(RbacAccountRepository.class),
            mock(RbacDepartmentRepository.class),
            mock(RbacDepartmentAccountRoleRepository.class),
            mock(RbacDepartmentAccountPermissionRepository.class),
            departmentRepository,
            bootstrap);

        ReflectionTestUtils.invokeMethod(userConfig, "initAccountsAndDepartments");

        InOrder order = inOrder(userCommand);
        order.verify(userCommand).addDepartment(
            "ICU01", "重症医学科", "重症医学科", "", "", "测试医院",
            UserQuery.SYSTEM_ACCOUNT_ID);
        order.verify(userCommand).addAccount(account, UserQuery.SYSTEM_ACCOUNT_ID);
    }

    @Test
    void accountInitializationFailureStopsStartup() {
        AccountPB account = AccountPB.newBuilder()
            .setAccountId("invalid-bootstrap-user").setAccountName("无效初始化用户").build();
        ConfigProtoService protoService = mock(ConfigProtoService.class);
        when(protoService.getConfig()).thenReturn(Config.newBuilder()
            .setUser(UserConfigPB.newBuilder().addAccount(account)).build());
        UserCommand commands = mock(UserCommand.class);
        when(commands.addAccount(account, UserQuery.SYSTEM_ACCOUNT_ID))
            .thenReturn(StatusCode.DEPARTMENT_NOT_FOUND);
        DepartmentRepository departments = mock(DepartmentRepository.class);
        when(departments.count()).thenReturn(1L);

        UserConfig config = new UserConfig(
            mock(ConfigurableApplicationContext.class), protoService, commands,
            mock(RbacPermissionRepository.class), mock(RbacRoleRepository.class),
            mock(RbacRolePermissionRepository.class), mock(RbacRoleRoleRepository.class),
            mock(RbacAccountRepository.class), mock(RbacDepartmentRepository.class),
            mock(RbacDepartmentAccountRoleRepository.class),
            mock(RbacDepartmentAccountPermissionRepository.class), departments,
            new IcisBootstrapProperties());

        assertThatThrownBy(() -> ReflectionTestUtils.invokeMethod(
            config, "initAccountsAndDepartments"))
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("invalid-bootstrap-user")
            .hasMessageContaining("DEPARTMENT_NOT_FOUND");
    }
}
