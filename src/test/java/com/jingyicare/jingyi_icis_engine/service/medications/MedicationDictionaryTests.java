package com.jingyicare.jingyi_icis_engine.service.medications;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

import java.time.LocalDateTime;
import java.util.Optional;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import com.jingyicare.jingyi_icis_engine.proto.config.IcisMedication.*;

import com.jingyicare.jingyi_icis_engine.entity.medications.*;
import com.jingyicare.jingyi_icis_engine.repository.medications.*;
import com.jingyicare.jingyi_icis_engine.service.ConfigProtoService;
import com.jingyicare.jingyi_icis_engine.testutils.*;
import com.jingyicare.jingyi_icis_engine.utils.*;

public class MedicationDictionaryTests extends TestsBase {
    @Test
    public void testUpdateFailedByIncompleteData() {
        final String medCode = "med_mdtcode_1";
        // 检查测试环境
        assertThat(medRepo.findByCode(medCode)).isEmpty();
        assertThat(medHisRepo.findByCode(medCode)).isEmpty();

        // name is null
        MedicalOrder order = newMedicalOrder(
            medCode, null, "med_spec_1", 1.0, "mg", "med_type_1", "order_id_1", "route_1", "route_1");
        medDict.updateIfNecessary(deptId, order);
        assertThat(medRepo.findByCode(medCode)).isEmpty();
        assertThat(medHisRepo.findByCode(medCode)).isEmpty();

        // spec is null.
        order = newMedicalOrder(
            medCode, "med_name_1", null, 1.0, "mg", "med_type_1", "order_id_1", "route_1", "route_1");
        medDict.updateIfNecessary(deptId, order);
        assertThat(medRepo.findByCode(medCode)).isEmpty();
        assertThat(medHisRepo.findByCode(medCode)).isEmpty();

        // dose is null.
        order = newMedicalOrder(
            medCode, "med_name_1", "med_spec_1", null, "mg", "med_type_1", "order_id_1", "route_1", "route_1");
        medDict.updateIfNecessary(deptId, order);
        assertThat(medRepo.findByCode(medCode)).isEmpty();
        assertThat(medHisRepo.findByCode(medCode)).isEmpty();

        // doseUnit is null.
        order = newMedicalOrder(
            medCode, "med_name_1", "med_spec_1", 1.0, null, "med_type_1", "order_id_1", "route_1", "route_1");
        medDict.updateIfNecessary(deptId, order);
        assertThat(medRepo.findByCode(medCode)).isEmpty();
        assertThat(medHisRepo.findByCode(medCode)).isEmpty();

        // orderId is null.
        order = newMedicalOrder(
            medCode, "med_name_1", "med_spec_1", 1.0, "mg", "med_type_1", null, "route_1", "route_1");
        medDict.updateIfNecessary(deptId, order);
        assertThat(medRepo.findByCode(medCode)).isEmpty();
        assertThat(medHisRepo.findByCode(medCode)).isEmpty();

        // All fields are ready.
        order = newMedicalOrder(
            medCode, "med_name_1", "med_spec_1", 1.0, "mg", "med_type_1", "order_id_1", "route_1", "route_1");
        medDict.updateIfNecessary(deptId, order);
        assertThat(medRepo.findByCode(medCode)).isNotEmpty();
        assertThat(medHisRepo.findByCode(medCode)).isNotEmpty();
    }

