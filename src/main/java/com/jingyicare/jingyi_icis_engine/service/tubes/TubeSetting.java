package com.jingyicare.jingyi_icis_engine.service.tubes;

import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.stream.Collectors;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import lombok.*;
import lombok.extern.slf4j.Slf4j;

import com.jingyicare.jingyi_icis_engine.proto.IcisWebApi.*;
import com.jingyicare.jingyi_icis_engine.proto.config.IcisMonitoring.*;
import com.jingyicare.jingyi_icis_engine.proto.config.IcisTube.*;

import com.jingyicare.jingyi_icis_engine.entity.monitorings.*;
import com.jingyicare.jingyi_icis_engine.entity.tubes.*;
import com.jingyicare.jingyi_icis_engine.repository.monitorings.*;
import com.jingyicare.jingyi_icis_engine.repository.tubes.*;
import com.jingyicare.jingyi_icis_engine.service.ConfigProtoService;
import com.jingyicare.jingyi_icis_engine.utils.*;

@Component
@Slf4j
public class TubeSetting {
    public static String getMonitoringParamCode(String tubeType, String tubeName) {
        StringBuilder paramCodeBuilder = new StringBuilder();
        paramCodeBuilder.append("tube_");
        paramCodeBuilder.append(StrUtils.toPinyinInitials(tubeType));
        paramCodeBuilder.append("_");
        paramCodeBuilder.append(StrUtils.toPinyinInitials(tubeName));
        return paramCodeBuilder.toString();
    }

    public TubeSetting(
        @Autowired ConfigProtoService protoService,
        @Autowired TubeTypeRepository tubeTypeRepo,
        @Autowired TubeTypeAttributeRepository tubeTypeAttrRepo,
        @Autowired TubeTypeStatusRepository tubeTypeStatusRepo,
        @Autowired MonitoringParamRepository monitoringParamRepo
    ) {
        this.tubePb = protoService.getConfig().getTube();
        this.DRAINAGE_TUBE_TYPE = tubePb.getDrainageTubeType();

        this.tubeTypeRepo = tubeTypeRepo;
        this.tubeTypeAttrRepo = tubeTypeAttrRepo;
        this.tubeTypeStatusRepo = tubeTypeStatusRepo;
        this.monitoringParamRepo = monitoringParamRepo;

        MonitoringParamPB defaultDrainageParamPB =
            protoService.getConfig().getMonitoring().getDefaultDrainageParam();
        this.defaultDrainageParam = MonitoringParam.builder()
            .typePb(ProtoUtils.encodeValueMeta(defaultDrainageParamPB.getValueMeta()))
            .balanceType(defaultDrainageParamPB.getBalanceType())
            .category(defaultDrainageParamPB.getCategory())
            .uiModalCode(defaultDrainageParamPB.getUiModalCode())
            .build();
    }

