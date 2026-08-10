package com.jingyicare.jingyi_icis_engine.service.settings;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.LocalDateTime;
import java.util.Collections;
import java.util.Optional;

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
import com.jingyicare.jingyi_icis_engine.service.ConfigProtoService;
import com.jingyicare.jingyi_icis_engine.service.medications.MedicationConfig;
import com.jingyicare.jingyi_icis_engine.service.monitorings.MonitoringConfig;
import com.jingyicare.jingyi_icis_engine.service.nursingrecords.NursingRecordConfig;
import com.jingyicare.jingyi_icis_engine.service.scores.ScoreConfig;
import com.jingyicare.jingyi_icis_engine.service.users.UserService;
import com.jingyicare.jingyi_icis_engine.utils.Pair;
import com.jingyicare.jingyi_icis_engine.utils.ProtoUtils;

class SettingServiceTests {
    @BeforeEach
    void setUp() {
        ConfigProtoService protoService = mock(ConfigProtoService.class);
        when(protoService.getConfig()).thenReturn(Config.newBuilder()
            .setText(Text.newBuilder().addAllStatusCodeMsg(
                Collections.nCopies(StatusCode.LAST_CODE_VALUE, "message")
            ))
            .build());
        userService = mock(UserService.class);
        deptSettingsRepo = mock(DeptSystemSettingsRepository.class);
        service = new SettingService(
            protoService,
            userService,
            mock(MedicationConfig.class),
            mock(MonitoringConfig.class),
            mock(NursingRecordConfig.class),
            mock(ScoreConfig.class),
            deptSettingsRepo,
            mock(SystemSettingsRepository.class)
        );
        when(userService.getAccountWithAutoId()).thenReturn(new Pair<>("admin", "1"));
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

    private UserService userService;
    private DeptSystemSettingsRepository deptSettingsRepo;
    private SettingService service;
}
