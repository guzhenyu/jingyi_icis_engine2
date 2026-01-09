package com.jingyicare.jingyi_icis_engine.service;

import javax.annotation.PostConstruct;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.stereotype.Service;

import lombok.extern.slf4j.Slf4j;

import com.jingyicare.jingyi_icis_engine.grpc.*;
import com.jingyicare.jingyi_icis_engine.proto.IcisConfig.Config;
import com.jingyicare.jingyi_icis_engine.proto.IcisWebApi.StatusCode;
import com.jingyicare.jingyi_icis_engine.service.doctors.DoctorConfig;
import com.jingyicare.jingyi_icis_engine.service.lis.LisConfig;
import com.jingyicare.jingyi_icis_engine.service.medications.MedicationConfig;
import com.jingyicare.jingyi_icis_engine.service.monitorings.MonitoringConfig;
import com.jingyicare.jingyi_icis_engine.service.nursingrecords.NursingRecordConfig;
import com.jingyicare.jingyi_icis_engine.service.scores.ScoreConfig;
import com.jingyicare.jingyi_icis_engine.service.shifts.ConfigShiftService;
import com.jingyicare.jingyi_icis_engine.service.patients.PatientConfig;
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
        @Autowired ScoreConfig scoreConfig,
        @Autowired NursingRecordConfig nursingRecordConfig,
        @Autowired DoctorConfig doctorConfig,
        @Autowired LisConfig lisConfig
    ) {
        this.context = context;
        this.config = configProtoService.getConfig();
        this.engineConfig = configProtoService.getEngineConfig();
        this.shiftConfig = shiftConfig;
        this.userConfig = userConfig;
        this.patientConfig = patientConfig;
        this.medicationConfig = medicationConfig;
        this.monitoringConfig = monitoringConfig;
        this.scoreConfig = scoreConfig;
        this.nursingRecordConfig = nursingRecordConfig;
        this.doctorConfig = doctorConfig;
        this.lisConfig = lisConfig;
    }

    @PostConstruct
    public void init() {
        monitoringConfig.initialize();
        userConfig.initialize();
        patientConfig.initialize();
        medicationConfig.initialize();
        scoreConfig.initialize();
        nursingRecordConfig.initialize();
        doctorConfig.initialize();
        lisConfig.initialize();
        log.info("Init system");

        if (StatusCode.LAST_CODE.getNumber() != config.getText().getStatusCodeMsgCount()) {
            log.error("StatusCode count mismatch: " + StatusCode.LAST_CODE.getNumber() +
                " v.s. " + config.getText().getStatusCodeMsgCount());
            LogUtils.flushAndQuit(context);
        }
        if (EngineStatusCode.ENGINE_LAST_CODE.getNumber() != engineConfig.getStatusCodeMsgCount()) {
            log.error("EngineStatusCode count mismatch: " + EngineStatusCode.ENGINE_LAST_CODE.getNumber() +
                " v.s. " + engineConfig.getStatusCodeMsgCount());
            LogUtils.flushAndQuit(context);
        }
        shiftConfig.checkIntegrity();
        userConfig.checkIntegrity();
        patientConfig.checkIntegrity();
        medicationConfig.checkIntegrity();
        monitoringConfig.checkIntegrity();
        scoreConfig.checkIntegrity();
        nursingRecordConfig.checkIntegrity();
        doctorConfig.checkIntegrity();
        log.info("Checking system consistency");

        userConfig.refresh();
        patientConfig.refresh();
        medicationConfig.refresh();
        monitoringConfig.refresh();
        scoreConfig.refresh();
        nursingRecordConfig.refresh();
        doctorConfig.refresh();
        log.info("Refreshed system");
    }

    private ConfigurableApplicationContext context;
    private Config config;
    private EngineConfigPB engineConfig;
    private ConfigShiftService shiftConfig;
    private UserConfig userConfig;
    private PatientConfig patientConfig;
    private MedicationConfig medicationConfig;
    private MonitoringConfig monitoringConfig;
    private ScoreConfig scoreConfig;
    private NursingRecordConfig nursingRecordConfig;
    private DoctorConfig doctorConfig;
    private LisConfig lisConfig;
}