    public TubeTypeListPB getDeptTubeTypes(String deptId) {
        // 创建 TubeTypeListPB 的构造器
        TubeTypeListPB.Builder tubeTypeListBuilder = TubeTypeListPB.newBuilder();

        // 从数据库获取 deptId 对应的所有 TubeType
        List<TubeType> tubeTypes = tubeTypeRepo.findByDeptIdAndIsDeletedFalse(deptId);
        tubeTypes.sort(Comparator.comparing(TubeType::getDisplayOrder));

        // 遍历每个 TubeType，构造 TubeTypePB
        for (TubeType tubeType : tubeTypes) {
            final Integer tubeTypeId = tubeType.getId();
            TubeTypePB.Builder tubeTypeBuilder = TubeTypePB.newBuilder()
                    .setId(tubeType.getId())
                    .setType(tubeType.getType())
                    .setName(tubeType.getName() != null ? tubeType.getName() : "")
                    .setCategory(tubeType.getCategory() != null ? tubeType.getCategory() : "")
                    .setIsCommon(tubeType.getIsCommon() != null && tubeType.getIsCommon() ? 1 : 0)
                    .setDisplayOrder(tubeType.getDisplayOrder() != null ? tubeType.getDisplayOrder() : 0)
                    .setIsDisabled(tubeType.getIsDisabled() != null && tubeType.getIsDisabled() ? 1 : 0);

            // 获取当前 tubeType 的属性
            List<TubeTypeAttribute> attributes = tubeTypeAttrRepo.findByTubeTypeIdAndIsDeletedFalse(tubeTypeId);
            attributes.sort(Comparator.comparing(TubeTypeAttribute::getDisplayOrder));
            for (TubeTypeAttribute attribute : attributes) {
                TubeValueSpecPB.Builder attributeBuilder = TubeValueSpecPB.newBuilder()
                    .setId(attribute.getId())
                    .setCode(attribute.getAttribute())
                    .setName(attribute.getName())
                    .setUiType(attribute.getUiType())
                    .addAllOptValue(splitString(attribute.getOptValue()))
                    .addAllDefaultValue(splitString(attribute.getDefaultValue()))
                    .setUnit(attribute.getUnit() != null ? attribute.getUnit() : "")
                    .setDisplayOrder(attribute.getDisplayOrder());

                // 添加属性到 TubeTypePB
                tubeTypeBuilder.addAttribute(attributeBuilder.build());
            }

            // 获取当前 tubeType 的状态
            List<TubeTypeStatus> statuses = tubeTypeStatusRepo.findByTubeTypeIdAndIsDeletedFalse(tubeTypeId);
            statuses.sort(Comparator.comparing(TubeTypeStatus::getDisplayOrder));
            for (TubeTypeStatus status : statuses) {
                TubeValueSpecPB.Builder statusBuilder = TubeValueSpecPB.newBuilder()
                    .setId(status.getId())
                    .setCode(status.getStatus())
                    .setName(status.getName())
                    .setUiType(status.getUiType())
                    .addAllOptValue(splitString(status.getOptValue()))
                    .addAllDefaultValue(splitString(status.getDefaultValue()))
                    .setUnit(status.getUnit() != null ? status.getUnit() : "")
                    .setDisplayOrder(status.getDisplayOrder());

                // 添加状态到 TubeTypePB
                tubeTypeBuilder.addStatus(statusBuilder.build());
            }

            // 将 TubeTypePB 添加到 TubeTypeListPB
            tubeTypeListBuilder.addTubeType(tubeTypeBuilder.build());
        }

        // 返回构建好的 TubeTypeListPB
        return tubeTypeListBuilder.build();
    }

    public TubeType findTubeType(String deptId, String name) {
        return tubeTypeRepo.findByDeptIdAndNameAndIsDeletedFalse(deptId, name).orElse(null);
    }

    public TubeType findTubeType(Integer tubeTypeId) {
        return tubeTypeRepo.findByIdAndIsDeletedFalse(tubeTypeId).orElse(null);
    }

    public List<TubeType> findTubeTypes(String deptId, String type) {
        return tubeTypeRepo.findByDeptIdAndTypeAndIsDeletedFalse(deptId, type);
    }

    public Integer addOrUpdateTubeType(String deptId, TubeTypePB tubeTypePb, Integer tubeTypeId, String accountId) {
        // 创建 TubeType 实体
        TubeType tubeType = TubeType.builder()
            .deptId(deptId)
            .type(tubeTypePb.getType())
            .name(tubeTypePb.getName() != null ? tubeTypePb.getName() : "")
            .category(tubeTypePb.getCategory() != null ? tubeTypePb.getCategory() : "")
            .isCommon(tubeTypePb.getIsCommon() > 0)
            .isDisabled(tubeTypePb.getIsDisabled() > 0)
            .displayOrder(tubeTypePb.getDisplayOrder())
            .isDeleted(false)
            .modifiedBy(accountId)
            .modifiedAt(TimeUtils.getNowUtc())
            .build();
        
        if (tubeTypeId != null) tubeType.setId(tubeTypeId);

        // 使用 TubeTypeRepository 保存实体
        tubeType = tubeTypeRepo.save(tubeType);

        // 如果对应的管道类型是“引流管”，生成对应的monitoring_params
        updateMonitoringParam(tubeType.getType(), tubeType.getName());

        return tubeType.getId();
    }

