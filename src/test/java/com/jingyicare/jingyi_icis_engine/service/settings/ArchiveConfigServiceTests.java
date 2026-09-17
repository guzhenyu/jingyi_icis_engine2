package com.jingyicare.jingyi_icis_engine.service.settings;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

import java.time.LocalDateTime;
import java.util.Collections;
import java.util.Optional;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;

import com.jingyicare.jingyi_icis_engine.entity.reports.DragableFormTemplate;
import com.jingyicare.jingyi_icis_engine.entity.settings.DeptSystemSettings;
import com.jingyicare.jingyi_icis_engine.entity.settings.DeptSystemSettingsId;
import com.jingyicare.jingyi_icis_engine.proto.IcisConfig.Config;
import com.jingyicare.jingyi_icis_engine.proto.IcisWebApi.*;
import com.jingyicare.jingyi_icis_engine.proto.config.IcisJfk.*;
import com.jingyicare.jingyi_icis_engine.proto.config.IcisSettings.*;
import com.jingyicare.jingyi_icis_engine.proto.config.IcisText.Text;
import com.jingyicare.jingyi_icis_engine.proto.shared.Shared.GenericResp;
import com.jingyicare.jingyi_icis_engine.repository.reports.DragableFormTemplateRepository;
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

class ArchiveConfigServiceTests {
    private final DeptSystemSettingsRepository settingsRepo = mock(DeptSystemSettingsRepository.class);
    private final DragableFormTemplateRepository templates = mock(DragableFormTemplateRepository.class);
    private final UserService users = mock(UserService.class);
    private final DeptSystemSettingsId settingsId = new DeptSystemSettingsId("ICU", 14);
    private SettingService service;

    @BeforeEach
    void setUp() {
        ConfigProtoService config = mock(ConfigProtoService.class);
        when(config.getConfig()).thenReturn(Config.newBuilder().setText(Text.newBuilder()
            .addAllStatusCodeMsg(Collections.nCopies(StatusCode.LAST_CODE_VALUE, "message"))).build());
        service = new SettingService(config, users, mock(MedicationConfig.class), mock(MonitoringConfig.class),
            mock(NursingRecordConfig.class), mock(ScoreConfig.class), settingsRepo,
            mock(SystemSettingsRepository.class), templates);
        when(users.getAccountWithAutoId()).thenReturn(new Pair<>("admin", "1"));
    }

    @Test
    void unconfiguredDepartmentReturnsEmptySettingsWithoutNameFallbackOrWrites() {
        var response = get("ICU");
        assertThat(response.getRt().getCode()).isEqualTo(StatusCode.OK_VALUE);
        assertThat(response.getSettings().getDeptId()).isEqualTo("ICU");
        assertThat(response.getSettings().getWardReportTemplateId()).isZero();
        assertThat(response.hasWardReportTemplate()).isFalse();
        verifyNoInteractions(templates);
        verify(settingsRepo, never()).save(any());
    }

    @Test
    void savesConfigAtFunction14AndReadsLatestTemplateById() {
        DragableFormTemplate template = template(42, "ICU");
        when(templates.findByIdAndIsDeletedFalse(42)).thenReturn(Optional.of(template));
        var response = update("ICU", 42);
        assertThat(response.getRt().getCode()).isEqualTo(StatusCode.OK_VALUE);
        ArgumentCaptor<DeptSystemSettings> captor = ArgumentCaptor.forClass(DeptSystemSettings.class);
        verify(settingsRepo).save(captor.capture());
        DeptSystemSettings saved = captor.getValue();
        assertThat(saved.getId()).isEqualTo(settingsId);
        assertThat(saved.getModifiedBy()).isEqualTo("admin");
        assertThat(saved.getModifiedAt()).isNotNull();
        assertThat(ProtoUtils.decodeDeptArchiveSettings(saved.getSettingsPb())).isEqualTo(settings("ICU", 42));

        when(settingsRepo.findById(settingsId)).thenReturn(Optional.of(saved));
        // 数据库里的ID、科室、名字优先于模板内部的历史元数据，改名不影响绑定。
        template.setName("新的交班报告名称");
        var loaded = get("ICU");
        assertThat(loaded.getRt().getCode()).isEqualTo(StatusCode.OK_VALUE);
        assertThat(loaded.getWardReportTemplate().getId()).isEqualTo(42);
        assertThat(loaded.getWardReportTemplate().getDeptId()).isEqualTo("ICU");
        assertThat(loaded.getWardReportTemplate().getName()).isEqualTo("新的交班报告名称");
    }

