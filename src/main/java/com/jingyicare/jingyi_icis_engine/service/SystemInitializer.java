package com.jingyicare.jingyi_icis_engine.service;

import java.util.List;

import javax.annotation.PostConstruct;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.stereotype.Service;

import lombok.extern.slf4j.Slf4j;

import com.jingyicare.jingyi_icis_engine.proto.IcisConfig.Config;
import com.jingyicare.jingyi_icis_engine.proto.IcisWebApi.StatusCode;
import com.jingyicare.jingyi_icis_engine.proto.config.IcisUser.DepartmentPB;
import com.jingyicare.jingyi_icis_engine.service.certs.CertificateService;
import com.jingyicare.jingyi_icis_engine.service.doctors.DoctorConfig;
import com.jingyicare.jingyi_icis_engine.service.lis.LisConfig;
import com.jingyicare.jingyi_icis_engine.service.medications.MedicationConfig;
import com.jingyicare.jingyi_icis_engine.service.monitorings.MonitoringConfig;
import com.jingyicare.jingyi_icis_engine.service.monitorings.MonitoringMetaService;
import com.jingyicare.jingyi_icis_engine.service.patients.PatientConfig;
import com.jingyicare.jingyi_icis_engine.service.shifts.ConfigShiftService;
import com.jingyicare.jingyi_icis_engine.service.users.DepartmentCommand;
import com.jingyicare.jingyi_icis_engine.service.users.UserConfig;
import com.jingyicare.jingyi_icis_engine.service.users.UserQuery;
import com.jingyicare.jingyi_icis_engine.utils.LogUtils;

@Service
@Slf4j
public class SystemInitializer {
    public SystemInitializer(
        ConfigurableApplicationContext context,
        @Autowired ConfigProtoService configProtoService,
        @Autowired ConfigShiftService shiftConfig,
        @Autowired UserConfig userConfig,
        @Autowired UserQuery userQuery,
        @Autowired DepartmentCommand departmentCommand,
        @Autowired PatientConfig patientConfig,
        @Autowired MedicationConfig medicationConfig,
        @Autowired MonitoringConfig monitoringConfig,
        @Autowired MonitoringMetaService monitoringMetaService,
        @Autowired DoctorConfig doctorConfig,
        @Autowired LisConfig lisConfig,
        @Autowired CertificateService certificateService
    ) {
        this.context = context;
        this.config = configProtoService.getConfig();
        this.shiftConfig = shiftConfig;
        this.userConfig = userConfig;
        this.userQuery = userQuery;
        this.departmentCommand = departmentCommand;
        this.patientConfig = patientConfig;
        this.medicationConfig = medicationConfig;
        this.monitoringConfig = monitoringConfig;
        this.monitoringMetaService = monitoringMetaService;
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
        doctorConfig.initialize();
        lisConfig.initialize();

        // 初始化部门数据
        List<DepartmentPB> allDepts = userQuery.getAllDepartments();
        for (DepartmentPB deptPb : allDepts) {
            StatusCode statusCode = departmentCommand.initDept(
                deptPb.getDeptId(), UserQuery.SYSTEM_ACCOUNT_ID);
            if (statusCode != StatusCode.OK) {
                throw new IllegalStateException(
                    "科室初始化失败：" + deptPb.getDeptId() + ", " + statusCode);
            }
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
    private UserQuery userQuery;
    private DepartmentCommand departmentCommand;
    private PatientConfig patientConfig;
    private MedicationConfig medicationConfig;
    private MonitoringConfig monitoringConfig;
    private final MonitoringMetaService monitoringMetaService;
    private DoctorConfig doctorConfig;
    private LisConfig lisConfig;
    private CertificateService certificateService;
}