    public Boolean isDisablingTheLastTubeType(Integer tubeTypeId, Boolean toDisable) {
        if (!toDisable) return false;

        // 首先查找指定的 TubeType
        TubeType tubeType = tubeTypeRepo.findByIdAndIsDeletedFalse(tubeTypeId).orElse(null);
        if (tubeType == null) return false;

        // 确认是否是最后一个活跃的管道类型
        final String deptId = tubeType.getDeptId();
        List<TubeType> tubeTypes = tubeTypeRepo.findByDeptIdAndIsDeletedFalse(deptId);
        Integer activeCount = 0;
        for (TubeType tt : tubeTypes) {
            if (!tt.getIsDisabled()) activeCount++;
        }

        return activeCount <= 1;
    }

    public Integer disableTubeType(Integer tubeTypeId, Boolean isDisabled, String accountId) {
        // 首先查找指定的 TubeType
        TubeType tubeType = tubeTypeRepo.findByIdAndIsDeletedFalse(tubeTypeId).orElse(null);
        log.info("Disable a tube type, tubeTypeId {}, accountId {}", tubeTypeId, accountId);

        if (tubeType != null) {
            tubeType.setIsDisabled(isDisabled);
            tubeType.setModifiedBy(accountId);
            tubeType.setModifiedAt(TimeUtils.getNowUtc());
            tubeType = tubeTypeRepo.save(tubeType);
            log.info("Disabled TubeType {} by {}", tubeType, accountId);
        }
        return tubeType.getId();
    }

    public void deleteDeptTubeType(Integer tubeTypeId, String accountId) {
        // 首先查找指定的 TubeType
        TubeType tubeType = tubeTypeRepo.findByIdAndIsDeletedFalse(tubeTypeId).orElse(null);
        log.info("Delete a tube type, tubeTypeId {}, accountId {}", tubeTypeId, accountId);

        if (tubeType != null) {
            tubeType.setIsDeleted(true);
            tubeType.setModifiedBy(accountId);
            tubeType.setModifiedAt(TimeUtils.getNowUtc());
            tubeType = tubeTypeRepo.save(tubeType);
            log.info("Deleted TubeType {}", tubeType);
        }
    }

    public TubeTypeAttribute findTubeTypeAttribute(Integer tubeTypeAttributeId) {
        return tubeTypeAttrRepo.findByIdAndIsDeletedFalse(tubeTypeAttributeId).orElse(null);
    }

    public TubeTypeAttribute findTubeTypeAttribute(
        Integer tubeTypeId, String attributeCode, Boolean isDeleted
    ) {
        List<TubeTypeAttribute> attrs = tubeTypeAttrRepo.findByTubeTypeIdAndAttributeAndIsDeleted(
            tubeTypeId, attributeCode, isDeleted
        ).stream().sorted(Comparator.comparing(TubeTypeAttribute::getId).reversed())
        .collect(Collectors.toList());

        if (attrs.isEmpty()) return null;
        return attrs.get(0);
    }

    public TubeTypeAttribute findTubeTypeAttrName(Integer tubeTypeId, String attrName) {
        return tubeTypeAttrRepo.findByTubeTypeIdAndNameAndIsDeletedFalse(tubeTypeId, attrName).orElse(null);
    }

    public Integer addOrUpdateTubeTypeAttribute(Integer tubeTypeId, TubeValueSpecPB attributePb, Integer tubeTypeAttrId, String accountId) {
        // 创建 TubeTypeAttribute 实体
        TubeTypeAttribute tubeTypeAttribute = TubeTypeAttribute.builder()
            .tubeTypeId(tubeTypeId)
            .attribute(attributePb.getCode())
            .name(attributePb.getName())
            .uiType(attributePb.getUiType())
            .optValue(String.join(",", attributePb.getOptValueList()))  // 将 opt_value 列表转换为逗号分隔的字符串
            .defaultValue(String.join(",", attributePb.getDefaultValueList()))  // 将 default_value 列表转换为逗号分隔的字符串
            .unit(attributePb.getUnit() != null ? attributePb.getUnit() : "")
            .displayOrder(attributePb.getDisplayOrder())
            .isDeleted(false)
            .modifiedAt(TimeUtils.getNowUtc())
            .modifiedBy(accountId)
            .build();
        if (tubeTypeAttrId != null) tubeTypeAttribute.setId(tubeTypeAttrId);

        // 保存 TubeTypeAttribute 实体
        tubeTypeAttribute = tubeTypeAttrRepo.save(tubeTypeAttribute);
        return tubeTypeAttribute.getId();
    }