    @Test
    void updatesExistingRowAndKeepsOtherDepartmentsSeparate() {
        var previous = stored(settings("ICU", 41));
        previous.setModifiedBy("previous-user");
        previous.setModifiedAt(LocalDateTime.of(2020, 1, 1, 0, 0));
        when(settingsRepo.findById(settingsId)).thenReturn(Optional.of(previous));
        when(templates.findByIdAndIsDeletedFalse(42)).thenReturn(Optional.of(template(42, "ICU")));
        assertThat(update("ICU", 42).getRt().getCode()).isEqualTo(StatusCode.OK_VALUE);
        verify(settingsRepo).save(previous);
        assertThat(previous.getModifiedBy()).isEqualTo("admin");
        assertThat(previous.getModifiedAt()).isAfter(LocalDateTime.of(2020, 1, 1, 0, 0));
        assertThat(get("OTHER").getSettings().getWardReportTemplateId()).isZero();
        verify(settingsRepo, never()).findById(new DeptSystemSettingsId("ICU", 11));
    }

    @Test
    void rejectsCrossDepartmentTemplateOnReadAndSave() {
        when(templates.findByIdAndIsDeletedFalse(42)).thenReturn(Optional.of(template(42, "OTHER")));
        when(settingsRepo.findById(settingsId)).thenReturn(Optional.of(stored(settings("ICU", 42))));
        assertThat(update("ICU", 42).getRt().getCode()).isEqualTo(StatusCode.FORM_TEMPLATE_NOT_BELONG_TO_DEPT_VALUE);
        var read = get("ICU");
        assertThat(read.getRt().getCode()).isEqualTo(StatusCode.FORM_TEMPLATE_NOT_BELONG_TO_DEPT_VALUE);
        assertThat(read.getSettings().getWardReportTemplateId()).isEqualTo(42);
        assertThat(read.hasWardReportTemplate()).isFalse();
        verify(settingsRepo, never()).save(any());
    }

    @Test
    void missingOrDeletedTemplateRetainsConfigForRepairButCannotRenderOrSave() {
        when(settingsRepo.findById(settingsId)).thenReturn(Optional.of(stored(settings("ICU", 42))));
        assertThat(update("ICU", 42).getRt().getCode()).isEqualTo(StatusCode.FORM_TEMPLATE_NOT_FOUND_VALUE);
        var read = get("ICU");
        assertThat(read.getRt().getCode()).isEqualTo(StatusCode.FORM_TEMPLATE_NOT_FOUND_VALUE);
        assertThat(read.getSettings().getWardReportTemplateId()).isEqualTo(42);
        assertThat(read.hasWardReportTemplate()).isFalse();
        verify(settingsRepo, never()).save(any());
    }

    @ParameterizedTest
    @ValueSource(strings = {"not-base64!", ""})
    void rejectsUnreadableOrPagelessTemplate(String templatePb) {
        var template = template(42, "ICU");
        template.setTemplatePb(templatePb);
        when(templates.findByIdAndIsDeletedFalse(42)).thenReturn(Optional.of(template));
        assertThat(update("ICU", 42).getRt().getCode()).isEqualTo(StatusCode.WARD_REPORT_TEMPLATE_INVALID_VALUE);
        when(settingsRepo.findById(settingsId)).thenReturn(Optional.of(stored(settings("ICU", 42))));
        assertThat(get("ICU").hasWardReportTemplate()).isFalse();
        verify(settingsRepo, never()).save(any());
    }

