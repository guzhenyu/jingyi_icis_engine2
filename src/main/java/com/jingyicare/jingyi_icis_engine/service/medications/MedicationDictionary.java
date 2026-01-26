package com.jingyicare.jingyi_icis_engine.service.medications;

import java.time.LocalDateTime;
import java.util.*;
import java.util.regex.*;
import java.util.stream.Collectors;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import lombok.*;
import lombok.extern.slf4j.Slf4j;

import com.jingyicare.jingyi_icis_engine.proto.config.IcisMedication.*;

import com.jingyicare.jingyi_icis_engine.entity.medications.*;
import com.jingyicare.jingyi_icis_engine.repository.medications.*;
import com.jingyicare.jingyi_icis_engine.service.ConfigProtoService;
import com.jingyicare.jingyi_icis_engine.utils.*;

@Service
@Slf4j
public class MedicationDictionary {
    public static Double calculateLiquidVolume(String spec, Double dose, String doseUnit) {
        if (spec == null || dose == null || doseUnit == null) return 0.0;

        if (doseUnit.equals("ml")) return dose;

        // spec: 0.5g/支
        Pattern pattern1 = Pattern.compile("(\\d+(\\.\\d+)?)ml(:|/)(支|瓶)");
        Matcher matcher = pattern1.matcher(spec);
        if (matcher.matches()) {
            if (!doseUnit.equals(matcher.group(4))) return 0.0;
            return dose * Double.parseDouble(matcher.group(1));
        }

        // spec: 1ml:2g
        Pattern pattern2 = Pattern.compile("(\\d+(\\.\\d+)?)ml(:|/)(\\d+(\\.\\d+)?)?(g|mg)");
        matcher = pattern2.matcher(spec);
        if (matcher.matches()) {
            if (!doseUnit.equals("g") && !doseUnit.equals("mg")) return 0.0;
            return dose * getWeightMultiplier(matcher.group(1), matcher.group(4), matcher.group(6), doseUnit);
        }

        // spec: 10ml:1g(10%)/支
        Pattern pattern3 = Pattern.compile("(\\d+(\\.\\d+)?)ml(:|/)(\\d+(\\.\\d+)?)?(g|mg)(\\(\\d+(\\.\\d+)?\\%\\))?(\\*|/|:)(\\d+)?(支|瓶)");
        matcher = pattern3.matcher(spec);
        if (matcher.matches()) {
            if (doseUnit.equals(matcher.group(11))) {
                return dose * Double.parseDouble(matcher.group(1)) /* xx ml */;
            } else if (doseUnit.equals("g") || doseUnit.equals("mg")) {
                return dose * getWeightMultiplier(matcher.group(1), matcher.group(4), matcher.group(6), doseUnit);
            }
        }

        // spec: 0.2g:20ml(10%)/支
        Pattern pattern4 = Pattern.compile("(\\d+(\\.\\d+)?)?(g|mg)(:|/)(\\d+(\\.\\d+)?)ml(\\(\\d+(\\.\\d+)?\\%\\))?(\\*|/|:)(\\d+)?(支|瓶)");;
        matcher = pattern4.matcher(spec);
        if (matcher.matches()) {
            if (doseUnit.equals(matcher.group(11))) {
                return dose * Double.parseDouble(matcher.group(5)) /* xx ml */;
            } else if (doseUnit.equals("g") || doseUnit.equals("mg")) {
                return dose * getWeightMultiplier(matcher.group(5), matcher.group(1), matcher.group(3), doseUnit);
            } else {
                return 0.0;
            }
        }

        return 0.0;
    }

    public static Double getWeightMultiplier(
        String specMl, String specWeight, String specWeightUnit, String doseUnit
    ) {
        final Double ml = Double.parseDouble(specMl);
        final Double divisor = (specWeight == null || specWeight.isEmpty()) ? 1.0
            : Double.parseDouble(specWeight);
        final Double multiplier = specWeightUnit.equals(doseUnit) ? 1.0 : (doseUnit.equals("g") ? 1000 : 0.001);
        return ml * multiplier / divisor;
    }

