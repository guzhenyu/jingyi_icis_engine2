package com.jingyicare.jingyi_icis_engine.utils;

import java.io.IOException;
import java.io.StringWriter;
import java.util.Base64;
import java.util.HashSet;
import java.util.Set;

import lombok.*;
import lombok.extern.slf4j.Slf4j;

import com.google.protobuf.Descriptors;
import com.google.protobuf.Message;
import com.google.protobuf.MessageOrBuilder;
import com.google.protobuf.TextFormat;
import com.google.protobuf.util.JsonFormat;

import com.jingyicare.jingyi_icis_engine.proto.IcisWebApi.*;
import com.jingyicare.jingyi_icis_engine.proto.config.IcisBga.*;
import com.jingyicare.jingyi_icis_engine.proto.config.IcisDevice.*;
import com.jingyicare.jingyi_icis_engine.proto.config.IcisDiagnosis.*;
import com.jingyicare.jingyi_icis_engine.proto.config.IcisDoctorScore.*;
import com.jingyicare.jingyi_icis_engine.proto.config.IcisJfk.*;
import com.jingyicare.jingyi_icis_engine.proto.config.IcisLis.*;
import com.jingyicare.jingyi_icis_engine.proto.config.IcisMedication.*;
import com.jingyicare.jingyi_icis_engine.proto.config.IcisMonitoring.*;
import com.jingyicare.jingyi_icis_engine.proto.config.IcisMonitoringReportAh2.*;
import com.jingyicare.jingyi_icis_engine.proto.config.IcisNursingOrder.*;
import com.jingyicare.jingyi_icis_engine.proto.config.IcisNursingRecord.*;
import com.jingyicare.jingyi_icis_engine.proto.config.IcisNursingScore.*;
import com.jingyicare.jingyi_icis_engine.proto.config.IcisPatient.*;
import com.jingyicare.jingyi_icis_engine.proto.config.IcisSettings.*;
import com.jingyicare.jingyi_icis_engine.proto.config.IcisShift.*;
import com.jingyicare.jingyi_icis_engine.proto.config.IcisTube.*;
import com.jingyicare.jingyi_icis_engine.proto.config.IcisUrl.*;
import com.jingyicare.jingyi_icis_engine.proto.config.IcisUser.*;
import com.jingyicare.jingyi_icis_engine.proto.shared.Shared.*;
import com.jingyicare.jingyi_icis_engine.proto.shared.ValueMeta.*;
import com.jingyicare.jingyi_icis_engine.proto.shared.Shared.EnumValue;

@Slf4j
public class ProtoUtils {
    public static String protoToJson(MessageOrBuilder msg) {
        try {
            return jsonPrinter.print(msg);
        } catch (Exception e) {
            log.error("Failed to convert proto to string: ", e, "\n", e.getStackTrace());
            return null;
        }
    }

    public static String protoToTxt(MessageOrBuilder msg) {
        if (msg == null) {
            return "";
        }

        String resultStr = "";
        try {
            StringWriter writer = new StringWriter();
            TextFormat.printer().print(msg, writer);
            resultStr = writer.toString();
        } catch (Exception e) {
            log.error("Failed to convert proto to text format: ", e, "\n", e.getStackTrace());
            return "";
        }

        return resultStr;
    }

    @SuppressWarnings("unchecked")
    public static <T extends com.google.protobuf.Message> T parseJsonToProto(String json, com.google.protobuf.Message.Builder builder) throws IOException {
        JsonFormat.parser().merge(json, builder);
        return (T) builder.build();
    }

    static public ValueMetaPB decodeValueMeta(String base64) {
        if (base64 == null || base64.isEmpty()) return null;

        try {
            return ValueMetaPB.parseFrom(Base64.getDecoder().decode(base64));
        } catch (Exception e) {
            log.error("Failed to decode ValueMetaPB from base64 string ", e);
            return null;
        }
    }