    @Test
    public void testUpdateSuccess() {
        final String medCode = "med_mdtcode_2";
        // 检查测试环境
        assertThat(medRepo.findByCode(medCode)).isEmpty();
        assertThat(medHisRepo.findByCode(medCode)).isEmpty();

        // 通过医嘱新增一条药品
        MedicalOrder order = newMedicalOrder(
            medCode, "med_name_2", "med_spec_2", 2.0, "mg", "med_type_2", "order_id_2", "route_1", "route_1");
        medDict.updateIfNecessary(deptId, order);

        Optional<Medication> optMed = medRepo.findByCode(medCode);
        assertThat(optMed).isNotEmpty();
        Medication med = optMed.get();
        assertThat(med.getName()).isEqualTo("med_name_2");
        assertThat(med.getSpec()).isEqualTo("med_spec_2");
        assertThat(med.getDose()).isEqualTo(2.0);
        assertThat(med.getDoseUnit()).isEqualTo("mg");
        final LocalDateTime medCreatedAt = med.getCreatedAt();
        assertThat(med.getConfirmed()).isFalse();
        assertThat(med.getConfirmedBy()).isEqualTo("");

        List<MedicationHistory> medHisList = medHisRepo.findByCode(medCode);
        assertThat(medHisList).hasSize(1);
        MedicationHistory medHis = medHisList.get(0);
        assertThat(medHis.getName()).isEqualTo("med_name_2");
        assertThat(medHis.getSpec()).isEqualTo("med_spec_2");
        assertThat(medHis.getDose()).isEqualTo(2.0);
        assertThat(medHis.getDoseUnit()).isEqualTo("mg");
        assertThat(medHis.getCreatedAt()).isEqualTo(medCreatedAt);
        assertThat(medHis.getSource()).isEqualTo("medical_orders");
        assertThat(medHis.getMedicalOrderId()).isEqualTo("order_id_2");

        // 确认医嘱
        med.setConfirmed(true);
        med.setConfirmedBy("system");
        med.setCompany("company");
        medRepo.save(med);
        optMed = medRepo.findByCode(medCode);
        assertThat(optMed).isNotEmpty();
        med = optMed.get();
        assertThat(med.getName()).isEqualTo("med_name_2");
        assertThat(med.getCompany()).isEqualTo("company");
        assertThat(med.getConfirmed()).isTrue();
        assertThat(med.getConfirmedBy()).isEqualTo("system");

        // 通过医嘱更新药品信息
        order = newMedicalOrder(
            medCode, "med_name_3", "med_spec_3", 3.0, "ug", "med_type_3", "order_id_3", "route_1", "route_1");
        medDict.updateIfNecessary(deptId, order);
        optMed = medRepo.findByCode(medCode);
        assertThat(optMed).isNotEmpty();
        med = optMed.get();
        assertThat(med.getName()).isEqualTo("med_name_3");
        assertThat(med.getSpec()).isEqualTo("med_spec_3");
        assertThat(med.getDose()).isEqualTo(2.0);  // 不更新剂量
        assertThat(med.getDoseUnit()).isEqualTo("ug");
        assertThat(med.getCompany()).isEqualTo("company");
        assertThat(med.getConfirmed()).isFalse();
        assertThat(med.getConfirmedBy()).isEqualTo("");

        medHisList = medHisRepo.findByCode(medCode);
        assertThat(medHisList).hasSize(2);
        medHis = medHisList.get(0);
        assertThat(medHis.getName()).isEqualTo("med_name_3");
        assertThat(medHis.getSpec()).isEqualTo("med_spec_3");
        assertThat(medHis.getDose()).isEqualTo(3.0);
        assertThat(medHis.getDoseUnit()).isEqualTo("ug");
        assertThat(medHis.getCreatedAt()).isEqualTo(med.getCreatedAt());
        assertThat(medHis.getSource()).isEqualTo("medical_orders");
        assertThat(medHis.getMedicalOrderId()).isEqualTo("order_id_3");

        // 对于一致的记录，不做更新
        medDict.updateIfNecessary(deptId, order);
        medHisList = medHisRepo.findByCode(medCode);
        assertThat(medHisList).hasSize(2);
        medHis = medHisList.get(0);
        assertThat(medHis.getName()).isEqualTo("med_name_3");
        medHis = medHisList.get(1);
        assertThat(medHis.getName()).isEqualTo("med_name_2");
    }