    public static Double calculateSolidMg(String spec, Double dose, String doseUnit) {
        if (spec == null || dose == null || doseUnit == null) return 0.0;

        // case 1: 如果单位本身就是重量单位，直接返回
        Double solidMg = toMg(dose, doseUnit);
        if (solidMg > 0) return solidMg;

        // 去掉所有空格，提高容错性
        spec = spec.trim().replaceAll("\\s+", "");
        // 转化成小写，提高匹配成功率
        spec = spec.toLowerCase();

        // case 2: 直接剂量，例如 "10mg/片"、"0.25g/粒"、"500μg/胶囊"
        Pattern p1 = Pattern.compile("(\\d+(\\.\\d+)?)(mg|g|μg|ug)(/)(片|粒|胶囊|袋)");
        Matcher m = p1.matcher(spec);
        if (m.matches()) {
            if (!doseUnit.equals(m.group(5))) return 0.0;
            double weight = Double.parseDouble(m.group(1));
            String unit = m.group(3);
            return dose * toMg(weight, unit);
        }

        // case 3: 总量*包装数，例如 "2g*10片"、"500mg*12粒"、"100μg*30片"
        Pattern p2 = Pattern.compile("(\\d+(\\.\\d+)?)(mg|g|μg|ug)(\\*|x)(\\d+)(片|粒|胶囊|袋)");
        m = p2.matcher(spec);
        if (m.matches()) {
            if (!doseUnit.equals(m.group(6))) return 0.0;
            double totalWeight = Double.parseDouble(m.group(1));
            String weightUnit = m.group(3);
            int packCount = Integer.parseInt(m.group(5));
            double perUnit = toMg(totalWeight, weightUnit) / packCount;
            return dose * perUnit;
        }

        return 0.0;
    }

    // 工具方法：统一换算成 mg
    private static double toMg(double val, String unitRaw) {
        String unit = (unitRaw == null) ? "" : unitRaw.trim().toLowerCase();
        if (unit.equalsIgnoreCase("g")) return val * 1000;
        if (unit.equalsIgnoreCase("μg") || unit.equalsIgnoreCase("ug")) return val * 0.001; // 支持 μg / ug
        if (unit.equalsIgnoreCase("mg")) return val;
        return 0.0;
    }

    private static final Pattern MED_RATE_PATTERN1 = Pattern.compile("^(ug|mg|g)/kg/(h|min)$");
    private static final Pattern MED_RATE_PATTERN2 = Pattern.compile("^(ug|mg|g)/(h|min)$");
    private static final Pattern MED_RATE_PATTERN3 = Pattern.compile("^ml/(h|min)$");
    private static final Pattern MED_RATE_PATTERN4 = Pattern.compile("^u/h$"); // 药速和速度一样

