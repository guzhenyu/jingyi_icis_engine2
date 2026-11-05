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
import com.jingyicare.jingyi_icis_engine.service.monitorings.MonitoringConfig;
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
        @Autowired MonitoringConfig monitoringConfig,
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
        this.monitoringConfig = monitoringConfig;
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

        monitoringConfig.initialize();
        userConfig.initialize();
        patientConfig.initialize();
        medicationConfig.initialize();
        initializeDeptScoreGroups();
        nursingOrderTemplateService.initDeptTemplateGroups();
        doctorConfig.initialize();
        lisConfig.initialize();
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

    private void initializeDeptScoreGroups() {
        List<String> departmentIds = departmentRepo.findAll()
            .stream()
            .map(RbacDepartment::getDeptId)
            .toList();
        Set<String> existingDeptIds = deptScoreGroupRepo.findAllDeptIds();

        for (String deptId : departmentIds) {
            if (existingDeptIds.contains(deptId)) {
                log.info("Department score groups already exist for deptId: {}", deptId);
                continue;
            }

            scoreMetaService.initDept(deptId);
        }

        log.info("Department score groups initialization completed.");
    }

    private ConfigurableApplicationContext context;
    private Config config;
    private ConfigShiftService shiftConfig;
    private UserConfig userConfig;
    private PatientConfig patientConfig;
    private MedicationConfig medicationConfig;
    private MonitoringConfig monitoringConfig;
    private ScoreMetaService scoreMetaService;
    private DeptScoreGroupRepository deptScoreGroupRepo;
    private RbacDepartmentRepository departmentRepo;
    private NursingOrderTemplateService nursingOrderTemplateService;
    private DoctorConfig doctorConfig;
    private LisConfig lisConfig;
    private CertificateService certificateService;
}