    @Test
    public void testQueryMedications() {
        Medication medication = MedicationTestUtils.newMedication("med_Code_101", "med_mdt氯化钙_Cacl", "100ml:2mg", 3.0, "mg");
        medRepo.save(medication);
        medication = MedicationTestUtils.newMedication("med_Code_102", "med_mdt氯化钠_Nacl", "100ml:2mg", 3.0, "mg");
        medRepo.save(medication);

        medConfig.refresh();

        List<MedicationDosagePB> dosages = medDict.lookupMedication("mdt氯化钠");
        assertThat(dosages).hasSize(1);
        MedicationDosagePB dosage = dosages.get(0);
        assertThat(dosage.getCode()).isEqualTo("med_Code_102");
        assertThat(dosage.getName()).isEqualTo("med_mdt氯化钠_Nacl");
        assertThat(dosage.getSpec()).isEqualTo("100ml:2mg");
        assertThat(dosage.getDose()).isEqualTo(3.0);
        assertThat(dosage.getDoseUnit()).isEqualTo("mg");
        assertThat(dosage.getIntakeVolMl()).isEqualTo(150.0);
        assertThat(dosage.getNameInitials()).isEqualTo("med_mdtlhn_nacl");

        dosages = medDict.lookupMedication("mdt氯化钠_n");
        assertThat(dosages).hasSize(1);

        dosages = medDict.lookupMedication("code_102");
        assertThat(dosages).hasSize(1);

        dosages = medDict.lookupMedication("氯化钾");
        assertThat(dosages).hasSize(0);

        dosages = medDict.lookupMedication("med_mdtlh");
        assertThat(dosages).hasSize(2);

        dosages = medDict.lookupMedication("");
        assertThat(dosages.size() >= 2).isTrue();

        dosages = medDict.lookupMedication(null);
        assertThat(dosages.size() >= 2).isTrue();
    }

    @Test
    public void testQueryRoutes() {
        final Integer ROUTE_GROUP_OTHERS = protoService.getConfig().getMedication()
            .getEnums().getAdministrationRouteGroupOthers().getId();
        final Integer INTAKE_TYPE_INTRAVENOUS = protoService.getConfig().getMedication()
            .getIntakeTypes().getIntakeType(1).getId();

        AdministrationRoute route = MedicationTestUtils.newAdministrationRoute(
            deptId, "admin_Code_101"/*code*/, "admin_Name_101"/*name*/, true/*isContinuous*/, ROUTE_GROUP_OTHERS/*groupId*/,
            INTAKE_TYPE_INTRAVENOUS/*intakeTypeId*/, true/*isValid*/
        );
        routeRepo.save(route);

        route = MedicationTestUtils.newAdministrationRoute(
            deptId, "admin_Code_102"/*code*/, "admin_Name_102"/*name*/, true/*isContinuous*/, ROUTE_GROUP_OTHERS/*groupId*/,
            INTAKE_TYPE_INTRAVENOUS/*intakeTypeId*/, true/*isValid*/
        );
        routeRepo.save(route);

        medConfig.refresh();

        List<AdministrationRoutePB> routes = medDict.lookupAdministrationRoute(deptId, "code_101");
        assertThat(routes).hasSize(1);

        routes = medDict.lookupAdministrationRoute(deptId, "code_102");
        assertThat(routes).hasSize(1);

        routes = medDict.lookupAdministrationRoute(deptId, "name_101");
        assertThat(routes).hasSize(1);

        routes = medDict.lookupAdministrationRoute(deptId, "code_10");
        assertThat(routes).hasSize(2);
    }

    private static class LiquidVolumeTestCase {
        String spec;
        Double dose;
        String doseUnit;
        Double expectedVol;

        LiquidVolumeTestCase(String spec, Double dose, String doseUnit, Double expectedVol) {
            this.spec = spec;
            this.dose = dose;
            this.doseUnit = doseUnit;
            this.expectedVol = expectedVol;
        }
    }

