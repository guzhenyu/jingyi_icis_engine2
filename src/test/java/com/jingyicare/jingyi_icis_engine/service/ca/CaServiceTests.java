package com.jingyicare.jingyi_icis_engine.service.ca;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.Collections;
import java.util.Optional;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.jingyicare.jingyi_icis_engine.entity.users.Account;
import com.jingyicare.jingyi_icis_engine.entity.users.RbacDepartment;
import com.jingyicare.jingyi_icis_engine.proto.IcisConfig.Config;
import com.jingyicare.jingyi_icis_engine.proto.IcisWebApi.RealtimeCaSignImageSourcePB;
import com.jingyicare.jingyi_icis_engine.proto.IcisWebApi.StatusCode;
import com.jingyicare.jingyi_icis_engine.proto.config.IcisSettings.AppSettingsPB;
import com.jingyicare.jingyi_icis_engine.proto.config.IcisText.Text;
import com.jingyicare.jingyi_icis_engine.repository.users.AccountRepository;
import com.jingyicare.jingyi_icis_engine.repository.users.RbacDepartmentRepository;
import com.jingyicare.jingyi_icis_engine.service.ConfigProtoService;
import com.jingyicare.jingyi_icis_engine.service.ca.CaSignImageValidator.ValidatedSignImage;
import com.jingyicare.jingyi_icis_engine.service.ca.client.CigCaClient;
import com.jingyicare.jingyi_icis_engine.service.settings.SettingService;
import com.jingyicare.jingyi_icis_engine.service.users.UserService;

class CaServiceTests {
    @BeforeEach
    void setUp() {
        ConfigProtoService protoService = mock(ConfigProtoService.class);
        when(protoService.getConfig()).thenReturn(Config.newBuilder()
            .setText(Text.newBuilder().addAllStatusCodeMsg(
                Collections.nCopies(StatusCode.LAST_CODE_VALUE, "message")
            ))
            .build());
        userService = mock(UserService.class);
        accountRepo = mock(AccountRepository.class);
        deptRepo = mock(RbacDepartmentRepository.class);
        accessPolicy = mock(CaAccessPolicy.class);
        settingService = mock(SettingService.class);
        cigCaClient = mock(CigCaClient.class);
        validator = mock(CaSignImageValidator.class);
        service = new CaService(
            protoService,
            userService,
            accountRepo,
            deptRepo,
            accessPolicy,
            settingService,
            cigCaClient,
            validator
        );

        Account account = new Account();
        account.setId(7L);
        account.setAccountId("doctor-7");
        account.setIsDisabled(0);
        when(userService.getCtxAccountId()).thenReturn("caller");
        when(deptRepo.findByDeptId("ICU")).thenReturn(Optional.of(mock(RbacDepartment.class)));
        when(accessPolicy.canCurrentUserAccessDept("ICU")).thenReturn(true);
        when(accountRepo.findByIdAndIsDeletedFalse(7L)).thenReturn(Optional.of(account));
        when(accessPolicy.accountBelongsToDept("doctor-7", "ICU")).thenReturn(true);
        when(settingService.getAppSettingsForService("ICU")).thenReturn(
            AppSettingsPB.newBuilder().setEnableCa(true).build()
        );
        when(cigCaClient.isEnabled()).thenReturn(true);
    }

    @Test
    void rejectsDisabledTargetAsAccountNotFoundBeforeCallingCig() {
        Account disabled = new Account();
        disabled.setId(7L);
        disabled.setAccountId("doctor-7");
        disabled.setIsDisabled(1);
        when(accountRepo.findByIdAndIsDeletedFalse(7L)).thenReturn(Optional.of(disabled));

        var response = service.getSignImage("{\"deptId\":\"ICU\",\"accountId\":\"7\"}");

        assertEquals(StatusCode.ACCOUNT_NOT_FOUND_VALUE, response.getRt().getCode());
        verify(cigCaClient, never()).getSignImage(anyLong());
    }

    @Test
    void rejectsCallerOrTargetOutsideDepartmentBeforeCallingCig() {
        when(accessPolicy.canCurrentUserAccessDept("ICU")).thenReturn(false);
        var callerResponse = service.getSignImage("{\"deptId\":\"ICU\",\"accountId\":\"7\"}");
        assertEquals(StatusCode.CA_ACCOUNT_NOT_IN_DEPT_VALUE, callerResponse.getRt().getCode());

        when(accessPolicy.canCurrentUserAccessDept("ICU")).thenReturn(true);
        when(accessPolicy.accountBelongsToDept("doctor-7", "ICU")).thenReturn(false);
        var targetResponse = service.getSignImage("{\"deptId\":\"ICU\",\"accountId\":\"7\"}");
        assertEquals(StatusCode.CA_ACCOUNT_NOT_IN_DEPT_VALUE, targetResponse.getRt().getCode());
        verify(cigCaClient, never()).getSignImage(anyLong());
    }

    @Test
    void respectsTheDepartmentCaSwitch() {
        when(settingService.getAppSettingsForService("ICU")).thenReturn(AppSettingsPB.getDefaultInstance());

        var response = service.getSignImage("{\"deptId\":\"ICU\",\"accountId\":\"7\"}");

        assertEquals(StatusCode.CA_SERVICE_NOT_ENABLED_VALUE, response.getRt().getCode());
        verify(cigCaClient, never()).getSignImage(anyLong());
    }

    @Test
    void returnsTheValidatedBareBase64Image() {
        when(validator.validate(any(), anyLong())).thenReturn(new ValidatedSignImage(
            new byte[] { 1, 2, 3 },
            "image/png",
            "a".repeat(64),
            10,
            4,
            RealtimeCaSignImageSourcePB.REALTIME_CA_SIGN_IMAGE_SOURCE_CA_PROVIDER
        ));

        var response = service.getSignImage("{\"deptId\":\"ICU\",\"accountId\":\"7\"}");

        assertEquals(StatusCode.OK_VALUE, response.getRt().getCode());
        assertEquals("AQID", response.getImageB64());
        assertEquals(7, response.getAccountId());
    }

    private UserService userService;
    private AccountRepository accountRepo;
    private RbacDepartmentRepository deptRepo;
    private CaAccessPolicy accessPolicy;
    private SettingService settingService;
    private CigCaClient cigCaClient;
    private CaSignImageValidator validator;
    private CaService service;
}