    public static DosageGroupExtPB calcMedRate(DosageGroupExtPB extInput, boolean useAdminRateAsInput) {
        if (extInput == null) return null;
        double totalMl = extInput.getTotalMl();
        double solidMg = toMg(extInput.getSolidAmount(), extInput.getSolidUnit());
        double weightKg = extInput.getWeightKg();
        String doseRateUnit = normDoseRateUnit(extInput.getDoseRateUnit());

        // 浓度 mg/ml（跨维度换算的关键）
        final double concMgPerMl = (totalMl > 0 && solidMg > 0) ? (solidMg / totalMl) : 0.0;

        // 进行匹配
        Matcher m1 = MED_RATE_PATTERN1.matcher(doseRateUnit);
        Matcher m2 = MED_RATE_PATTERN2.matcher(doseRateUnit);
        Matcher m3 = MED_RATE_PATTERN3.matcher(doseRateUnit);
        Matcher m4 = MED_RATE_PATTERN4.matcher(doseRateUnit);

        double toDoserMultiplier = 0;
        if (m1.matches()) {  // 情况1：从mg/kg/h转化到对应的药速单位
            if (weightKg <= Consts.EPS || concMgPerMl <= Consts.EPS) {
                log.error("calcMedRate: missing weight or concentration info: weightKg={}, concMgPerMl={}",
                    weightKg, concMgPerMl);
                return null;  // 缺体重或浓度信息，无法换算
            }

            /*
             * admin_rate(ml/h) =[* (浓度mg/ml) / (体重kg)]=> dose_rate1(mg/kg/h) =[ / toMg]=> dose_rate2(e.g., ug/kg/h) =[ / (min? 60 : 1)] => dose_rate(e.g., ug/kg/min)
             * 反过来：
             * dose_rate(e.g., ug/kg/min) =[ * (min? 60 : 1)] => dose_rate2(e.g., ug/kg/h) =[ * toMg]=> dose_rate1(mg/kg/h) =[ * (体重kg) / (浓度mg/ml)]=> admin_rate(ml/h)
             **/
            toDoserMultiplier = concMgPerMl / weightKg; // 浓度 / 体重
            toDoserMultiplier /= toMg(1.0, m1.group(1)); // 目标单位的质量部分
            toDoserMultiplier /= (m1.group(2).equals("min") ? 60.0 : 1.0); // 目标单位的时间部分
        } else if (m2.matches()) {  // 情况2：从mg/h转化到对应的药速单位
            if (concMgPerMl <= Consts.EPS) {
                log.error("calcMedRate: missing concentration info: concMgPerMl={}", concMgPerMl);
                return null;  // 缺浓度信息，无法换算
            }
            /*
             * admin_rate(ml/h) =[* (浓度mg/ml)]=> dose_rate1(mg/h) =[ / toMg]=> dose_rate2(e.g., ug/h) =[ / (min? 60 : 1)] => dose_rate(e.g., ug/min)
             * 反过来：
             * dose_rate(e.g., ug/min) =[ * (min? 60 : 1)] => dose_rate2(e.g., ug/h) =[ * toMg]=> dose_rate1(mg/h) =[/ (浓度mg/ml)]=> admin_rate(ml/h)
             **/
            toDoserMultiplier = concMgPerMl; // 浓度
            toDoserMultiplier /= toMg(1.0, m2.group(1)); // 目标单位的质量部分
            toDoserMultiplier /= (m2.group(2).equals("min") ? 60.0 : 1.0); // 目标单位的时间部分
        } else if (m3.matches()) {  // 情况3：从ml/h转化到对应的药速单位
            /*
             * admin_rate(ml/h) =[ / (min? 60 : 1)] => dose_rate(e.g., ml/min)
             * 反过来：
             * dose_rate(e.g., ml/min) =[ * (min? 60 : 1)] => admin_rate(ml/h)
             **/
            toDoserMultiplier = 1.0 / (m3.group(1).equals("min") ? 60.0 : 1.0); // 目标单位的时间部分
        } else if (m4.matches()) {  // 情况4：u/h，单位和药速一样
            toDoserMultiplier = 1.0; 
        }
        if (toDoserMultiplier <= Consts.EPS) {
            log.error("calcMedRate: cannot parse doseRateUnit: {}", doseRateUnit);
            return null;
        }

        if (useAdminRateAsInput) {
            return extInput.toBuilder()
                .setDoseRateAmount(ValueMetaUtils.normalize(
                    extInput.getAdministrationRate() * toDoserMultiplier,
                    3
                ))
                .build();
        } else {
            return extInput.toBuilder()
                .setAdministrationRate(ValueMetaUtils.normalize(
                    extInput.getDoseRateAmount() / toDoserMultiplier,
                    3
                ))
                .build();
        }
    }

    private static String normDoseRateUnit(String dsu) {
        if (dsu == null) return "";
        String x = dsu.trim().toLowerCase().replaceAll("\\s+", "");
        // 单位别名归一
        x = x.replace("μg", "ug").replace("mcg", "ug");
        x = x.replace("hour", "h").replace("hr", "h");
        x = x.replace("minutes", "min").replace("minute", "min");
        return x;
    }