    @Test
    public void testCalculateLiquidVolume() {
        List<LiquidVolumeTestCase> caseList = List.of(
            // A. null & direct
            new LiquidVolumeTestCase(null, 10.0, "ml", 0.0),
            new LiquidVolumeTestCase("x", null, "ml", 0.0),
            new LiquidVolumeTestCase("x", 5.0, null, 0.0),
            new LiquidVolumeTestCase("随便写", 3.7, "ml", 3.7),

            // B. pattern1
            new LiquidVolumeTestCase("10ml/支", 2.0, "支", 20.0),
            new LiquidVolumeTestCase("0.5ml:瓶", 3.0, "瓶", 1.5),
            new LiquidVolumeTestCase("10ml/支", 2.0, "瓶", 0.0),
            new LiquidVolumeTestCase("10.25ml/支", 4.0, "支", 41.0),

            // C. pattern2
            new LiquidVolumeTestCase("1ml:2g", 3.0, "g", 1.5),
            new LiquidVolumeTestCase("2ml/500mg", 250.0, "mg", 1.0),
            new LiquidVolumeTestCase("1ml:2g", 500.0, "mg", 0.25),
            new LiquidVolumeTestCase("3ml/1g", 0.0, "g", 0.0),
            new LiquidVolumeTestCase("1ml:mg", 1000.0, "mg", 1000.0),
            new LiquidVolumeTestCase("2ml/500mg", 1.0, "kg", 0.0),

            // D. pattern3
            new LiquidVolumeTestCase("10ml:1g(10%)/支", 3.0, "支", 30.0),
            new LiquidVolumeTestCase("10ml:1g(10%)/支", 2.0, "g", 20.0),
            new LiquidVolumeTestCase("10ml:250mg*10瓶", 500.0, "mg", 20.0),
            new LiquidVolumeTestCase("10ml:1g/瓶", 2.0, "瓶", 20.0),
            new LiquidVolumeTestCase("5ml:500mg:10支", 2.0, "片", 0.0),
            new LiquidVolumeTestCase("10ml:1g(5%):12瓶", 1.0, "g", 10.0),
            new LiquidVolumeTestCase("10ml/1g*12支", 0.5, "g", 5.0),

            // E. invalid spec
            new LiquidVolumeTestCase("abc", 5.0, "g", 0.0),
            new LiquidVolumeTestCase("10 m l/支", 1.0, "支", 0.0),
            new LiquidVolumeTestCase("10ml/支  ", 1.0, "支", 0.0)
        );

        for (LiquidVolumeTestCase testCase : caseList) {
            double actualVol = medDict.calculateLiquidVolume(testCase.spec, testCase.dose, testCase.doseUnit);
            assertThat(actualVol).isCloseTo(testCase.expectedVol, within(Consts.EPS));
        }
    }

    private MedicalOrder newMedicalOrder(
        String code, String name, String spec,
        Double dose, String doseUnit, String medicationType, String orderId,
        String routeCode, String routeName
    ) {
        MedicalOrder order = new MedicalOrder();
        order.setDeptId(deptId);
        order.setOrderCode(code);
        order.setOrderName(name);
        order.setSpec(spec);
        order.setDose(dose);
        order.setDoseUnit(doseUnit);
        order.setMedicationType(medicationType);
        order.setOrderId(orderId);
        order.setAdministrationRouteCode(routeCode);
        order.setAdministrationRouteName(routeName);
        return order;
    }

    private static class SolidMgTestCase {
        final String spec;
        final Double dose;
        final String doseUnit;
        final Double expectedMg;
        SolidMgTestCase(String spec, Double dose, String doseUnit, Double expectedMg) {
            this.spec = spec;
            this.dose = dose;
            this.doseUnit = doseUnit;
            this.expectedMg = expectedMg;
        }
    }