    @ParameterizedTest
    @ValueSource(ints = {0, -1})
    void rejectsUnsetOrNegativeTemplateId(int templateId) {
        assertThat(update("ICU", templateId).getRt().getCode()).isEqualTo(StatusCode.WARD_REPORT_TEMPLATE_REQUIRED_VALUE);
        verifyNoInteractions(templates);
        verify(settingsRepo, never()).save(any());
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "  "})
    void rejectsBlankDepartment(String deptId) {
        assertThat(get(deptId).getRt().getCode()).isEqualTo(StatusCode.DEPT_IS_EMPTY_VALUE);
        assertThat(update(deptId, 42).getRt().getCode()).isEqualTo(StatusCode.DEPT_IS_EMPTY_VALUE);
        verifyNoInteractions(templates, settingsRepo);
    }

    @Test
    void rejectsInvalidPayloadAndMissingLogin() {
        assertThat(service.getArchiveConfig("{").getRt().getCode()).isEqualTo(StatusCode.PARSE_JSON_FAILED_VALUE);
        assertThat(service.updateArchiveConfig("{").getRt().getCode()).isEqualTo(StatusCode.PARSE_JSON_FAILED_VALUE);
        assertThat(service.updateArchiveConfig("{}").getRt().getCode()).isEqualTo(StatusCode.ARCHIVE_CONFIG_INVALID_VALUE);
        when(users.getAccountWithAutoId()).thenReturn(null);
        assertThat(update("ICU", 42).getRt().getCode()).isEqualTo(StatusCode.ACCOUNT_NOT_FOUND_VALUE);
        verifyNoInteractions(templates, settingsRepo);
    }

    @Test
    void rejectsCorruptConfigAndStoredDepartmentMismatch() {
        var corrupt = stored(settings("ICU", 42));
        corrupt.setSettingsPb("broken!");
        when(settingsRepo.findById(settingsId)).thenReturn(Optional.of(corrupt));
        assertThat(get("ICU").getRt().getCode()).isEqualTo(StatusCode.ARCHIVE_CONFIG_INVALID_VALUE);
        corrupt.setSettingsPb(ProtoUtils.encodeDeptArchiveSettings(settings("OTHER", 42)));
        assertThat(get("ICU").getRt().getCode()).isEqualTo(StatusCode.ARCHIVE_CONFIG_INVALID_VALUE);
        verifyNoInteractions(templates);
    }

    private GetArchiveConfigResp get(String deptId) {
        return service.getArchiveConfig(ProtoUtils.protoToJson(GetArchiveConfigReq.newBuilder().setDeptId(deptId).build()));
    }

    private GenericResp update(String deptId, int templateId) {
        return service.updateArchiveConfig(ProtoUtils.protoToJson(UpdateArchiveConfigReq.newBuilder()
            .setSettings(settings(deptId, templateId)).build()));
    }

    private DeptArchiveSettings settings(String deptId, int templateId) {
        return DeptArchiveSettings.newBuilder().setDeptId(deptId).setWardReportTemplateId(templateId).build();
    }

    private DeptSystemSettings stored(DeptArchiveSettings settings) {
        return new DeptSystemSettings(settingsId, ProtoUtils.encodeDeptArchiveSettings(settings), LocalDateTime.now(), "admin");
    }

    private DragableFormTemplate template(int id, String deptId) {
        var pb = JfkTemplatePB.newBuilder().setId(999).setDeptId("stale-dept").setName("旧模板名称")
            .addPages(JfkPagePB.newBuilder()).build();
        return DragableFormTemplate.builder().id(id).deptId(deptId).name("病室报告模板A")
            .templatePb(ProtoUtils.encodeJfkTemplate(pb)).isDeleted(false).build();
    }
}
