package com.jingyicare.jingyi_icis_engine.service.patients;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.springframework.stereotype.Component;

import com.jingyicare.jingyi_icis_engine.consts.PatientConsts;
import com.jingyicare.jingyi_icis_engine.proto.config.IcisText.EnumTextPB;
import com.jingyicare.jingyi_icis_engine.proto.shared.Shared.EnumValue;
import com.jingyicare.jingyi_icis_engine.service.ConfigProtoService;

@Component
public class PatientEnumConverter {
    public PatientEnumConverter(ConfigProtoService protoService) {
        Map<String, Integer> rhIds = new HashMap<>();
        for (EnumValue value : PatientConsts.BLOOD_RHS) {
            rhIds.put(value.getName(), value.getId());
        }
        EnumTextPB enumText = protoService.getConfig().getText().getEnumText();
        addRhAliases(rhIds, enumText.getBloodRhNegativeStrList(), PatientConsts.BLOOD_RH_NEGATIVE);
        addRhAliases(rhIds, enumText.getBloodRhPositiveStrList(), PatientConsts.BLOOD_RH_POSITIVE);
        this.bloodRhIds = Map.copyOf(rhIds);
    }

    public Integer getBloodTypeId(String bloodType) {
        return PatientConsts.BLOOD_TYPES.stream()
            .filter(value -> value.getName().equals(bloodType))
            .map(EnumValue::getId)
            .findFirst()
            .orElse(PatientConsts.BLOOD_TYPE_UNKNOWN);
    }

    public Integer getBloodRhId(String bloodRh) {
        if (bloodRh == null) return PatientConsts.BLOOD_RH_UNKNOWN;
        return bloodRhIds.getOrDefault(bloodRh, PatientConsts.BLOOD_RH_UNKNOWN);
    }

    public String getBloodTypeName(Integer id) {
        return findEnumNameById(id, PatientConsts.BLOOD_TYPES);
    }

    public String getBloodRhName(Integer id) {
        return findEnumNameById(id, PatientConsts.BLOOD_RHS);
    }

    private void addRhAliases(Map<String, Integer> ids, List<String> aliases, Integer id) {
        for (String alias : aliases) {
            if (alias.isBlank()) {
                throw new IllegalArgumentException("Rh 别名不能为空或空白字符串");
            }
            Integer previousId = ids.putIfAbsent(alias, id);
            if (previousId != null && !previousId.equals(id)) {
                throw new IllegalArgumentException("Rh 别名冲突: " + alias);
            }
        }
    }

    private String findEnumNameById(Integer id, List<EnumValue> values) {
        if (id == null) return "";
        return values.stream()
            .filter(value -> value.getId() == id)
            .map(EnumValue::getName)
            .findFirst()
            .orElse("");
    }

    private final Map<String, Integer> bloodRhIds;
}
