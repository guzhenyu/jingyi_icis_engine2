package com.jingyicare.jingyi_icis_engine.service.qcs;

import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.*;
import java.util.stream.Collectors;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import lombok.*;
import lombok.extern.slf4j.Slf4j;

import com.jingyicare.jingyi_icis_engine.proto.IcisWebApi.*;
import com.jingyicare.jingyi_icis_engine.proto.config.IcisQualityControl.*;
import com.jingyicare.jingyi_icis_engine.proto.shared.Shared.*;

import com.jingyicare.jingyi_icis_engine.entity.patients.*;
import com.jingyicare.jingyi_icis_engine.entity.users.*;
import com.jingyicare.jingyi_icis_engine.repository.patients.*;
import com.jingyicare.jingyi_icis_engine.repository.users.*;
import com.jingyicare.jingyi_icis_engine.service.ConfigProtoService;
import com.jingyicare.jingyi_icis_engine.utils.*;

/*
[queryStartUtc, queryEndUtc, deptId, itemCode]

[queryStartUtc, queryEndUtc)                            [deptId]
 |          |                                             |-> List<Pair<effectiveTime, bedCount>> bedCounts
 |          |                                                        |
 |          |                            |---------------------------|
 |          |-----------------------------------------------> 1. 按effectiveTime排序，找到最后一条effectiveTime<=queryStartUtc的记录a
 |->List<Pair<monthStartDate, monthEndDate>>                  2. 找到第一条effectiveTime>=queryEndUtc的记录b
    statsMonths                                                    |
       |                                                           |
       |-----------------------------------------------------------|
       |
       |-> 统计[a, b)之间的床位使用情况 List<Pair<monthStartDate, bedCount>>

AccountStats = {account_id, name, gender, primary_role_name, start_date, end_date, ...}
[deptId] ==(accounts_departments)=> List<(account_id, start_date, end_date, primary_role_id)>  // 没删除end_date = 9999-12-31
                                              |([itemCode] => List<qualified_primary_role_id> 过滤)
                                              |-> account_id => (name, gender, ...)
                                              |-> primary_role_id => primary_role_name
                                                    |--> List<AccountStats>
                                                            |
[queryStartUtc, queryEndUtc) => statsMonths ---------------------> 统计List<Pair<monthStartDate, List<AccountStats>>>
*/
@Component
@Slf4j
public class AccountBedRatioCalc {
    public AccountBedRatioCalc(
        @Autowired ConfigProtoService protoService,
        @Autowired DepartmentAccountRepository deptAcctRepo,
        @Autowired AccountRepository accountRepo,
        @Autowired RbacRoleRepository roleRepo,
        @Autowired BedCountRepository bedCountRepo
    ) {
        this.ZONE_ID = protoService.getConfig().getZoneId();
        this.doctorRoleIds = protoService.getConfig().getQualityControl().getDoctorRoleIdList();
        this.nurseRoleIds = protoService.getConfig().getQualityControl().getNurseRoleIdList();

        this.protoService = protoService;
        this.deptAcctRepo = deptAcctRepo;
        this.accountRepo = accountRepo;
        this.roleRepo = roleRepo;
        this.bedCountRepo = bedCountRepo;
    }

    // 1. 生成月窗口
    // 2. 拉取床位时间线并投影为“各月月底床位”
    // 3. 拉取人员-科室关系区间并与账号表合并
    // 4. 计算各月月底在岗人员及明细
    // 5. 组装 QcICUAccountBedRatioPB 列表
    public List<QcICUAccountBedRatioPB> calcMonthly(
        String deptId,
        String itemCode,
        LocalDateTime queryStartUtc,   // [start, end) UTC
        LocalDateTime queryEndUtc
    ) {
        List<LocalDateTime> monthsUtc = QcUtils.buildMonthsUtc(queryStartUtc, queryEndUtc, ZONE_ID);
        Map<LocalDateTime, Integer> bedCounts = getBedCountsByMonth(deptId, monthsUtc);
        Map<LocalDateTime, List<QcICUAccountPB>> accountPBsByMonth = getAccountPBsByMonth(deptId, itemCode, monthsUtc);
        List<QcICUAccountBedRatioPB> result = new ArrayList<>();
        for (LocalDateTime month : monthsUtc) {
            Integer bedCount = bedCounts.getOrDefault(month, 0);
            List<QcICUAccountPB> accountPbs = accountPBsByMonth.getOrDefault(month, Collections.emptyList());
            result.add(QcICUAccountBedRatioPB.newBuilder()
                .setMonthIso8601(TimeUtils.toIso8601String(month, ZONE_ID))
                .setBedCnt(bedCount)
                .setAcctCnt(accountPbs.size())
                .addAllAccount(accountPbs)
                .build()
            );
        }
        return result;
    }

