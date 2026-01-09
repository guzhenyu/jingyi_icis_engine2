package com.jingyicare.jingyi_icis_engine.service.patients;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import com.jingyicare.jingyi_icis_engine.proto.IcisWebApi.*;
import com.jingyicare.jingyi_icis_engine.proto.config.IcisPatient.*;

import com.jingyicare.jingyi_icis_engine.entity.patients.PatientRecord;
import com.jingyicare.jingyi_icis_engine.repository.patients.PatientRecordRepository;
import com.jingyicare.jingyi_icis_engine.service.*;
import com.jingyicare.jingyi_icis_engine.service.patients.PatientService;
import com.jingyicare.jingyi_icis_engine.testutils.*;
import com.jingyicare.jingyi_icis_engine.utils.*;

public class PatientServiceTests extends TestsBase {
    public PatientServiceTests(
        @Autowired ConfigProtoService protoService,
        @Autowired PatientService patientService,
        @Autowired PatientRecordRepository patientRecordRepo
    ) {
        this.enums = protoService.getConfig().getPatient().getEnumsV2();
        this.PENDING_ADMISSION_VAL = enums.getAdmissionStatusList().stream()
            .filter(e -> e.getName().equals(protoService.getConfig().getPatient().getPendingAdmissionName()))
            .findFirst()
            .get()
            .getId();
        this.IN_ICU_VAL = enums.getAdmissionStatusList().stream()
            .filter(e -> e.getName().equals(protoService.getConfig().getPatient().getInIcuName()))
            .findFirst()
            .get()
            .getId();
        this.PENDING_DISCHARGED_VAL = enums.getAdmissionStatusList().stream()
            .filter(e -> e.getName().equals(protoService.getConfig().getPatient().getPendingDischargedName()))
            .findFirst()
            .get()
            .getId();
        this.DISCHARGED_VAL = enums.getAdmissionStatusList().stream()
            .filter(e -> e.getName().equals(protoService.getConfig().getPatient().getDischargedName()))
            .findFirst()
            .get()
            .getId();

        this.patientService = patientService;
        this.patientTestUtils = new PatientTestUtils();
        this.patientRecordRepo = patientRecordRepo;
    }

    @Test
    public void testGetPatientsV2() {
        final String deptId = "10013";
        PatientRecord rec1 = patientTestUtils.newPatientRecord(701L, PENDING_ADMISSION_VAL, deptId);
        PatientRecord rec2 = patientTestUtils.newPatientRecord(702L, IN_ICU_VAL, deptId);
        PatientRecord rec3 = patientTestUtils.newPatientRecord(703L, PENDING_DISCHARGED_VAL, deptId);
        PatientRecord rec4 = patientTestUtils.newPatientRecord(704L, DISCHARGED_VAL, deptId);
        rec1 = patientRecordRepo.save(rec1);
        rec2 = patientRecordRepo.save(rec2);
        rec3 = patientRecordRepo.save(rec3);
        rec4 = patientRecordRepo.save(rec4);

        // 获取在线病人
        GetInlinePatientsV2Req req = GetInlinePatientsV2Req.newBuilder()
            .setDeptId(deptId)
            .build();
        String reqStr = ProtoUtils.protoToJson(req);
        GetInlinePatientsV2Resp resp = patientService.getInlinePatientsV2(reqStr);
        assertThat(resp.getRt().getCode()).isEqualTo(StatusCode.OK.ordinal());
        assertThat(resp.getInIcu().getColList()).hasSize(8);
        assertThat(resp.getInIcu().getRowList()).hasSize(1);
        assertThat(resp.getInIcu().getRow(0).getCell(0).getValue()).isEqualTo("hisBedNumber702");
        assertThat(resp.getInIcu().getBasicsList()).hasSize(1);

        assertThat(resp.getPendingAdmission().getColList()).hasSize(10);
        assertThat(resp.getPendingAdmission().getRowList()).hasSize(1);
        assertThat(resp.getPendingAdmission().getRow(0).getCell(0).getValue()).isEqualTo("hisBedNumber701");
        assertThat(resp.getInIcu().getBasicsList()).hasSize(1);

        assertThat(resp.getPendingDischarged().getColList()).hasSize(11);
        assertThat(resp.getPendingDischarged().getRowList()).hasSize(1);
        assertThat(resp.getPendingDischarged().getRow(0).getCell(0).getValue()).isEqualTo("hisBedNumber703");
        assertThat(resp.getInIcu().getBasicsList()).hasSize(1);

        // 获取历史病人
        GetDischargedPatientsV2Req dischargedReq = GetDischargedPatientsV2Req.newBuilder()
            .setDeptId(deptId)
            .build();
        reqStr = ProtoUtils.protoToJson(req);
        GetDischargedPatientsV2Resp dischargedResp = patientService.getDischargedPatientsV2(reqStr);
        assertThat(dischargedResp.getRt().getCode()).isEqualTo(StatusCode.OK.ordinal());
        assertThat(dischargedResp.getPatient().getColList()).hasSize(10);
        assertThat(dischargedResp.getPatient().getRowList()).hasSize(1);
        assertThat(dischargedResp.getPatient().getRow(0).getCell(0).getValue()).isEqualTo("hisBedNumber704");
        assertThat(resp.getInIcu().getBasicsList()).hasSize(1);
    }