    @Test
    public void testCalculateSolidMg() {
        List<SolidMgTestCase> caseList = List.of(
            // A. null & invalid early return
            new SolidMgTestCase(null, 2.0, "片", 0.0),
            new SolidMgTestCase("10mg/片", null, "片", 0.0),
            new SolidMgTestCase("10mg/片", 1.0, null, 0.0),

            // B. pattern1: 直接剂量  (数字 + 单位[mg|g|μg] + "/" + 形态[片|粒|胶囊|袋])
            new SolidMgTestCase("10mg/片", 2.0, "片", 20.0),
            new SolidMgTestCase("0.25g/粒", 4.0, "粒", 1000.0),        // 0.25g=250mg; 250*4=1000
            new SolidMgTestCase("500μg/胶囊", 2.0, "胶囊", 1.0),       // 500μg=0.5mg; 0.5*2=1.0
            new SolidMgTestCase("100 μg / 片", 10.0, "片", 1.0),       // 含空格也能匹配(先去空格)
            new SolidMgTestCase("10mg/片", 1.0, "粒", 0.0),            // 单位不匹配

            // C. pattern2: 总量 * 包装数  (数字 + 单位[mg|g|μg] + [*|x] + 包数 + 形态)
            new SolidMgTestCase("2g*10片", 3.0, "片", 600.0),          // 每片 200mg; 200*3=600
            new SolidMgTestCase("500mg*12粒", 6.0, "粒", 250.0),       // 每粒 41.666...mg; *6=250
            new SolidMgTestCase("100μg*30片", 15.0, "片", 0.05),        // 每30片 0.1mg;每15片0.05mg
            new SolidMgTestCase("1gx10胶囊", 5.0, "胶囊", 500.0),      // 使用 'x' 分隔；每粒 100mg; 100*5=500
            new SolidMgTestCase("2g*10片", 3.0, "粒", 0.0),            // 形态不匹配
            new SolidMgTestCase("2g*10片", 0.0, "片", 0.0),            // 剂量为0

            // D. invalid spec / 边界
            new SolidMgTestCase("abc", 5.0, "片", 0.0),                // 不匹配任何模式
            new SolidMgTestCase("10mmg/片", 1.0, "片", 0.0),           // 非法单位 "mmg"
            new SolidMgTestCase("10mg/片片", 1.0, "片", 0.0),          // 尾部形态非法
            new SolidMgTestCase("500uG/片", 2.0, "片", 1.0)            // "ug" 也支持(0.5mg*2=1.0)
        );

        for (SolidMgTestCase tc : caseList) {
            double actual = medDict.calculateSolidMg(tc.spec, tc.dose, tc.doseUnit);
            assertThat(actual).isCloseTo(tc.expectedMg, within(Consts.EPS));
        }
    }