    public MedicationDictionary(
        @Autowired ConfigProtoService protoService,
        @Autowired MedicationConfig medConfig,
        @Autowired MedicationRepository medRepo,
        @Autowired MedicationHistoryRepository medHisRepo,
        @Autowired AdministrationRouteRepository routeRepo,
        @Autowired AdministrationRouteGroupRepository routeGroupRepo,
        @Autowired IntakeTypeRepository intakeTypeRepo
    ) {
        this.ROUTE_GROUP_OTHERS = protoService.getConfig().getMedication().getEnums()
            .getAdministrationRouteGroupOthers().getId();
        this.INTAKE_TYPE_NOT_COUNTING = 0;
        this.medConfig = medConfig;
        this.medRepo = medRepo;
        this.medHisRepo = medHisRepo;
        this.routeRepo = routeRepo;
        this.routeGroupRepo = routeGroupRepo;
        this.intakeTypeRepo = intakeTypeRepo;
    }

    // 更新药品字典
    @Transactional
    public void updateIfNecessary(String deptId, MedicalOrder order) {
        if (order == null) return;

        // Not null fields.
        final String code = order.getOrderCode();
        final String name = order.getOrderName();
        final String spec = order.getSpec();
        final Double dose = order.getDose();
        final String doseUnit = order.getDoseUnit();
        final String medicationType = order.getMedicationType();
        final String orderId = order.getOrderId();
        if (code == null || name == null || spec == null ||
            dose == null || doseUnit == null || orderId == null
        ) {
            log.warn("Invalid order: {}", order);
            return;
        }

        Medication med = updateMedicationRepository(code, name, spec, dose, doseUnit, medicationType, orderId, null);
        order.setShouldCalculateRate(med.getShouldCalculateRate() != null ? med.getShouldCalculateRate() : false);
        updateRouteRepository(deptId, order.getAdministrationRouteCode(), order.getAdministrationRouteName());
    }

    @Transactional
    public void updateIfNecessary(
        String deptId, MedicationDosageGroupPB dosageGroup, String routeCode, String routeName
    ) {
        if (dosageGroup == null) return;

        for (MedicationDosagePB dosage : dosageGroup.getMdList()) {
            final String code = dosage.getCode();
            final String name = dosage.getName();
            final String spec = dosage.getSpec();
            final Double dose = dosage.getDose();
            final String doseUnit = dosage.getDoseUnit();
            if (code == null || name == null || spec == null ||
                dose == null || doseUnit == null
            ) {
                log.warn("Invalid dosage: {}", dosage);
                continue;
            }
            final Boolean shouldCalculateRate = dosage.getShouldCalculateRate();

            updateMedicationRepository(code, name, spec, dose, doseUnit, dosage.getType(), "from manual entry", shouldCalculateRate);
        }
        updateRouteRepository(deptId, routeCode, routeName);
    }

    @Transactional
    public List<Medication> findUnconfirmedMedications() {
        return medRepo.findByConfirmed(false);
    }

    @Transactional
    public List<MedicationPB> getMedicationList() {
        List<MedicationPB> medPbList = new ArrayList<>();
        for (Medication med : medRepo.findAll()) {
            boolean shouldCalculateRate = med.getShouldCalculateRate() == null ? false : med.getShouldCalculateRate();
            MedicationDosagePB medDosagePb = MedicationDosagePB.newBuilder()
                .setCode(med.getCode())
                .setName(med.getName())
                .setSpec(med.getSpec() == null ? "" : med.getSpec())
                .setDose(med.getDose() == null ? 0.0 : med.getDose())
                .setDoseUnit(med.getDoseUnit() == null ? "" : med.getDoseUnit())
                .setIntakeVolMl(calculateLiquidVolume(med.getSpec(), med.getDose(), med.getDoseUnit()))
                .setShouldCalculateRate(shouldCalculateRate)
                .setNameInitials(StrUtils.toPinyinInitials(med.getName()).toLowerCase())
                .build();
            MedicationPB medPb = MedicationPB.newBuilder()
                .setCode(med.getCode())
                .setName(med.getName())
                .setSpec(med.getSpec())
                .setDose(med.getDose())
                .setDoseUnit(med.getDoseUnit())
                .setPackageCount(med.getPackageCount() == null ? 0 : med.getPackageCount())
                .setPackageUnit(med.getPackageUnit() == null ? "" : med.getPackageUnit())
                .setShouldCalculateRate(shouldCalculateRate)
                .setMedicationType(med.getType() == null ? 0 : med.getType())
                .setCompany(med.getCompany() == null ? "" : med.getCompany())
                .setPrice(med.getPrice() == null ? 0.0 : med.getPrice())
                .setDosage(medDosagePb)
                .build();
            medPbList.add(medPb);
        }
        return medPbList;
    }