    private Map<LocalDateTime, Integer> getBedCountsByMonth(
        String deptId, List<LocalDateTime> monthsUtc
    ) {
        Map<LocalDateTime, Integer> result = new HashMap<>();
        if (monthsUtc == null || monthsUtc.isEmpty()) return result;

        // 变更记录按时间升序
        List<BedCount> changes = bedCountRepo.findByDeptIdAndIsDeletedFalse(deptId)
                .stream()
                .sorted(Comparator.comparing(BedCount::getEffectiveTime))
                .toList();

        int j = 0;        // 指向变更记录
        int currentBedCount = 0;  // 截至该月月底（<nextMonthStart）的床位数，默认0

        for (LocalDateTime monthStartUtc : monthsUtc) {
            LocalDateTime nextMonthStartUtc = monthStartUtc.plusMonths(1);

            // 吸收所有在 nextMonthStart 之前生效的变更（严格小于，下月1日生效算到下月）
            while (j < changes.size()
                && changes.get(j).getEffectiveTime().isBefore(nextMonthStartUtc)
            ) {
                currentBedCount = changes.get(j).getBedCount();
                j++;
            }

            // 本月月底的床位数
            result.put(monthStartUtc, currentBedCount);
        }

        return result;
    }

    @AllArgsConstructor
    private static class EmploymentPeriod {
        public Long employeeId;
        public String accountId;
        public String roleName;
        public LocalDateTime startTime;
        public LocalDateTime endTime;
    }

    private Map<LocalDateTime, List<QcICUAccountPB>> getAccountPBsByMonth(
        String deptId, String itemCode, List<LocalDateTime> monthsUtc
    ) {
        // 角色集
        List<Integer> roleIds =
            itemCode.equals(Consts.ICU_2_ICU_DOCTOR_BED_RATIO) ? new ArrayList<>(doctorRoleIds)
            : itemCode.equals(Consts.ICU_3_ICU_NURSE_BED_RATIO) ? new ArrayList<>(nurseRoleIds)
            : Collections.emptyList();
        if (roleIds.isEmpty()) {
            log.warn("No valid role IDs found for itemCode: {}", itemCode);
            return new HashMap<>();
        }
        List<DepartmentAccount> deptAccounts =
            deptAcctRepo.findByDeptIdAndPrimaryRoleIdIn(deptId, roleIds);
        Map<Integer, String> roleMap = roleRepo.findAll().stream()
            .collect(Collectors.toMap(RbacRole::getId, RbacRole::getName));

        // 找到对应月份下的在岗人员employeeIds
        List<Pair<LocalDateTime, List<EmploymentPeriod>>> monthEmployeeMap = new ArrayList<>();
        Set<Long> employeeIds = new HashSet<>();
        for (LocalDateTime monthStartUtc : monthsUtc) {
            LocalDateTime nextMonthStartUtc = monthStartUtc.plusMonths(1);
            List<EmploymentPeriod> monthEmployees = new ArrayList<>();
            for (DepartmentAccount deptAcct : deptAccounts) {
                Long employeeId = deptAcct.getEmployeeId();
                String accountId = deptAcct.getAccountId();
                String roleName = roleMap.getOrDefault(deptAcct.getPrimaryRoleId(), "n/a");
                LocalDateTime startDate = deptAcct.getStartDate();
                LocalDateTime endDate = deptAcct.getDeletedAt();

                // 判断月末对应的账号是否活跃
                boolean active = startDate.isBefore(nextMonthStartUtc)
                    && (endDate == null || !endDate.isBefore(nextMonthStartUtc));
                if (!active) continue;
                EmploymentPeriod emp = new EmploymentPeriod(
                    employeeId, accountId, roleName, startDate, endDate
                );
                monthEmployees.add(emp);
                employeeIds.add(employeeId);
            }
            monthEmployees.sort(Comparator.comparing(emp -> emp.startTime));
            monthEmployeeMap.add(new Pair<>(monthStartUtc, monthEmployees));
        }

        // 查找医护信息
        Map<Long, String> accountMap = accountRepo.findByIdIn(employeeIds.stream().toList())
            .stream().collect(Collectors.toMap(Account::getId, Account::getName));

        // 组装结果
        Map<LocalDateTime, List<QcICUAccountPB>> monthAccountMap = new HashMap<>();
        for (Pair<LocalDateTime, List<EmploymentPeriod>> pair : monthEmployeeMap) {
            LocalDateTime monthStart = pair.getFirst();
            List<EmploymentPeriod> employees = pair.getSecond();

            List<QcICUAccountPB> accountPBs = new ArrayList<>();
            for (EmploymentPeriod emp : employees) {
                String accountName = accountMap.getOrDefault(emp.employeeId, "n/a");
                String startDateIso8601 = TimeUtils.toIso8601String(emp.startTime, ZONE_ID);
                String endDateIso8601 = TimeUtils.toIso8601String(emp.endTime, ZONE_ID);
                QcICUAccountPB accountPb = QcICUAccountPB.newBuilder()
                    .setEmployeeId(emp.employeeId)
                    .setAccountId(emp.accountId)
                    .setAccountName(accountName)
                    .setPrimaryRoleName(emp.roleName)
                    .setStartDateIso8601(startDateIso8601)
                    .setEndDateIso8601(endDateIso8601)
                    .build();
                accountPBs.add(accountPb);
            }
            monthAccountMap.put(monthStart, accountPBs);
        }

        return monthAccountMap;
    }