    static public String encodeValueMeta(ValueMetaPB pb) {
        return Base64.getEncoder().encodeToString(pb.toByteArray());
    }

    static public GenericValuePB decodeGenericValue(String base64) {
        try {
            return GenericValuePB.parseFrom(Base64.getDecoder().decode(base64));
        } catch (Exception e) {
            log.error("Failed to decode GenericValuePB from base64 string ", e);
            return null;
        }
    }

    static public String encodeGenericValue(GenericValuePB pb) {
        return Base64.getEncoder().encodeToString(pb.toByteArray());
    }

    static public MonitoringValuePB decodeMonitoringValue(String base64) {
        try {
            return MonitoringValuePB.parseFrom(Base64.getDecoder().decode(base64));
        } catch (Exception e) {
            log.error("Failed to decode MonitoringValuePB from base64 string ", e);
            return null;
        }
    }

    static public String encodeMonitoringValue(MonitoringValuePB pb) {
        return Base64.getEncoder().encodeToString(pb.toByteArray());
    }

    static public String encodeScoreGroupPB(ScoreGroupPB pb) {
        return Base64.getEncoder().encodeToString(pb.toByteArray());
    }

    static public ScoreGroupPB decodeScoreGroupPB(String base64) {
        try {
            return ScoreGroupPB.parseFrom(Base64.getDecoder().decode(base64));
        } catch (Exception e) {
            log.error("Failed to decode ScoreGroupPB from base64 string ", e);
            return null;
        }
    }

    static public String encodeScoreGroupMetaPB(ScoreGroupMetaPB pb) {
        return Base64.getEncoder().encodeToString(pb.toByteArray());
    }

    static public ScoreGroupMetaPB decodeScoreGroupMetaPB(String base64) {
        try {
            return ScoreGroupMetaPB.parseFrom(Base64.getDecoder().decode(base64));
        } catch (Exception e) {
            log.error("Failed to decode ScoreGroupMetaPB from base64 string ", e);
            return null;
        }
    }

    public static ShiftSettingsPB decodeShiftSettings(String data) {
        try {
            byte[] decodedData = Base64.getDecoder().decode(data);
            return ShiftSettingsPB.parseFrom(decodedData);
        } catch (Exception e) {
            log.error("Failed to parse shiftSettings", e);
            return null;
        }
    }

    public static String encodeShiftSettings(ShiftSettingsPB shiftSettings) {
        return Base64.getEncoder().encodeToString(shiftSettings.toByteArray());
    }

    public static MedOrderGroupSettingsPB decodeMedOrderGroupSettings(String data) {
        try {
            byte[] decodedData = Base64.getDecoder().decode(data);
            return MedOrderGroupSettingsPB.parseFrom(decodedData);
        } catch (Exception e) {
            log.error("Failed to parse MedOrderGroupSettingsPB", e);
            return null;
        }
    }

    public static String encodeMedOrderGroupSettings(MedOrderGroupSettingsPB medOrderGroupSettings) {
        return Base64.getEncoder().encodeToString(medOrderGroupSettings.toByteArray());
    }

    static public JfkTemplatePB decodeJfkTemplate(String base64) {
        try {
            return JfkTemplatePB.parseFrom(Base64.getDecoder().decode(base64));
        } catch (Exception e) {
            log.error("Failed to decode JfkTemplatePB from base64 string ", e);
            return null;
        }
    }

    static public String encodeJfkTemplate(JfkTemplatePB pb) {
        return Base64.getEncoder().encodeToString(pb.toByteArray());
    }

    static public JfkFormInstancePB decodeJfkFormInstance(String base64) {
        try {
            return JfkFormInstancePB.parseFrom(Base64.getDecoder().decode(base64));
        } catch (Exception e) {
            log.error("Failed to decode JfkFormInstancePB from base64 string ", e);
            return null;
        }
    }

    static public String encodeJfkFormInstance(JfkFormInstancePB pb) {
        return Base64.getEncoder().encodeToString(pb.toByteArray());
    }

