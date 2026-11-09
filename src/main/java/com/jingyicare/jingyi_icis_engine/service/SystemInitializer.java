package com.jingyicare.jingyi_icis_engine.service;

import java.util.List;
import java.util.Set;

import javax.annotation.PostConstruct;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.stereotype.Service;

import lombok.extern.slf4j.Slf4j;

import com.jingyicare.jingyi_icis_engine.proto.IcisConfig.Config;
import com.jingyicare.jingyi_icis_engine.proto.IcisWebApi.StatusCode;
import com.jingyicare.jingyi_icis_engine.entity.users.RbacDepartment;
import com.jingyicare.jingyi_icis_engine.repository.scores.DeptScoreGroupRepository;
import com.jingyicare.jingyi_icis_engine.repository.users.RbacDepartmentRepository;
import com.jingyicare.jingyi_icis_engine.service.doctors.DoctorConfig;
import com.jingyicare.jingyi_icis_engine.service.lis.LisConfig;
import com.jingyicare.jingyi_icis_engine.service.medications.MedicationConfig;
import com.jingyicare.jingyi_icis_engine.service.monitorings.BgaMetaService;
import com.jingyicare.jingyi_icis_engine.service.monitorings.MonitoringConfig;
import com.jingyicare.jingyi_icis_engine.service.monitorings.MonitoringMetaService;
import com.jingyicare.jingyi_icis_engine.service.nursingrecords.NursingOrderTemplateService;
import com.jingyicare.jingyi_icis_engine.service.scores.ScoreMetaService;
import com.jingyicare.jingyi_icis_engine.service.shifts.ConfigShiftService;
import com.jingyicare.jingyi_icis_engine.service.patients.PatientConfig;
import com.jingyicare.jingyi_icis_engine.service.certs.CertificateService;
import com.jingyicare.jingyi_icis_engine.service.users.UserConfig;
import com.jingyicare.jingyi_icis_engine.utils.LogUtils;

@Service
@Slf4j
public class SystemInitializer {
    public SystemInitializer(
        ConfigurableApplicationContext context,
        @Autowired ConfigProtoService configProtoService,
        @Autowired ConfigShiftService shiftConfig,
        @Autowired UserConfig userConfig,
        @Autowired PatientConfig patientConfig,
        @Autowired MedicationConfig medicationConfig,
        @Autowired BgaMetaService bgaMetaService,
        @Autowired MonitoringConfig monitoringConfig,
        @Autowired MonitoringMetaService monitoringMetaService,
        @Autowired ScoreMetaService scoreMetaService,
        @Autowired DeptScoreGroupRepository deptScoreGroupRepo,
        @Autowired RbacDepartmentRepository departmentRepo,
        @Autowired NursingOrderTemplateService nursingOrderTemplateService,
        @Autowired DoctorConfig doctorConfig,
        @Autowired LisConfig lisConfig,
        @Autowired CertificateService certificateService
    ) {
        this.context = context;
        this.config = configProtoService.getConfig();
        this.shiftConfig = shiftConfig;
        this.userConfig = userConfig;
        this.patientConfig = patientConfig;
        this.medicationConfig = medicationConfig;
        this.bgaMetaService = bgaMetaService;
        this.monitoringConfig = monitoringConfig;
        this.monitoringMetaService = monitoringMetaService;
        this.scoreMetaService = scoreMetaService;
        this.deptScoreGroupRepo = deptScoreGroupRepo;
        this.departmentRepo = departmentRepo;
        this.nursingOrderTemplateService = nursingOrderTemplateService;
        this.doctorConfig = doctorConfig;
        this.lisConfig = lisConfig;
        this.certificateService = certificateService;
    }

    @PostConstruct
    public void init() {
        certificateService.validatePreInitializationOrThrow();
        log.info("Deployment certificate and ICIS department scope inputs checked");

        monitoringMetaService.initMonitoringParams();
        userConfig.initialize();
        patientConfig.initialize();
        medicationConfig.initialize();
        nursingOrderTemplateService.initDeptTemplateGroups();
        doctorConfig.initialize();
        lisConfig.initialize();

        // 初始化部门数据
        List<String> deptIds = departmentRepo.findAll().stream()
            .map(RbacDepartment::getDeptId).toList();
        Set<String> scoreGroupDeptIds = deptScoreGroupRepo.findAllDeptIds();
        for (String deptId : deptIds) {
            if (!scoreGroupDeptIds.contains(deptId)) {
                scoreMetaService.initDept(deptId);
            }
            bgaMetaService.initBgaParams(deptId);
        }

        log.info("Init system");

        if (StatusCode.LAST_CODE.getNumber() != config.getText().getStatusCodeMsgCount()) {
            log.error("StatusCode count mismatch: " + StatusCode.LAST_CODE.getNumber() +
                " v.s. " + config.getText().getStatusCodeMsgCount());
            LogUtils.flushAndQuit(context);
        }
        shiftConfig.checkIntegrity();
        userConfig.checkIntegrity();
        patientConfig.checkIntegrity();
        medicationConfig.checkIntegrity();
        monitoringConfig.checkIntegrity();
        doctorConfig.checkIntegrity();
        log.info("Checking system consistency");

        userConfig.refresh();
        patientConfig.refresh();
        medicationConfig.refresh();
        monitoringConfig.refresh();
        doctorConfig.refresh();
        log.info("Refreshed system");

        certificateService.validateStartupOrThrow();
        log.info("Certificate and ICIS department scope checked");
    }

    private ConfigurableApplicationContext context;
    private Config config;
    private ConfigShiftService shiftConfig;
    private UserConfig userConfig;
    private PatientConfig patientConfig;
    private MedicationConfig medicationConfig;
    private BgaMetaService bgaMetaService;
    private MonitoringConfig monitoringConfig;
    private final MonitoringMetaService monitoringMetaService;
    private ScoreMetaService scoreMetaService;
    private DeptScoreGroupRepository deptScoreGroupRepo;
    private RbacDepartmentRepository departmentRepo;
    private NursingOrderTemplateService nursingOrderTemplateService;
    private DoctorConfig doctorConfig;
    private LisConfig lisConfig;
    private CertificateService certificateService;
}