    public void deleteTubeTypeAttribute(Integer tubeTypeAttrId, String accountId) {
        // 根据 tubeTypeAttrId 查找 TubeTypeAttribute
        Optional<TubeTypeAttribute> tubeTypeAttributeOpt = tubeTypeAttrRepo.findByIdAndIsDeletedFalse(tubeTypeAttrId);

        if (tubeTypeAttributeOpt.isPresent()) {
            TubeTypeAttribute tubeTypeAttribute = tubeTypeAttributeOpt.get();
            tubeTypeAttribute.setIsDeleted(true);
            tubeTypeAttribute.setModifiedBy(accountId);
            tubeTypeAttribute.setModifiedAt(TimeUtils.getNowUtc());
            tubeTypeAttrRepo.save(tubeTypeAttribute);
            log.info("Deleted TubeTypeAttribute with tubeTypeAttrId {}", tubeTypeAttrId);
        } else {
            log.warn("TubeTypeAttribute not found with tubeTypeAttrId {}", tubeTypeAttrId);
        }
    }

    public TubeTypeStatus findTubeTypeStatus(
        Integer tubeTypeId, String statusCode, Boolean isDeleted
    ) {
        List<TubeTypeStatus> statuses = tubeTypeStatusRepo.findByTubeTypeIdAndStatusAndIsDeleted(
            tubeTypeId, statusCode, isDeleted
        ).stream().sorted(Comparator.comparing(TubeTypeStatus::getId).reversed())
        .collect(Collectors.toList());

        if (statuses.isEmpty()) return null;
        return statuses.get(0);
    }

    public TubeTypeStatus findTubeTypeStatusName(Integer tubeTypeId, String statusName) {
        return tubeTypeStatusRepo.findByTubeTypeIdAndNameAndIsDeletedFalse(tubeTypeId, statusName).orElse(null);
    }

    public Integer addOrUpdateTubeTypeStatus(Integer tubeTypeId, TubeValueSpecPB statusPb, Integer tubeTypeStatusId, String accountId) {
        // 创建 TubeTypeStatus 实体
        TubeTypeStatus tubeTypeStatus = TubeTypeStatus.builder()
            .tubeTypeId(tubeTypeId)
            .status(statusPb.getCode())
            .name(statusPb.getName())
            .uiType(statusPb.getUiType())
            .optValue(String.join(",", statusPb.getOptValueList()))  // 将 opt_value 列表转换为逗号分隔的字符串
            .defaultValue(String.join(",", statusPb.getDefaultValueList()))  // 将 default_value 列表转换为逗号分隔的字符串
            .unit(statusPb.getUnit() != null ? statusPb.getUnit() : "")
            .displayOrder(statusPb.getDisplayOrder())
            .isDeleted(false)
            .modifiedBy(accountId)
            .modifiedAt(TimeUtils.getNowUtc())
            .build();
        if (tubeTypeStatusId != null) tubeTypeStatus.setId(tubeTypeStatusId);

        // 保存 TubeTypeStatus 实体
        tubeTypeStatus = tubeTypeStatusRepo.save(tubeTypeStatus);
        return tubeTypeStatus.getId();
    }

    public void deleteTubeTypeStatus(Integer tubeTypeStatusId, String accountId) {
        // 根据 tubeTypeStatusId 查找 TubeTypeStatus
        Optional<TubeTypeStatus> tubeTypeStatusOpt = tubeTypeStatusRepo.findByIdAndIsDeletedFalse(tubeTypeStatusId);

        if (tubeTypeStatusOpt.isPresent()) {
            TubeTypeStatus tubeTypeStatus = tubeTypeStatusOpt.get();
            tubeTypeStatus.setIsDeleted(true);
            tubeTypeStatus.setModifiedBy(accountId);
            tubeTypeStatus.setModifiedAt(TimeUtils.getNowUtc());
            tubeTypeStatusRepo.save(tubeTypeStatus);
            log.info("Deleted TubeTypeStatus with tubeTypeStatusId {}", tubeTypeStatusId);
        } else {
            log.warn("TubeTypeStatus not found with tubeTypeStatusId {}", tubeTypeStatusId);
        }
    }