    static public DiseaseMetaPB decodeDiseaseMeta(String base64) {
        try {
            return DiseaseMetaPB.parseFrom(Base64.getDecoder().decode(base64));
        } catch (Exception e) {
            log.error("Failed to decode DiseaseMetaPB from base64 string ", e);
            return null;
        }
    }

    static public String encodeDiseaseMeta(DiseaseMetaPB pb) {
        return Base64.getEncoder().encodeToString(pb.toByteArray());
    }

    static public DiseasePB decodeDisease(String base64) {
        try {
            return DiseasePB.parseFrom(Base64.getDecoder().decode(base64));
        } catch (Exception e) {
            log.error("Failed to decode DiseasePB from base64 string ", e);
            return null;
        }
    }

    static public String encodeDisease(DiseasePB pb) {
        return Base64.getEncoder().encodeToString(pb.toByteArray());
    }

    static public MedicationFrequencySpec decodeFreqSpec(String base64) {
        try {
            return MedicationFrequencySpec.parseFrom(Base64.getDecoder().decode(base64));
        } catch (Exception e) {
            log.error("Failed to decode MedicationFrequencySpec from base64 string ", e);
            return null;
        }
    }

    static public String encodeFreqSpec(MedicationFrequencySpec pb) {
        return Base64.getEncoder().encodeToString(pb.toByteArray());
    }

    static public MenuConfigPB decodeMenuConfig(String base64) {
        try {
            return MenuConfigPB.parseFrom(Base64.getDecoder().decode(base64));
        } catch (Exception e) {
            log.error("Failed to decode MenuConfigPB from base64 string ", e);
            return null;
        }
    }

    static public String encodeMenuConfig(MenuConfigPB pb) {
        return Base64.getEncoder().encodeToString(pb.toByteArray());
    }

    static public MonitoringModalOptionPB decodeMonitoringModalOption(String base64) {
        try {
            return MonitoringModalOptionPB.parseFrom(Base64.getDecoder().decode(base64));
        } catch (Exception e) {
            log.error("Failed to decode MonitoringModalOptionPB from base64 string ", e);
            return null;
        }
    }

    static public String encodeMonitoringModalOption(MonitoringModalOptionPB pb) {
        return Base64.getEncoder().encodeToString(pb.toByteArray());
    }

    static public DeptMonitoringSettingsPB decodeDeptMonitoringSettings(String base64) {
        try {
            return DeptMonitoringSettingsPB.parseFrom(Base64.getDecoder().decode(base64));
        } catch (Exception e) {
            log.error("Failed to decode DeptMonitoringSettingsPB from base64 string ", e);
            return null;
        }
    }

    static public String encodeDeptMonitoringSettings(DeptMonitoringSettingsPB pb) {
        return Base64.getEncoder().encodeToString(pb.toByteArray());
    }

    static public NursingRecordSettingsPB decodeNursingRecordSettings(String base64) {
        try {
            return NursingRecordSettingsPB.parseFrom(Base64.getDecoder().decode(base64));
        } catch (Exception e) {
            log.error("Failed to decode NursingRecordSettingsPB from base64 string ", e);
            return null;
        }
    }

    static public String encodeNursingRecordSettings(NursingRecordSettingsPB pb) {
        return Base64.getEncoder().encodeToString(pb.toByteArray());
    }

    static public ExtUrlPB decodeExtUrl(String base64) {
        try {
            return ExtUrlPB.parseFrom(Base64.getDecoder().decode(base64));
        } catch (Exception e) {
            log.error("Failed to decode ExtUrlPB from base64 string ", e);
            return null;
        }
    }

    static public String encodeExtUrl(ExtUrlPB pb) {
        return Base64.getEncoder().encodeToString(pb.toByteArray());
    }

    static public ScoreSettingsPB decodeScoreSettings(String base64) {
        try {
            return ScoreSettingsPB.parseFrom(Base64.getDecoder().decode(base64));
        } catch (Exception e) {
            log.error("Failed to decode ScoreSettingsPB from base64 string ", e);
            return null;
        }
    }