    @Test
    public void testCalcMedRatePattern1() {
        // 用例1：ug/kg/min（规范化与时间换算验证）
        // total=100 ml, solid=100 μg -> conc = 0.001 mg/ml
        // weight=10 kg, admin=100 ml/h -> mg/h=0.1 -> mg/kg/h=0.01 -> 10 μg/kg/h -> 0.166666... μg/kg/min
        DosageGroupExtPB input1 = newDosageGroupExtPB(
            100.0, 100.0, "μg", 10.0, 100.0/*admin ml/h*/, 0.0, "ug/kg/min"
        );
        DosageGroupExtPB r1 = MedicationDictionary.calcMedRate(input1, /*useAdminRateAsInput*/ true);
        assertThat(r1).isNotNull();
        assertThat(r1.getDoseRateAmount()).isCloseTo(0.1666666667, within(Consts.EPS));

        // 用例2：mg/kg/h（无时间换算）
        // total=60 ml, solid=30 mg -> conc=0.5 mg/ml
        // weight=60 kg, admin=30 ml/h -> mg/h=15 -> mg/kg/h=0.25
        DosageGroupExtPB input2 = newDosageGroupExtPB(
            60.0, 30.0, "mg", 60.0, 30.0/*admin ml/h*/, 0.0, "mg/kg/h"
        );
        DosageGroupExtPB r2 = MedicationDictionary.calcMedRate(input2, true);
        assertThat(r2).isNotNull();
        assertThat(r2.getDoseRateAmount()).isCloseTo(0.25, within(Consts.EPS));

        // 用例3：规范化 mcg/kg/min + hr → h
        // total=50 ml, solid=25 mg -> conc=0.5 mg/ml
        // weight=50 kg, admin=60 ml/h -> mg/h=30 -> mg/kg/h=0.6 -> 600 μg/kg/h -> 10 μg/kg/min
        DosageGroupExtPB input3 = newDosageGroupExtPB(
            50.0, 25.0, "mg", 50.0, 60.0, 0.0, "mcg/kg/min"  // 会被规范化为 ug/kg/min
        );
        DosageGroupExtPB r3 = MedicationDictionary.calcMedRate(input3, true);
        assertThat(r3).isNotNull();
        assertThat(r3.getDoseRateAmount()).isCloseTo(10.0, within(Consts.EPS));

        // 反向用例1：与上面用例1互验 -> admin 应回到 100 ml/h
        DosageGroupExtPB input4 = newDosageGroupExtPB(
            100.0, 100.0, "μg",
            10.0,
            0.0,                     // admin 输入不用
            0.1666666667, "ug/kg/min"
        );
        DosageGroupExtPB r4 = MedicationDictionary.calcMedRate(input4, /*useAdminRateAsInput*/ false);
        assertThat(r4).isNotNull();
        assertThat(r4.getAdministrationRate()).isCloseTo(100.0, within(Consts.EPS));

        // 反向用例2：mg/kg/h
        // conc=0.5 mg/ml, weight=60 kg, dose=0.25 mg/kg/h -> mg/h=15 -> ml/h=30
        DosageGroupExtPB input5 = newDosageGroupExtPB(
            60.0, 30.0, "mg",
            60.0,
            0.0,
            0.25, "mg/kg/h"
        );
        DosageGroupExtPB r5 = MedicationDictionary.calcMedRate(input5, false);
        assertThat(r5).isNotNull();
        assertThat(r5.getAdministrationRate()).isCloseTo(30.0, within(Consts.EPS));

        // 缺体重
        DosageGroupExtPB a = newDosageGroupExtPB(100.0, 100.0, "μg", 0.0/*weightKg*/, 100.0, 0.0, "ug/kg/h");
        assertThat(MedicationDictionary.calcMedRate(a, true)).isNull();

        // 缺浓度（solid=0）
        DosageGroupExtPB b = newDosageGroupExtPB(100.0, 0.0/*solidAmount*/, "mg", 10.0, 100.0, 0.0, "ug/kg/min");
        assertThat(MedicationDictionary.calcMedRate(b, true)).isNull();
    }

