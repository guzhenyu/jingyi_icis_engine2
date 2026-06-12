package com.jingyicare.jingyi_icis_engine.service.debug;

import java.util.List;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.web.util.HtmlUtils;

import com.jingyicare.jingyi_icis_engine.entity.medications.MedicationOrderGroup;
import com.jingyicare.jingyi_icis_engine.repository.medications.MedicationOrderGroupRepository;
import com.jingyicare.jingyi_icis_engine.utils.ProtoUtils;

@Service
public class MedicationDebugger {
    public MedicationDebugger(
        @Autowired MedicationOrderGroupRepository medicationOrderGroupRepository
    ) {
        this.medicationOrderGroupRepository = medicationOrderGroupRepository;
    }

    public String getMedOrdGroups(Long patientId) {
        List<MedicationOrderGroup> groups =
            medicationOrderGroupRepository.findByPatientIdOrderByIdAsc(patientId);

        StringBuilder builder = new StringBuilder();
        for (int i = 0; i < groups.size(); ++i) {
            MedicationOrderGroup group = groups.get(i);
            if (i > 0) {
                builder.append("\n--------------------------\n");
            }

            builder.append("id: ").append(group.getId()).append("\n");
            builder.append("ordering_doctor: ").append(group.getOrderingDoctor()).append("\n");
            builder.append("ordering_doctor_id: ").append(group.getOrderingDoctorId()).append("\n");
            builder.append("medication_dosage_group:\n");
            builder.append(ProtoUtils.protoToTxt(
                ProtoUtils.decodeDosageGroup(group.getMedicationDosageGroup())));
        }

        return "<pre>" + HtmlUtils.htmlEscape(builder.toString()) + "</pre>";
    }

    private MedicationOrderGroupRepository medicationOrderGroupRepository;
}