    public List<MedicationDosagePB> lookupMedication(String query) {
        final String lowerCaseQuery = query == null ? "" : query.toLowerCase();

        List<MedicationDosagePB> resList = new ArrayList<>();
        for (MedicationPB medPb : getMedicationList()) {
            MedicationDosagePB med = medPb.getDosage();
            if (lowerCaseQuery.isEmpty()) {
                resList.add(med);
                continue;
            }

            if (med.getCode().toLowerCase().contains(lowerCaseQuery) ||
                med.getName().toLowerCase().contains(lowerCaseQuery) ||
                med.getNameInitials().contains(lowerCaseQuery)
            ) {
                resList.add(med);
            }
        }
        resList = resList.stream()
            .sorted(Comparator.comparing(MedicationDosagePB::getNameInitials))
            .toList();

        return resList;
    }

    public List<MedicationPB> lookupMedicationPB(String query) {
        final String lowerCaseQuery = query == null ? "" : query.toLowerCase();

        List<MedicationPB> resList = new ArrayList<>();
        for (MedicationPB medPb : getMedicationList()) {
            MedicationDosagePB med = medPb.getDosage();
            if (lowerCaseQuery.isEmpty()) {
                resList.add(medPb);
                continue;
            }

            if (med.getCode().toLowerCase().contains(lowerCaseQuery) ||
                med.getName().toLowerCase().contains(lowerCaseQuery) ||
                med.getNameInitials().contains(lowerCaseQuery)
            ) {
                resList.add(medPb);
            }
        }
        resList = resList.stream()
            .sorted(Comparator.comparing(medPb -> medPb.getDosage().getNameInitials()))
            .toList();

        return resList;
    }

    @Transactional
    private Medication updateMedicationRepository(
        String code, String name, String spec, Double dose,
        String doseUnit, String medicationType, String medicalOrderId,
        Boolean shouldCalculateRate
    ) {
        Medication med = medRepo.findByCode(code).orElse(null);
        if (med != null) {
            if (Objects.equals(med.getName(), name) &&
                Objects.equals(med.getSpec(), spec) &&
                Objects.equals(med.getDoseUnit(), doseUnit)) return med;
        }

        final LocalDateTime createdAt = TimeUtils.getNowUtc();
        MedicationHistory medHis = new MedicationHistory();
        medHis.setCode(code);
        medHis.setName(name);
        medHis.setSpec(spec);
        medHis.setDose(dose);
        medHis.setDoseUnit(doseUnit);
        // medHis.setType(...medicationType);
        if (shouldCalculateRate != null) {
            medHis.setShouldCalculateRate(shouldCalculateRate);
        }
        medHis.setCreatedAt(createdAt);
        medHis.setSource("medical_orders");
        medHis.setMedicalOrderId(medicalOrderId);
        medHisRepo.save(medHis);

        if (med == null) {
            med = new Medication();
            med.setDose(dose);
        }
        med.setCode(code);
        med.setName(name);
        med.setSpec(spec);
        med.setDoseUnit(doseUnit);
        // med.setType(...medicationType);
        if (shouldCalculateRate != null) {
            med.setShouldCalculateRate(shouldCalculateRate);
        }
        med.setCreatedAt(createdAt);
        med.setConfirmed(false);
        med.setConfirmedBy("");
        medRepo.save(med);
        return med;
    }

    @Transactional
    public AdministrationRoute findRouteByCode(String deptId, String routeCode) {
        AdministrationRoute route = routeRepo.findByDeptIdAndCode(deptId, routeCode).orElse(null);
        if (route != null) return route;

        return defaultRoute(deptId, routeCode, routeCode);
    }