    public void adjustTubeOrder(AdjustTubeOrderReq req, String accountId) {
        List<TubeOrder> tubeOrders = req.getTubeOrderList();
        if (req.getTubeOrderType() == TubeOrderType.TUBE_TYPE.ordinal()) {
            for (TubeOrder tubeOrder : tubeOrders) {
                Optional<TubeType> tubeTypeOpt = tubeTypeRepo.findByIdAndIsDeletedFalse(tubeOrder.getId());
                if (tubeTypeOpt.isPresent()) {
                    TubeType tubeType = tubeTypeOpt.get();
                    tubeType.setDisplayOrder(tubeOrder.getDisplayOrder());
                    tubeType.setModifiedBy(accountId);
                    tubeType.setModifiedAt(TimeUtils.getNowUtc());
                    tubeTypeRepo.save(tubeType);
                }
            }
        } else if (req.getTubeOrderType() == TubeOrderType.TUBE_ATTR.ordinal()) {
            for (TubeOrder tubeOrder : tubeOrders) {
                Optional<TubeTypeAttribute> tubeTypeAttrOpt = tubeTypeAttrRepo.findByIdAndIsDeletedFalse(tubeOrder.getId());
                if (tubeTypeAttrOpt.isPresent()) {
                    TubeTypeAttribute tubeTypeAttr = tubeTypeAttrOpt.get();
                    tubeTypeAttr.setDisplayOrder(tubeOrder.getDisplayOrder());
                    tubeTypeAttr.setModifiedBy(accountId);
                    tubeTypeAttr.setModifiedAt(TimeUtils.getNowUtc());
                    tubeTypeAttrRepo.save(tubeTypeAttr);
                }
            }
        } else if (req.getTubeOrderType() == TubeOrderType.TUBE_STATUS.ordinal()) {
            for (TubeOrder tubeOrder : tubeOrders) {
                Optional<TubeTypeStatus> tubeTypeStatusOpt = tubeTypeStatusRepo.findByIdAndIsDeletedFalse(tubeOrder.getId());
                if (tubeTypeStatusOpt.isPresent()) {
                    TubeTypeStatus tubeTypeStatus = tubeTypeStatusOpt.get();
                    tubeTypeStatus.setDisplayOrder(tubeOrder.getDisplayOrder());
                    tubeTypeStatus.setModifiedBy(accountId);
                    tubeTypeStatus.setModifiedAt(TimeUtils.getNowUtc());
                    tubeTypeStatusRepo.save(tubeTypeStatus);
                }
            }
        }
        return;
    }

    public String getDrainageTubeType() {
        return DRAINAGE_TUBE_TYPE;
    }

    public void updateMonitoringParam(String tubeType, String tubeName) {
        if (!tubeType.equals(DRAINAGE_TUBE_TYPE)) return;

        String paramCode = getMonitoringParamCode(tubeType, tubeName);
        if (monitoringParamRepo.findByCode(paramCode).isPresent()) return;

        Integer displayOrder = 1;
        {
            MonitoringParam lastParam = monitoringParamRepo.findTopByOrderByDisplayOrderDesc().orElse(null);
            if (lastParam != null) displayOrder = lastParam.getDisplayOrder() + 1;
        }

        MonitoringParam mp = MonitoringParam.builder()
            .code(paramCode)
            .name(tubeName)
            .nameEn(StrUtils.toPinyin(tubeName))
            .typePb(defaultDrainageParam.getTypePb())
            .balanceType(defaultDrainageParam.getBalanceType())
            .category(defaultDrainageParam.getCategory())
            .uiModalCode(defaultDrainageParam.getUiModalCode())
            .displayOrder(displayOrder)
            .build();
        monitoringParamRepo.save(mp);
    }

    private List<String> splitString(String str) {
        return StrUtils.isBlank(str) ? List.of() : List.of(str.split(","));
    }

    private final String DRAINAGE_TUBE_TYPE;
    private final MonitoringParam defaultDrainageParam;

    @Getter
    private TubePB tubePb;
    private final TubeTypeRepository tubeTypeRepo;
    private final TubeTypeAttributeRepository tubeTypeAttrRepo;
    private final TubeTypeStatusRepository tubeTypeStatusRepo;
    private final MonitoringParamRepository monitoringParamRepo;
}