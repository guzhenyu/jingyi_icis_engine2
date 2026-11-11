package com.jingyicare.jingyi_icis_engine.service.settings;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.time.LocalDateTime;
import java.util.Collections;
import java.util.Optional;
import java.util.function.Supplier;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import com.google.protobuf.util.JsonFormat;
import com.jingyicare.jingyi_icis_engine.entity.settings.DeptSystemSettings;
import com.jingyicare.jingyi_icis_engine.entity.settings.DeptSystemSettingsId;
import com.jingyicare.jingyi_icis_engine.proto.IcisConfig.Config;
import com.jingyicare.jingyi_icis_engine.proto.IcisWebApi.StatusCode;
import com.jingyicare.jingyi_icis_engine.proto.IcisWebApi.UpdateAppSettingsReq;
import com.jingyicare.jingyi_icis_engine.proto.config.IcisSettings.AppGeneralSettingsPB;
import com.jingyicare.jingyi_icis_engine.proto.config.IcisSettings.AppSettingTypeEnum;
import com.jingyicare.jingyi_icis_engine.proto.config.IcisSettings.AppSettingsPB;
import com.jingyicare.jingyi_icis_engine.proto.config.IcisSettings.SystemSettingFunctionId;
import com.jingyicare.jingyi_icis_engine.proto.config.IcisText.Text;
import com.jingyicare.jingyi_icis_engine.repository.settings.DeptSystemSettingsRepository;
import com.jingyicare.jingyi_icis_engine.repository.settings.SystemSettingsRepository;
import com.jingyicare.jingyi_icis_engine.service.ProtoConfig;
import com.jingyicare.jingyi_icis_engine.service.locks.LockExecutor;
import com.jingyicare.jingyi_icis_engine.service.reports.JfkTemplateReader;
import com.jingyicare.jingyi_icis_engine.service.users.UserQuery;
import com.jingyicare.jingyi_icis_engine.utils.Pair;
import com.jingyicare.jingyi_icis_engine.utils.ProtoUtils;

class SettingServiceTests {
    @BeforeEach
    void setUp() {
        ProtoConfig protoConfig = mock(ProtoConfig.class);
        when(protoConfig.getConfig()).thenReturn(Config.newBuilder()
            .setText(Text.newBuilder().addAllStatusCodeMsg(
                Collections.nCopies(StatusCode.LAST_CODE_VALUE, "message")
            ))
            .build());
        userQuery = mock(UserQuery.class);
        deptSettingsRepo = mock(DeptSystemSettingsRepository.class);
        lockExecutor = mock(LockExecutor.class);
        when(lockExecutor.execute(anyCollection(), any())).thenAnswer(invocation -> {
            Supplier<?> action = invocation.getArgument(1);
            return action.get();
        });
        service = new SettingService(
            protoConfig,
            userQuery,
            new SettingsStore(deptSettingsRepo, mock(SystemSettingsRepository.class)),
            mock(JfkTemplateReader.class),
            lockExecutor
        );
        when(userQuery.getAccountWithAutoId()).thenReturn(new Pair<>("admin", "1"));
    }

    @Test
    void unchangedOrUnsupportedSettingsDoNotAcquireEmptyLocks() {
        for (String request : new String[] {"{}", "{\"changedType\":[0,999]}"}) {
            assertThat(service.updateAppSettings(request).getRt().getCode())
                .isEqualTo(StatusCode.OK_VALUE);
        }
        verifyNoInteractions(lockExecutor, deptSettingsRepo);
    }

    @Test
    void rejectsBlankDepartmentBeforeAcquiringSettingsLocks() {
        var response = service.updateAppSettings("{\"changedType\":[7],\"deptId\":\"  \"}");
        assertThat(response.getRt().getCode()).isEqualTo(StatusCode.DEPT_IS_EMPTY_VALUE);
        verifyNoInteractions(lockExecutor, deptSettingsRepo);
    }

    @Test
    void updatingEnableCaPreservesTheOtherGeneralSettings() throws Exception {
        DeptSystemSettingsId id = new DeptSystemSettingsId(
            "ICU", SystemSettingFunctionId.GET_DEPT_APP_SETTINGS_VALUE
        );
        AppGeneralSettingsPB existing = AppGeneralSettingsPB.newBuilder()
            .setJfkUseNativePrint(true)
            .setPrintAgentIpPort("10.0.0.8:9123")
            .setCheckFutureTime(true)
            .setEnableCa(false)
            .build();
        DeptSystemSettings entity = new DeptSystemSettings(
            id, ProtoUtils.encodeAppGeneralSettings(existing), LocalDateTime.now(), "old-user"
        );
        when(deptSettingsRepo.findById(id)).thenReturn(Optional.of(entity));
        when(deptSettingsRepo.save(any())).thenAnswer(invocation -> invocation.getArgument(0));

        UpdateAppSettingsReq request = UpdateAppSettingsReq.newBuilder()
            .setDeptId("ICU")
            .addChangedType(AppSettingTypeEnum.AST_ENABLE_CA)
            .setSettings(AppSettingsPB.newBuilder().setEnableCa(true))
            .build();
        var response = service.updateAppSettings(JsonFormat.printer().print(request));

        assertThat(response.getRt().getCode()).isEqualTo(StatusCode.OK_VALUE);
        ArgumentCaptor<DeptSystemSettings> captor = ArgumentCaptor.forClass(DeptSystemSettings.class);
        verify(deptSettingsRepo).save(captor.capture());
        AppGeneralSettingsPB updated = ProtoUtils.decodeAppGeneralSettings(captor.getValue().getSettingsPb());
        assertThat(updated.getEnableCa()).isTrue();
        assertThat(updated.getJfkUseNativePrint()).isTrue();
        assertThat(updated.getPrintAgentIpPort()).isEqualTo("10.0.0.8:9123");
        assertThat(updated.getCheckFutureTime()).isTrue();
    }

    private UserQuery userQuery;
    private DeptSystemSettingsRepository deptSettingsRepo;
    private LockExecutor lockExecutor;
    private SettingService service;
}
