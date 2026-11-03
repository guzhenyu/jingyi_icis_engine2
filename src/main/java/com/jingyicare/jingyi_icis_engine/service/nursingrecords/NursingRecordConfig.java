package com.jingyicare.jingyi_icis_engine.service.nursingrecords;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class NursingRecordConfig {
    public NursingRecordConfig(
        @Autowired NursingOrderTemplateService templateService
    ) {
        this.templateService = templateService;
    }

    public void initialize() {
        templateService.initDeptTemplateGroups();
    }

    @Transactional
    public void checkIntegrity() {
    }

    public void refresh() {
    }

    private final NursingOrderTemplateService templateService;
}