    public static final String QC_UNIT = "";
    public static final String QC_NORMINATOR_TITLE = "实际人数";
    public static final String QC_NORMINATOR_COL1_TITLE = "工号";
    public static final String QC_NORMINATOR_COL1_KEY = "accountId";
    public static final String QC_NORMINATOR_COL2_TITLE = "姓名";
    public static final String QC_NORMINATOR_COL2_KEY = "accountName";
    public static final String QC_NORMINATOR_COL3_TITLE = "角色";
    public static final String QC_NORMINATOR_COL3_KEY = "primaryRoleName";
    public static final String QC_NORMINATOR_COL4_TITLE = "开始时间";
    public static final String QC_NORMINATOR_COL4_KEY = "startTime";
    public static final String QC_NORMINATOR_COL5_TITLE = "结束时间";
    public static final String QC_NORMINATOR_COL5_KEY = "endTime";
    public static final String QC_DENOMINATOR_TITLE = "实际床数";
    public static final String QC_DENOMINATOR_COL1_TITLE = "床数";
    public static final String QC_DENOMINATOR_COL1_KEY = "bedCnt";

    public List<QcMonthDataPB> toMonthDataList(List<QcICUAccountBedRatioPB> ratioList) {
        if (ratioList == null || ratioList.isEmpty()) return Collections.emptyList();

        // 预构建表头（可复用）
        final List<QcHeaderPB> numeratorHeaders = Arrays.asList(
            QcHeaderPB.newBuilder().setTitle(QC_NORMINATOR_COL1_TITLE).setKey(QC_NORMINATOR_COL1_KEY).build(),
            QcHeaderPB.newBuilder().setTitle(QC_NORMINATOR_COL2_TITLE).setKey(QC_NORMINATOR_COL2_KEY).build(),
            QcHeaderPB.newBuilder().setTitle(QC_NORMINATOR_COL3_TITLE).setKey(QC_NORMINATOR_COL3_KEY).build(),
            QcHeaderPB.newBuilder().setTitle(QC_NORMINATOR_COL4_TITLE).setKey(QC_NORMINATOR_COL4_KEY).build(),
            QcHeaderPB.newBuilder().setTitle(QC_NORMINATOR_COL5_TITLE).setKey(QC_NORMINATOR_COL5_KEY).build()
        );
        final List<QcHeaderPB> denominatorHeaders = List.of(
            QcHeaderPB.newBuilder().setTitle(QC_DENOMINATOR_COL1_TITLE).setKey(QC_DENOMINATOR_COL1_KEY).build()
        );

        List<QcMonthDataPB> out = new ArrayList<>(ratioList.size());

        for (QcICUAccountBedRatioPB ratio : ratioList) {
            // 分子表格
            QcTablePB.Builder numeratorTbl = QcTablePB.newBuilder()
                .setTitle(QC_NORMINATOR_TITLE);
            numeratorHeaders.forEach(numeratorTbl::addHeader);

            for (QcICUAccountPB acc : ratio.getAccountList()) {
                QcRowPB.Builder row = QcRowPB.newBuilder();
                row.putData(QC_NORMINATOR_COL1_KEY, acc.getAccountId());
                row.putData(QC_NORMINATOR_COL2_KEY, acc.getAccountName());
                row.putData(QC_NORMINATOR_COL3_KEY, acc.getPrimaryRoleName());
                row.putData(QC_NORMINATOR_COL4_KEY, TimeUtils.format1Date(acc.getStartDateIso8601(), ZONE_ID));
                row.putData(QC_NORMINATOR_COL5_KEY, TimeUtils.format1Date(acc.getEndDateIso8601(), ZONE_ID));
                numeratorTbl.addRow(row.build());
            }

            // 分母表格
            QcTablePB.Builder denominatorTbl = QcTablePB.newBuilder()
                .setTitle(QC_DENOMINATOR_TITLE);
            denominatorHeaders.forEach(denominatorTbl::addHeader);
            denominatorTbl.addRow(
                QcRowPB.newBuilder()
                    .putData(QC_DENOMINATOR_COL1_KEY, String.valueOf(ratio.getBedCnt()))
                    .build()
            );

            // 汇总一个月
            QcMonthDataPB monthData = QcMonthDataPB.newBuilder()
                .setMonthIso8601(ratio.getMonthIso8601())
                .setNumerator((float) ratio.getAcctCnt())      // 分子：人数
                .setDenominator((float) ratio.getBedCnt())     // 分母：床数
                .setUnit(QC_UNIT)
                .setNumeratorTbl(numeratorTbl.build())
                .setDenominatorTbl(denominatorTbl.build())
                .build();

            out.add(monthData);
        }

        return out;
    }

    private final String ZONE_ID;
    private final List<Integer> doctorRoleIds;
    private final List<Integer> nurseRoleIds;

    private final ConfigProtoService protoService;
    private final DepartmentAccountRepository deptAcctRepo;
    private final AccountRepository accountRepo;
    private final RbacRoleRepository roleRepo;
    private final BedCountRepository bedCountRepo;
}