    static public String encodeScoreSettings(ScoreSettingsPB pb) {
        return Base64.getEncoder().encodeToString(pb.toByteArray());
    }

    static public AppGeneralSettingsPB decodeAppGeneralSettings(String base64) {
        try {
            return AppGeneralSettingsPB.parseFrom(Base64.getDecoder().decode(base64));
        } catch (Exception e) {
            log.error("Failed to decode AppGeneralSettingsPB from base64 string ", e);
            return null;
        }
    }

    static public String encodeAppGeneralSettings(AppGeneralSettingsPB pb) {
        return Base64.getEncoder().encodeToString(pb.toByteArray());
    }

    static public MedicalOrderIdsPB decodeOrderIds(String base64) {
        try {
            return MedicalOrderIdsPB.parseFrom(Base64.getDecoder().decode(base64));
        } catch (Exception e) {
            log.error("Failed to decode MedicalOrderIdsPB from base64 string ", e);
            return null;
        }
    }

    static public String encodeOrderIds(MedicalOrderIdsPB pb) {
        return Base64.getEncoder().encodeToString(pb.toByteArray());
    }

    static public MedicationDosageGroupPB decodeDosageGroup(String base64) {
        try {
            return MedicationDosageGroupPB.parseFrom(Base64.getDecoder().decode(base64));
        } catch (Exception e) {
            log.error("Failed to decode MedicationDosageGroupPB from base64 string ", e);
            return null;
        }
    }

    static public String encodeDosageGroup(MedicationDosageGroupPB pb) {
        return Base64.getEncoder().encodeToString(pb.toByteArray());
    }

    static public DosageGroupExtPB decodeDosageGroupExt(String base64) {
        try {
            return DosageGroupExtPB.parseFrom(Base64.getDecoder().decode(base64));
        } catch (Exception e) {
            log.error("Failed to decode DosageGroupExtPB from base64 string ", e);
            return null;
        }
    }

    static public String encodeDosageGroupExt(DosageGroupExtPB pb) {
        return Base64.getEncoder().encodeToString(pb.toByteArray());
    }

    static public Ah2PageDataPB decodeAh2PageData(String base64) {
        try {
            return Ah2PageDataPB.parseFrom(Base64.getDecoder().decode(base64));
        } catch (Exception e) {
            log.error("Failed to decode Ah2PageDataPB from base64 string ", e);
            return null;
        }
    }

    static public String encodeAh2PageData(Ah2PageDataPB pb) {
        return Base64.getEncoder().encodeToString(pb.toByteArray());
    }

    static public WardReportPB decodeWardReport(String base64) {
        try {
            return WardReportPB.parseFrom(Base64.getDecoder().decode(base64));
        } catch (Exception e) {
            log.error("Failed to decode WardReportPB from base64 string ", e);
            return null;
        }
    }

    static public String encodeWardReport(WardReportPB pb) {
        return Base64.getEncoder().encodeToString(pb.toByteArray());
    }