    @Transactional
    public List<AdministrationRoutePB> lookupAdministrationRoute(String deptId, String query) {
        final String lowerCaseQuery = query == null ? "" : query.toLowerCase();

        // 获取给药途径分组的名称，入量类型的名称
        Map<Integer, String> routeGroupMap = routeGroupRepo.findAll().stream().collect(
            Collectors.toMap(AdministrationRouteGroup::getId, AdministrationRouteGroup::getName));
        Map<Integer, String> intakeTypeMap = intakeTypeRepo.findAll().stream()
            .collect(Collectors.toMap(IntakeType::getId, IntakeType::getName));

        // 生成结果
        List<AdministrationRoutePB> resList = new ArrayList<>();
        for (AdministrationRoute r : routeRepo.findByDeptId(deptId)) {
            AdministrationRoutePB route = toRoutePB(
                r, routeGroupMap.getOrDefault(r.getGroupId(), ""),
                intakeTypeMap.getOrDefault(r.getIntakeTypeId(), "")
            );
            if (route == null) continue;

            if (lowerCaseQuery.isEmpty()) {
                resList.add(route);
                continue;
            }
            if (route.getCode().toLowerCase().contains(lowerCaseQuery) ||
                route.getName().toLowerCase().contains(lowerCaseQuery) ||
                route.getNameInitials().contains(lowerCaseQuery)
            ) {
                resList.add(route);
            }
        }
        return resList;
    }

    private AdministrationRoutePB toRoutePB(
        AdministrationRoute route, String routeGroupName, String intakeTypeName
    ) {
        if (route == null) return null;

        return AdministrationRoutePB.newBuilder()
            .setCode(route.getCode())
            .setName(route.getName())
            .setIsContinuous(route.getIsContinuous() ? 1 : 0)
            .setGroupId(route.getGroupId())
            .setIntakeTypeId(route.getIntakeTypeId())
            .setNameInitials(StrUtils.toPinyinInitials(route.getName()).toLowerCase())
            .setGroup(routeGroupName)
            .setIntakeType(intakeTypeName)
            .setId(route.getId() == null ? 0 : route.getId())
            .build();
    }

    @Transactional
    public Integer getAdministrationRouteGroupId(String deptId, String routeCode) {
        AdministrationRoute route = routeRepo.findByDeptIdAndCode(deptId, routeCode).orElse(null);
        if (route == null) return ROUTE_GROUP_OTHERS;

        Integer groupId = route.getGroupId();
        if (groupId == null) return ROUTE_GROUP_OTHERS;

        for (AdministrationRouteGroup group : routeGroupRepo.findAll()) {
            if (group.getId().equals(groupId)) {
                return groupId;
            }
        }
        return ROUTE_GROUP_OTHERS;
    }

    private void updateRouteRepository(String deptId, String routeCode, String routeName) {
        AdministrationRoute route = routeRepo.findByDeptIdAndCode(deptId, routeCode).orElse(null);
        if (route != null) return;
        routeRepo.save(defaultRoute(deptId, routeCode, routeName));
    }

    private AdministrationRoute defaultRoute(String deptId, String routeCode, String routeName) {
        AdministrationRoute route = new AdministrationRoute();
        route.setDeptId(deptId);
        route.setCode(routeCode);
        route.setName(routeName);
        route.setIsContinuous(false);
        route.setGroupId(ROUTE_GROUP_OTHERS);
        route.setIntakeTypeId(INTAKE_TYPE_NOT_COUNTING);
        route.setIsValid(true);
        return route;
    }

    private Integer ROUTE_GROUP_OTHERS;
    private Integer INTAKE_TYPE_NOT_COUNTING;
    private final MedicationConfig medConfig;
    private final MedicationRepository medRepo;
    private final MedicationHistoryRepository medHisRepo;
    private final AdministrationRouteRepository routeRepo;
    private final AdministrationRouteGroupRepository routeGroupRepo;
    private final IntakeTypeRepository intakeTypeRepo;
}