    final PatientEnumsV2 enums;
    final private Integer PENDING_ADMISSION_VAL;
    final private Integer IN_ICU_VAL;
    final private Integer PENDING_DISCHARGED_VAL;
    final private Integer DISCHARGED_VAL;
    final private PatientService patientService;
    final private PatientTestUtils patientTestUtils;
    final private PatientRecordRepository patientRecordRepo;
}

    // @Test
    // public void testUpdatePatientInIcu()  {
    //     final String deptId = "10002";
    //     PatientRecord rec = newPatientRecord(1L, pendingAdmissionVal, deptId);

    //     // 检查测试环境
    //     Optional<PatientRecord> optional = patientRecordRepository.findById(1L);
    //     assertThat(optional).isEmpty();

    //     // 初始化数据库
    //     patientRecordRepository.save(rec);

    //     // 执行测试
    //     PatientRecord.AdmissionInputs admissionInputs = new PatientRecord.AdmissionInputs();
    //     LocalDateTime now = LocalDateTime.now();
    //     admissionInputs.setAdmissionType("转入");
    //     admissionInputs.setAdmissionTime(now);
    //     admissionInputs.setAdmissionSourceDeptId("1008");
    //     admissionInputs.setAdmissionSourceDeptName("门诊");
    //     admissionInputs.setIsPlannedAdmission(true);
    //     admissionInputs.setPrimaryCareDoctorId("管床医生Idxxxx");
    //     admissionInputs.setAdmissionEditTime(now);
    //     admissionInputs.setAdmittingAccountId("入科操作人");
    //     rec.setAdmissionInputs(admissionInputs);

    //     patientRecordRepository.save(rec);

    //     // 查询检查更新情况
    // }

    // /**
    //  * 更新患者数据：待确认出科->出科
    //  *   出科时间【必填】  出科类型【必填】【转出、死亡、出院】  转出科室【选填】  死亡时间【选填】
    //  */
    // @Test
    // public void testUpdatePatientOutIcu()  {
    //     /*
    //      * setTextData()方法中：科室1002的数据待确认出科有1条
    //      *   id,  mrn,   icuName  deptId, bedNumBerStr  dischargeTime,      admissionStatus:
    //      *   5   'mrn5'   '张三5'  '1001'     4                              PENDING_DISCHARGED
    //      */
    //     Optional<PatientRecord> record = patientRecordRepository.findById(5L);
    //     assertNotNull(record);

    //     //设置字段并保存  后续前端可编辑的字段不一定是固定的，跟随业务升级而变化
    //     PatientRecord.DischargeInputs dischargeInputs = new PatientRecord.DischargeInputs();
    //     LocalDateTime now = TimeUtils.getLocalTime(2024,8,30);
    //     dischargeInputs.setDischargeTime(LocalDateTime.now());//出科时间
    //     dischargeInputs.setDischargedType("转出");//出科时间
    //     dischargeInputs.setDischargedDeptId("1100");
    //     dischargeInputs.setDischargedDeptName("外科");
    //     dischargeInputs.setDischargeEditTime(now);
    //     dischargeInputs.setDischargingAccountId("出科操作人");
    //     record.get().setDischargeInputs(dischargeInputs);


    //     patientRecordRepository.save(record.get());

    //     //验证是否修改成功
    //     Optional<PatientRecord> optional2 = patientRecordRepository.findById(5L);
    //     assertEquals(PatientRecord.AdmissionStatusEnum.DISCHARGED,optional2.get().getAdmissionStatus());
    //     assertEquals("转出",optional2.get().getDischargedType());
    //     assertEquals("1100",optional2.get().getDischargedDeptId());
    //     assertEquals("外科",optional2.get().getDischargedDeptName());
    //     assertEquals(now,optional2.get().getDischargeEditTime());
    //     assertEquals("出科操作人",optional2.get().getDischargingAccountId());

    // }

    // /**
    //  * 查询患者24小时内的上一条出科数据：根据科室编码、住院号、在科状态、出科时间查询
    //  *  待确认入科的时候，点击"确认入科"按钮，会调用该方法，判断这个病人在当前入科时间的前24小时内是否有出科记录
    //  */
    // @Test
    // public void testFindByDeptIdAndHisMrnAndAdmissionStatusAndDischargeTimeRange() {
    //     /*
    //      * setTextData()方法中， 创建了3条，dept_id字段为"1001"且mrn字段为"mrn1"的PatientRecord对象，满足条件的只有1条
    //      * id  his_mrn  icu_name dept_id  bed_number_str  admissionStatus dischargeTime
    //      * ----------------------------------------------------------------------------
    //      * 1   'mrn1'    '张三1'  1001        1            DISCHARGED      2024-07-30 14:46:00
    //      * 7   'mrn1'    '张三1'  1001        1            DISCHARGED      2024-07-30 15:46:00  [满足条件的数据第一条数据]
    //      * 2   'mrn1'    '张三1'  1001        1            IN_ICU
    //      */

    //     LocalDateTime startTime = TimeUtils.getLocalTime(2024,7,30);
    //     LocalDateTime endTime = TimeUtils.getLocalTime(2024,8,1);
    //     Optional<PatientRecord> patientRecord = patientRecordRepository.findFirstByDeptIdAndAdmissionStatusAndDischargeTimeRange("1001","mrn1",PatientRecord.AdmissionStatusEnum.DISCHARGED,startTime, endTime);
    //     assertNotNull(patientRecord);
    //     assertEquals(7,patientRecord.get().getId());
    //     assertEquals("mrn1",patientRecord.get().getHisMrn());
    // }

    // /**
    //  * 手动添加的病人
    //  * 姓名	    住院号	 性别	  入科时间	      出生时间
    //  * 诊断	    入科类型	 入科计划  非计划原因(非必填)  床号
    //  * 入科类型  入科计划、 入科来源  管床医生(非必填)   hisPid(非必填)
    //  */
    // @Test
    // public void testSavePatientIcuManualEntry() {
    //     //先确保数据不存在
    //     List<PatientRecord> optionalList = patientRecordRepository.findByDeptIdAndHisMrnByAdmissionStatus("1003","mrn9");
    //     assertTrue(optionalList.isEmpty());

    //     //设置需要修改的字段数据并保存
    //     LocalDateTime now = TimeUtils.getLocalTime(2024,8,30);
    //     PatientRecord patientRecord = new PatientRecord();
    //     patientRecord.setHisMrn("mrn9");
    //     patientRecord.setIcuName("张三9");
    //     patientRecord.setIcuGender(PatientRecord.GenderEnum.MALE);
    //     patientRecord.setAdmissionTime(now);
    //     patientRecord.setIcuDateOfBirth(now);
    //     patientRecord.setDiagnosisTime(now);
    //     patientRecord.setDiagnosis("诊断");
    //     patientRecord.setAdmissionType("转入");
    //     patientRecord.setIsPlannedAdmission(true);//计划入科
    //     patientRecord.setBedNumberStr("9");
    //     patientRecord.setHisPatientId("12345678901");
    //     patientRecord.setAdmissionSourceDeptId("10016");
    //     patientRecord.setAdmissionSourceDeptName("急诊");
    //     patientRecord.setIcuManualEntry(true);//手动添加
    //     patientRecord.setIcuManualEntryAccountId("XXX");//添加人
    //     patientRecord.setAdmissionStatus(PatientRecord.AdmissionStatusEnum.IN_ICU);
    //     patientRecord.setCreatedAt(now);
    //     patientRecordRepository.save(patientRecord);

    //     //验证是否添加成功
    //     Optional<PatientRecord> optional = patientRecordRepository.findById(patientRecord.getId());
    //     assertNotNull(optional);
    //     assertEquals("mrn9",optional.get().getHisMrn());
    //     assertEquals("张三9",optional.get().getIcuName());
    //     assertEquals(PatientRecord.GenderEnum.MALE,optional.get().getIcuGender());
    //     assertEquals(now,optional.get().getAdmissionTime());
    //     assertEquals(now,optional.get().getIcuDateOfBirth());
    //     assertEquals(now,optional.get().getDiagnosisTime());
    //     assertEquals("诊断",optional.get().getDiagnosis());
    //     assertEquals("转入",optional.get().getAdmissionType());
    //     assertTrue(optional.get().getIsPlannedAdmission());
    //     assertEquals("9",optional.get().getBedNumberStr());
    //     assertEquals("12345678901",optional.get().getHisPatientId());
    //     assertEquals("10016",optional.get().getAdmissionSourceDeptId());
    //     assertEquals("急诊",optional.get().getAdmissionSourceDeptName());
    //     assertTrue(optional.get().getIcuManualEntry());
    //     assertEquals("XXX",optional.get().getIcuManualEntryAccountId());
    //     assertEquals(PatientRecord.AdmissionStatusEnum.IN_ICU,optional.get().getAdmissionStatus());
    //     assertEquals(now,optional.get().getCreatedAt());
    // }