    private static final Set<Descriptors.FieldDescriptor> fields = new HashSet<>();
    private static final JsonFormat.Printer jsonPrinter;
    static {
        // ReturnCode.code
        fields.add(ReturnCode.getDescriptor().findFieldByNumber(1));
        // GetUsernamePB.username
        fields.add(GetUsernameResp.getDescriptor().findFieldByNumber(2));
        // EnumValue.id
        fields.add(EnumValue.getDescriptor().findFieldByNumber(1));
        // GetPatientInfoResp
        //     .icu_gender
        //     .admission_status
        //     .admission_type
        //     .is_planned_admission
        //     .unplanned_admission_reason
        //     .discharged_type
        //     .discharged_dept_name
        fields.add(GetPatientInfoResp.getDescriptor().findFieldByNumber(5));
        fields.add(GetPatientInfoResp.getDescriptor().findFieldByNumber(9));
        fields.add(GetPatientInfoResp.getDescriptor().findFieldByNumber(11));
        fields.add(GetPatientInfoResp.getDescriptor().findFieldByNumber(12));
        fields.add(GetPatientInfoResp.getDescriptor().findFieldByNumber(13));
        fields.add(GetPatientInfoResp.getDescriptor().findFieldByNumber(16));
        fields.add(GetPatientInfoResp.getDescriptor().findFieldByNumber(17));

        // OrderGroupPB
        //     route_group_id
        fields.add(OrderGroupPB.getDescriptor().findFieldByNumber(1));

        // FluidIntakePB
        //     .total_ml
        fields.add(FluidIntakePB.getDescriptor().findFieldByNumber(1));

        // FluidIntakePB.IntakeRecord
        //     .ml
        fields.add(FluidIntakePB.IntakeRecord.getDescriptor().findFieldByNumber(1));

        // MedicationExecutionActionPB
        //     .action_type
        fields.add(MedicationExecutionActionPB.getDescriptor().findFieldByNumber(6));

        // MedicationOrderGroupPB
        //     .order_duration_type - 18
        fields.add(MedicationOrderGroupPB.getDescriptor().findFieldByNumber(18));

        // TubeOrder
        //    .display_order
        fields.add(TubeOrder.getDescriptor().findFieldByNumber(2));

        // TubeTypePB
        //    .is_common - 5
        //    .is_disabled - 6
        //    .display_order - 7
        fields.add(TubeTypePB.getDescriptor().findFieldByNumber(5));
        fields.add(TubeTypePB.getDescriptor().findFieldByNumber(6));
        fields.add(TubeTypePB.getDescriptor().findFieldByNumber(7));

        // TubeValueSpecPB
        //    .opt_value - 5
        //    .default_value - 6
        //    .display_order - 8
        fields.add(TubeValueSpecPB.getDescriptor().findFieldByNumber(5));
        fields.add(TubeValueSpecPB.getDescriptor().findFieldByNumber(6));
        fields.add(TubeValueSpecPB.getDescriptor().findFieldByNumber(8));

        // GetPatientTubeRecordsReq
        //    .is_deleted
        fields.add(GetPatientTubeRecordsReq.getDescriptor().findFieldByNumber(2));

        // PatientTubeRecordPB
        //    .is_retained_on_discharge - 9
        //    .shift_data_filled - 14
        fields.add(PatientTubeRecordPB.getDescriptor().findFieldByNumber(10));
        fields.add(PatientTubeRecordPB.getDescriptor().findFieldByNumber(16));

        // ValueMetaPB
        //    .upper_limit = 8
        //    .lower_limit = 9
        //    .decimal_places = 10
        //    .unit = 11
        fields.add(ValueMetaPB.getDescriptor().findFieldByNumber(8));
        fields.add(ValueMetaPB.getDescriptor().findFieldByNumber(9));
        fields.add(ValueMetaPB.getDescriptor().findFieldByNumber(10));
        fields.add(ValueMetaPB.getDescriptor().findFieldByNumber(11));

        // GenericValuePB
        //    .bool_val = 1
        //    .int32_val = 2
        //    .float_val = 4
        //    .double_val = 5
        fields.add(GenericValuePB.getDescriptor().findFieldByNumber(1));
        fields.add(GenericValuePB.getDescriptor().findFieldByNumber(2));
        fields.add(GenericValuePB.getDescriptor().findFieldByNumber(4));
        fields.add(GenericValuePB.getDescriptor().findFieldByNumber(5));

        // AdministrationRoutePB
        //    .is_continuous = 3
        //    .group_id = 4
        //    .intake_type_id = 5
        fields.add(AdministrationRoutePB.getDescriptor().findFieldByNumber(3));
        fields.add(AdministrationRoutePB.getDescriptor().findFieldByNumber(4));
        fields.add(AdministrationRoutePB.getDescriptor().findFieldByNumber(5));

        // ScoreItemMetaPB
        //    .score_item_type = 5
        //    .score_item_aggregator = 6
        fields.add(ScoreItemMetaPB.getDescriptor().findFieldByNumber(5));
        fields.add(ScoreItemMetaPB.getDescriptor().findFieldByNumber(6));

        // ScoreGroupMetaPB
        //    .score_group_aggregator = 9
        fields.add(ScoreGroupMetaPB.getDescriptor().findFieldByNumber(9));

        // LookupMedicationResp
        //    .medication = 2
        fields.add(LookupMedicationResp.getDescriptor().findFieldByNumber(2));

        // LookupRouteResp
        //    .route = 2
        fields.add(LookupRouteResp.getDescriptor().findFieldByNumber(2));

        // NursingOrderPB
        //    .duration_type = 4
        fields.add(NursingOrderPB.getDescriptor().findFieldByNumber(4));

        // NursingOrderTemplatePB
        //    .duration_type = 4
        fields.add(NursingOrderTemplatePB.getDescriptor().findFieldByNumber(4));

        // PatientShiftRecordExistsResp
        //    .exists = 2
        fields.add(PatientShiftRecordExistsResp.getDescriptor().findFieldByNumber(2));

        // DeviceInfoWithBindingPB
        //    .is_binding = 2
        fields.add(DeviceInfoWithBindingPB.getDescriptor().findFieldByNumber(2));

        // GetDeviceBindingHistoryResp
        //    .is_binding = 2
        fields.add(GetDeviceBindingHistoryResp.getDescriptor().findFieldByNumber(2));

        // TubeViewPB
        //    .is_removed = 8
        fields.add(TubeViewPB.getDescriptor().findFieldByNumber(7));

        // DailyBalancePB
        //    .intake_ml = 2
        //    .output_ml = 4
        //    .balance_ml = 6
        fields.add(DailyBalancePB.getDescriptor().findFieldByNumber(2));
        fields.add(DailyBalancePB.getDescriptor().findFieldByNumber(4));
        fields.add(DailyBalancePB.getDescriptor().findFieldByNumber(6));

        // PatientBgaRecordPB
        //     .bga_result = 9
        fields.add(PatientBgaRecordPB.getDescriptor().findFieldByNumber(9));

        // DoctorScoreFactorPB
        //     .score = 4
        fields.add(DoctorScoreFactorPB.getDescriptor().findFieldByNumber(4));

        // ApacheIIScorePB
        //     .aps_score = 2
        //     .age = 3
        //     .age_score = 4
        //     .chc_score = 6
        //     .apache_ii_score = 7
        //     .predicted_mortality_rate = 11
        fields.add(ApacheIIScorePB.getDescriptor().findFieldByNumber(2));
        fields.add(ApacheIIScorePB.getDescriptor().findFieldByNumber(3));
        fields.add(ApacheIIScorePB.getDescriptor().findFieldByNumber(4));
        fields.add(ApacheIIScorePB.getDescriptor().findFieldByNumber(6));
        fields.add(ApacheIIScorePB.getDescriptor().findFieldByNumber(7));
        fields.add(ApacheIIScorePB.getDescriptor().findFieldByNumber(11));

        // LisParamPB
        //     .external_param_code = 4
        fields.add(LisParamPB.getDescriptor().findFieldByNumber(4));

        // MedicationExecutionRecordPB
        //     .is_personal_medications = 15
        //     .is_continuous = 19
        //     .should_calculate_rate = 24
        fields.add(MedicationExecutionRecordPB.getDescriptor().findFieldByNumber(15));
        fields.add(MedicationExecutionRecordPB.getDescriptor().findFieldByNumber(19));
        fields.add(MedicationExecutionRecordPB.getDescriptor().findFieldByNumber(24));

        // AdministrationRoutePB
        //    .is_continuous = 3
        fields.add(AdministrationRoutePB.getDescriptor().findFieldByNumber(3));

        // BedConfigPB
        //    .pid = 8
        fields.add(BedConfigPB.getDescriptor().findFieldByNumber(8));

        // IntakeTypePB
        //    .id = 1
        //    .name = 2
        //    .monitoring_param_code = 3;
        fields.add(IntakeTypePB.getDescriptor().findFieldByNumber(1));
        fields.add(IntakeTypePB.getDescriptor().findFieldByNumber(2));
        fields.add(IntakeTypePB.getDescriptor().findFieldByNumber(3));

        // MenuItem
        //    .type = 6
        fields.add(MenuItem.getDescriptor().findFieldByNumber(6));

        // GetMonitoringReportResp
        //    .rotation_degree = 3
        fields.add(GetMonitoringReportResp.getDescriptor().findFieldByNumber(3));

        // GetPatientScoreResp
        //    .found = 2
        //    .score_group_meta = 3
        fields.add(GetPatientScoreResp.getDescriptor().findFieldByNumber(2));
        fields.add(GetPatientScoreResp.getDescriptor().findFieldByNumber(3));

        // IcisAccountPB
        //    .gender = 4
        fields.add(IcisAccountPB.getDescriptor().findFieldByNumber(4));

        // AppSettingsPB 全部都要
        for (int i = 1; i < 10; ++i) {
            fields.add(AppSettingsPB.getDescriptor().findFieldByNumber(i));
        }

        // BalanceParamRecordPB
        //    .sum_ml = 3
        fields.add(BalanceParamRecordPB.getDescriptor().findFieldByNumber(3));

        // BalanceGroupSummaryPB
        //    .sum_ml = 3
        fields.add(BalanceGroupSummaryPB.getDescriptor().findFieldByNumber(3));

        // GetAllExtUrlsResp
        //    .url = 2
        fields.add(GetAllExtUrlsResp.getDescriptor().findFieldByNumber(2));

        // MedicationFrequencySpec.ByInterval
        //    .day_of_week = 1
        fields.add(MedicationFrequencySpec.ByInterval.getDescriptor().findFieldByNumber(1));

        // MedicationPB
        //    .should_calculate_rate = 8
        fields.add(MedicationPB.getDescriptor().findFieldByNumber(8));

        // MedicationDosagePB
        //    .should_calculate_rate = 8
        fields.add(MedicationDosagePB.getDescriptor().findFieldByNumber(8));

        // PatientInfoPB
        //    .icu_gender = 8
        fields.add(PatientInfoPB.getDescriptor().findFieldByNumber(8));

        // PatientBasicsPB
        //    .gender = 3
        fields.add(PatientBasicsPB.getDescriptor().findFieldByNumber(3));

        // JfkValMetaPB
        //    .val_type = 1
        //    .default_val = 2
        //    .is_restricted = 3
        fields.add(JfkValMetaPB.getDescriptor().findFieldByNumber(1));
        fields.add(JfkValMetaPB.getDescriptor().findFieldByNumber(2));
        fields.add(JfkValMetaPB.getDescriptor().findFieldByNumber(3));

        // JfkFieldMetaPB
        //    .id = 1
        //    .name = 2
        //    .content_type = 4
        //    .is_array = 5
        //    .is_options = 6
        fields.add(JfkFieldMetaPB.getDescriptor().findFieldByNumber(1));
        fields.add(JfkFieldMetaPB.getDescriptor().findFieldByNumber(2));
        fields.add(JfkFieldMetaPB.getDescriptor().findFieldByNumber(4));
        fields.add(JfkFieldMetaPB.getDescriptor().findFieldByNumber(5));
        fields.add(JfkFieldMetaPB.getDescriptor().findFieldByNumber(6));

        // JfkTablePB
        //    .h_replica_data_direction_is_col = 24
        fields.add(JfkTablePB.getDescriptor().findFieldByNumber(24));

        jsonPrinter = JsonFormat.printer().includingDefaultValueFields(fields)
            .printingEnumsAsInts();
    }
}