    @Test
    public void testCalcMedRatePattern2() {
        // 用例1：mg/min
        // conc=2 mg/ml, admin=15 ml/h -> mg/h=30 -> mg/min=0.5
        DosageGroupExtPB input1 = newDosageGroupExtPB(
            100.0, 200.0, "mg",  // 2 mg/ml
            0.0,
            15.0,
            0.0, "mg/min"
        );
        DosageGroupExtPB r1 = MedicationDictionary.calcMedRate(input1, true);
        assertThat(r1).isNotNull();
        assertThat(r1.getDoseRateAmount()).isCloseTo(0.5, within(Consts.EPS));

        // 用例2：ug/h（规范化 mcg）
        // 同上 mg/h=30 -> 30000 ug/h
        DosageGroupExtPB input2 = newDosageGroupExtPB(
            100.0, 200.0, "mg",
            0.0,
            15.0,
            0.0, "mcg/h"
        );
        DosageGroupExtPB r2 = MedicationDictionary.calcMedRate(input2, true);
        assertThat(r2).isNotNull();
        assertThat(r2.getDoseRateAmount()).isCloseTo(30000.0, within(Consts.EPS));

        // 用例3：mg/min + 不同浓度
        // conc=0.25 mg/ml, admin=12 ml/h -> mg/h=3 -> ug/min=50
        DosageGroupExtPB input3 = newDosageGroupExtPB(
            40.0, 10.0, "mg",      // 0.25 mg/ml
            0.0,
            12.0,
            0.0, "mcg/min"
        );
        DosageGroupExtPB r3 = MedicationDictionary.calcMedRate(input3, true);
        assertThat(r3).isNotNull();
        assertThat(r3.getDoseRateAmount()).isCloseTo(50.0, within(Consts.EPS));

        // 反向用例1：mg/min -> admin
        // mg/min=0.5 -> mg/h=30；conc=2 mg/ml -> ml/h=15
        DosageGroupExtPB input4 = newDosageGroupExtPB(
            100.0, 200.0, "mg",   // 2 mg/ml
            0.0,
            0.0,
            0.5, "mg/min"
        );
        DosageGroupExtPB r4 = MedicationDictionary.calcMedRate(input4, false);
        assertThat(r4).isNotNull();
        assertThat(r4.getAdministrationRate()).isCloseTo(15.0, within(Consts.EPS));

        // 反向用例2：ug/h -> admin
        DosageGroupExtPB input5 = newDosageGroupExtPB(
            100.0, 200.0, "mg",
            0.0,
            0.0,
            30000.0, "ug/h"
        );
        DosageGroupExtPB r5 = MedicationDictionary.calcMedRate(input5, false);
        assertThat(r5).isNotNull();
        assertThat(r5.getAdministrationRate()).isCloseTo(15.0, within(Consts.EPS));

        // 缺浓度（total=0）
        DosageGroupExtPB input6 = newDosageGroupExtPB(
            0.0, 200.0, "mg",
            0.0,
            10.0,
            0.0, "mg/min"
        );
        assertThat(MedicationDictionary.calcMedRate(input6, true)).isNull();
    }

    @Test
    public void testCalcMedRateOthers() {
        // ml/h -> ml/min
        DosageGroupExtPB input1 = newDosageGroupExtPB(
            0.0, 0.0, "",  // 不依赖浓度与体重
            0.0,
            120.0,
            0.0, "ml/min"
        );
        DosageGroupExtPB r1 = MedicationDictionary.calcMedRate(input1, true);
        assertThat(r1).isNotNull();
        assertThat(r1.getDoseRateAmount()).isCloseTo(2.0, within(Consts.EPS));

        // ml/min -> ml/h
        DosageGroupExtPB input2 = newDosageGroupExtPB(
            0.0, 0.0, "",
            0.0,
            0.0,
            2.0, "ml/min"
        );
        DosageGroupExtPB r2 = MedicationDictionary.calcMedRate(input2, false);
        assertThat(r2).isNotNull();
        assertThat(r2.getAdministrationRate()).isCloseTo(120.0, within(Consts.EPS));

        // 不支持的单位
        DosageGroupExtPB input3 = newDosageGroupExtPB(
            100.0, 100.0, "mg",
            70.0,
            50.0,
            0.0, "iu/kg/h"      // 不支持的单位
        );
        assertThat(MedicationDictionary.calcMedRate(input3, true)).isNull();
    }

    private static DosageGroupExtPB newDosageGroupExtPB(
        double totalMl, double solidAmount, String solidUnit, double weightKg,
        double adminRateMlPerH, double doseRateAmount, String doseRateUnit
    ) {
        return DosageGroupExtPB.newBuilder()
            .setTotalMl(totalMl)
            .setSolidAmount(solidAmount)
            .setSolidUnit(solidUnit == null ? "" : solidUnit)
            .setWeightKg(weightKg)
            .setAdministrationRate(adminRateMlPerH)
            .setDoseRateAmount(doseRateAmount)
            .setDoseRateUnit(doseRateUnit == null ? "" : doseRateUnit)
            .build();
    }

    private final String deptId = "10035";

    @Autowired private ConfigProtoService protoService;
    @Autowired private MedicationDictionary medDict;
    @Autowired private MedicationConfig medConfig;
    @Autowired private MedicationRepository medRepo;
    @Autowired private MedicationHistoryRepository medHisRepo;
    @Autowired private AdministrationRouteRepository routeRepo;
}