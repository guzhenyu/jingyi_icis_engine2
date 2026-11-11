package com.jingyicare.jingyi_icis_engine.service.scores;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import com.jingyicare.jingyi_icis_engine.entity.scores.DeptScoreGroup;
import com.jingyicare.jingyi_icis_engine.proto.IcisWebApi.*;
import com.jingyicare.jingyi_icis_engine.proto.config.IcisNursingScore.*;
import com.jingyicare.jingyi_icis_engine.proto.shared.Shared.GenericResp;
import com.jingyicare.jingyi_icis_engine.repository.scores.DeptScoreGroupJdbc;
import com.jingyicare.jingyi_icis_engine.repository.scores.DeptScoreGroupJdbc.DeptScoreGroupKey;
import com.jingyicare.jingyi_icis_engine.repository.scores.DeptScoreGroupRepository;
import com.jingyicare.jingyi_icis_engine.service.ConfigProtoService;
import com.jingyicare.jingyi_icis_engine.service.locks.LockExecutor;
import com.jingyicare.jingyi_icis_engine.service.locks.LockKeys;
import com.jingyicare.jingyi_icis_engine.service.users.UserQuery;
import com.jingyicare.jingyi_icis_engine.utils.Pair;
import com.jingyicare.jingyi_icis_engine.utils.ProtoUtils;
import com.jingyicare.jingyi_icis_engine.utils.StrUtils;
import com.jingyicare.jingyi_icis_engine.utils.TimeUtils;

@Service
@Slf4j
public class ScoreMetaService {
    public ScoreMetaService(
        ConfigProtoService protoService,
        UserQuery userQuery,
        DeptScoreGroupRepository deptScoreGroupRepo,
        LockExecutor lockExecutor,
        JdbcTemplate jdbcTemplate
    ) {
        this.protoService = protoService;
        this.userQuery = userQuery;
        this.deptScoreGroupRepo = deptScoreGroupRepo;
        this.lockExecutor = lockExecutor;
        this.jdbcTemplate = jdbcTemplate;
        ScorePB scorePb = protoService.getConfig().getScore();
        this.defaultScoreGroupCodes = List.copyOf(scorePb.getDefaultScoreGroupCodeList());
        this.configuredScoreGroupMetas = scorePb.getGroupList().stream()
            .map(meta -> meta.toBuilder().setOrigName(meta.getName()).build())
            .toList();
        this.configuredScoreGroupMetaByCode = configuredScoreGroupMetas.stream()
            .collect(Collectors.toMap(ScoreGroupMetaPB::getCode, Function.identity()));
    }

    public ScoreGroupMetaPB getConfiguredScoreGroupMeta(String code) {
        return configuredScoreGroupMetaByCode.get(code);
    }

    public void initDept(String deptId) {
        StrUtils.requireNonBlank(deptId, "deptId");
        lockExecutor.execute(deptScoreGroupLockKeys(deptId, defaultScoreGroupCodes), () -> {
            // 外层筛选与本次加锁之间可能有其他实例或接口完成初始化。
            if (!deptScoreGroupRepo.findByDeptIdAndIsDeletedFalse(deptId).isEmpty()) {
                return null;
            }
            Set<String> codes = new HashSet<>();
            for (String code : defaultScoreGroupCodes) {
                if (!codes.add(code) || !configuredScoreGroupMetaByCode.containsKey(code)) {
                    throw new IllegalStateException("默认评分组配置无效：" + code);
                }
            }
            LocalDateTime nowUtc = TimeUtils.getNowUtc();
            for (int i = 0; i < defaultScoreGroupCodes.size(); i++) {
                String code = defaultScoreGroupCodes.get(i);
                DeptScoreGroup group = DeptScoreGroup.builder()
                    .deptId(deptId)
                    .scoreGroupCode(code)
                    .scoreGroupMeta(ProtoUtils.encodeScoreGroupMetaPB(
                        configuredScoreGroupMetaByCode.get(code)))
                    .displayOrder(i + 1)
                    .isDeleted(false)
                    .modifiedBy("System")
                    .modifiedAt(nowUtc)
                    .build();
                deptScoreGroupRepo.save(group);
            }
            return null;
        });
    }

    public GetScoreGroupMetaResp getScoreGroupMeta(String reqJson) {
        final GetScoreGroupMetaReq req;
        try {
            req = ProtoUtils.parseJsonToProto(reqJson, GetScoreGroupMetaReq.newBuilder());
        } catch (Exception e) {
            log.error("评分元数据请求解析失败", e);
            return GetScoreGroupMetaResp.newBuilder()
                .setRt(protoService.getReturnCode(StatusCode.PARSE_JSON_FAILED)).build();
        }
        if (StrUtils.isBlank(req.getDeptId())) {
            return GetScoreGroupMetaResp.newBuilder()
                .setRt(protoService.getReturnCode(StatusCode.OK))
                .addAllScoreGroupMeta(configuredScoreGroupMetas).build();
        }
        List<ScoreGroupMetaPB> metas = new ArrayList<>();
        for (DeptScoreGroup group :
            deptScoreGroupRepo.findByDeptIdAndIsDeletedFalse(req.getDeptId())
        ) {
            ScoreGroupMetaPB meta = resolveScoreGroupMeta(group, group.getScoreGroupCode());
            if (meta == null) {
                return GetScoreGroupMetaResp.newBuilder()
                    .setRt(protoService.getReturnCode(StatusCode.DEPT_SCORE_GROUP_NOT_FOUND))
                    .build();
            }
            metas.add(meta.toBuilder().setId(group.getId())
                .setDisplayOrder(group.getDisplayOrder()).build());
        }
        metas.sort(Comparator.comparing(ScoreGroupMetaPB::getDisplayOrder)
            .thenComparing(ScoreGroupMetaPB::getId));
        return GetScoreGroupMetaResp.newBuilder()
            .setRt(protoService.getReturnCode(StatusCode.OK)).addAllScoreGroupMeta(metas).build();
    }

    public AddDeptScoreGroupResp addDeptScoreGroup(String reqJson) {
        final AddDeptScoreGroupReq req;
        try {
            req = ProtoUtils.parseJsonToProto(reqJson, AddDeptScoreGroupReq.newBuilder());
        } catch (Exception e) {
            log.error("评分元数据请求解析失败", e);
            return addGroupResponse(StatusCode.PARSE_JSON_FAILED);
        }
        if (hasBlankGroupKey(req.getDeptId(), req.getScoreGroupCode())) {
            return addGroupResponse(StatusCode.INVALID_PARAM_VALUE);
        }
        Pair<String, String> account = userQuery.getAccountWithAutoId();
        if (account == null) return addGroupResponse(StatusCode.ACCOUNT_NOT_FOUND);

        return lockExecutor.execute(
            deptScoreGroupLockKeys(req.getDeptId(), List.of(req.getScoreGroupCode())),
            () -> {
                if (deptScoreGroupRepo.existsByDeptIdAndScoreGroupCodeAndIsDeletedFalse(
                    req.getDeptId(), req.getScoreGroupCode())
                ) {
                    return addGroupResponse(StatusCode.DEPT_SCORE_GROUP_ALREADY_EXISTS);
                }
                ScoreGroupMetaPB meta = configuredScoreGroupMetaByCode.get(req.getScoreGroupCode());
                if (meta == null) return addGroupResponse(StatusCode.DEPT_SCORE_GROUP_NOT_FOUND);
                Integer id = saveDeptScoreGroup(null, req.getDeptId(), req.getScoreGroupCode(),
                    meta, account.getFirst(), TimeUtils.getNowUtc());
                return AddDeptScoreGroupResp.newBuilder()
                    .setRt(protoService.getReturnCode(StatusCode.OK)).setId(id).build();
            });
    }

    public AddDeptScoreGroupsResp addDeptScoreGroups(String reqJson) {
        final AddDeptScoreGroupsReq req;
        try {
            req = ProtoUtils.parseJsonToProto(reqJson, AddDeptScoreGroupsReq.newBuilder());
        } catch (Exception e) {
            log.error("评分元数据请求解析失败", e);
            return addGroupsResponse(StatusCode.PARSE_JSON_FAILED);
        }
        if (StrUtils.isBlank(req.getDeptId()) || req.getGroupList().stream()
            .anyMatch(group -> StrUtils.isBlank(group.getScoreGroupCode()))
        ) {
            return addGroupsResponse(StatusCode.INVALID_PARAM_VALUE);
        }
        Pair<String, String> account = userQuery.getAccountWithAutoId();
        if (account == null) return addGroupsResponse(StatusCode.ACCOUNT_NOT_FOUND);
        List<String> codes = req.getGroupList().stream()
            .map(DeptScoreGroupPB::getScoreGroupCode).toList();
        if (new HashSet<>(codes).size() != codes.size()) {
            return addGroupsResponse(StatusCode.DEPT_SCORE_GROUP_ALREADY_EXISTS);
        }

        return lockExecutor.execute(deptScoreGroupLockKeys(req.getDeptId(), codes), () -> {
            // 整批校验完成后才写入，错误响应不会自动回滚事务。
            for (String code : codes) {
                if (deptScoreGroupRepo.existsByDeptIdAndScoreGroupCodeAndIsDeletedFalse(
                    req.getDeptId(), code)
                ) {
                    return addGroupsResponse(StatusCode.DEPT_SCORE_GROUP_ALREADY_EXISTS);
                }
            }
            for (String code : codes) {
                if (!configuredScoreGroupMetaByCode.containsKey(code)) {
                    return addGroupsResponse(StatusCode.DEPT_SCORE_GROUP_NOT_FOUND);
                }
            }
            LocalDateTime nowUtc = TimeUtils.getNowUtc();
            List<Integer> ids = new ArrayList<>();
            for (String code : codes) {
                ids.add(saveDeptScoreGroup(null, req.getDeptId(), code,
                    configuredScoreGroupMetaByCode.get(code), account.getFirst(), nowUtc));
            }
            return AddDeptScoreGroupsResp.newBuilder()
                .setRt(protoService.getReturnCode(StatusCode.OK)).addAllId(ids).build();
        });
    }

    public GenericResp deleteDeptScoreGroup(String reqJson) {
        final DeleteDeptScoreGroupReq req;
        try {
            req = ProtoUtils.parseJsonToProto(reqJson, DeleteDeptScoreGroupReq.newBuilder());
        } catch (Exception e) {
            log.error("评分元数据请求解析失败", e);
            return response(StatusCode.PARSE_JSON_FAILED);
        }
        Pair<String, String> account = userQuery.getAccountWithAutoId();
        if (account == null) return response(StatusCode.ACCOUNT_NOT_FOUND);
        Optional<DeptScoreGroupKey> initial = DeptScoreGroupJdbc.findKeyById(
            jdbcTemplate, req.getId());
        if (initial.isEmpty()) return response(StatusCode.OK);

        return lockExecutor.executePlanned(
            () -> DeptScoreGroupJdbc.findKeyById(jdbcTemplate, req.getId()),
            plan -> {
                // 物理删除导致计划为空时，仍锁住最初读取到的资源。
                DeptScoreGroupKey key = plan.orElse(initial.get());
                return deptScoreGroupLockKeys(key.deptId(), List.of(key.scoreGroupCode()));
            },
            plan -> plan.equals(DeptScoreGroupJdbc.findKeyById(jdbcTemplate, req.getId())),
            plan -> {
                DeptScoreGroup group = deptScoreGroupRepo
                    .findByIdAndIsDeletedFalse(req.getId()).orElse(null);
                if (group == null) return response(StatusCode.OK);
                group.setIsDeleted(true);
                group.setDeletedBy(account.getFirst());
                group.setDeletedAt(TimeUtils.getNowUtc());
                deptScoreGroupRepo.save(group);
                return response(StatusCode.OK);
            });
    }

    public GenericResp reorderDeptScoreGroups(String reqJson) {
        final ReorderDeptScoreGroupsReq req;
        try {
            req = ProtoUtils.parseJsonToProto(reqJson, ReorderDeptScoreGroupsReq.newBuilder());
        } catch (Exception e) {
            log.error("评分元数据请求解析失败", e);
            return response(StatusCode.PARSE_JSON_FAILED);
        }
        if (StrUtils.isBlank(req.getDeptId())) return response(StatusCode.INVALID_PARAM_VALUE);
        Pair<String, String> account = userQuery.getAccountWithAutoId();
        if (account == null) return response(StatusCode.ACCOUNT_NOT_FOUND);
        List<Integer> ids = req.getGroupOrderList().stream().map(ScoreGroupOrder::getId).toList();
        if (new HashSet<>(ids).size() != ids.size()) {
            return response(StatusCode.INVALID_PARAM_VALUE);
        }
        return lockExecutor.executePlanned(
            () -> DeptScoreGroupJdbc.findActiveKeysByIds(jdbcTemplate, ids),
            plan -> {
                List<String> keys = new ArrayList<>();
                keys.add(LockKeys.deptScoreGroup(req.getDeptId()));
                for (DeptScoreGroupKey key : plan) {
                    keys.add(LockKeys.deptScoreGroup(key.deptId(), key.scoreGroupCode()));
                }
                return keys;
            },
            plan -> plan.equals(DeptScoreGroupJdbc.findActiveKeysByIds(jdbcTemplate, ids)),
            plan -> {
                Map<Integer, DeptScoreGroup> groups = new HashMap<>();
                // 必须先校验全部 ID，再修改托管实体，避免脏检查提交部分修改。
                for (Integer id : ids) {
                    DeptScoreGroup group = deptScoreGroupRepo
                        .findByIdAndIsDeletedFalse(id).orElse(null);
                    if (group == null || !Objects.equals(group.getDeptId(), req.getDeptId())) {
                        return response(StatusCode.DEPT_SCORE_GROUP_NOT_FOUND);
                    }
                    groups.put(id, group);
                }
                LocalDateTime nowUtc = TimeUtils.getNowUtc();
                for (ScoreGroupOrder order : req.getGroupOrderList()) {
                    DeptScoreGroup group = groups.get(order.getId());
                    if (Objects.equals(group.getDisplayOrder(), order.getDisplayOrder())) continue;
                    group.setDisplayOrder(order.getDisplayOrder());
                    group.setModifiedBy(account.getFirst());
                    group.setModifiedAt(nowUtc);
                    deptScoreGroupRepo.save(group);
                }
                return response(StatusCode.OK);
            });
    }

    public GetOneScoreGroupMetaResp getOneScoreGroupMeta(String reqJson) {
        final GetOneScoreGroupMetaReq req;
        try {
            req = ProtoUtils.parseJsonToProto(reqJson, GetOneScoreGroupMetaReq.newBuilder());
        } catch (Exception e) {
            log.error("评分元数据请求解析失败", e);
            return GetOneScoreGroupMetaResp.newBuilder()
                .setRt(protoService.getReturnCode(StatusCode.PARSE_JSON_FAILED)).build();
        }
        DeptScoreGroup group = deptScoreGroupRepo.findByDeptIdAndScoreGroupCodeAndIsDeletedFalse(
            req.getDeptId(), req.getCode()).orElse(null);
        ScoreGroupMetaPB meta = resolveScoreGroupMeta(group, req.getCode());
        if (meta == null) {
            return GetOneScoreGroupMetaResp.newBuilder()
                .setRt(protoService.getReturnCode(StatusCode.SCORE_GROUP_NOT_FOUND)).build();
        }
        return GetOneScoreGroupMetaResp.newBuilder()
            .setRt(protoService.getReturnCode(StatusCode.OK)).setScoreGroupMeta(meta).build();
    }

    public GenericResp updateScoreGroupMeta(String reqJson) {
        final UpdateScoreGroupMetaReq req;
        try {
            req = ProtoUtils.parseJsonToProto(reqJson, UpdateScoreGroupMetaReq.newBuilder());
        } catch (Exception e) {
            log.error("评分元数据请求解析失败", e);
            return response(StatusCode.PARSE_JSON_FAILED);
        }
        return updateDeptScoreGroupMeta(req.getDeptId(), req.getScoreGroupCode(), meta -> {
            ScoreGroupMetaPB.Builder builder = meta.toBuilder();
            if (req.getUpdateName()) builder.setName(req.getName());
            if (req.getUpdateMeasureMultiSelection()) {
                builder.setMeasureMultiSelection(req.getMeasureMultiSelection());
            }
            if (req.getUpdateNotes()) builder.setNotes(req.getNotes());
            return new ScoreGroupMetaUpdate(StatusCode.OK, builder.build());
        });
    }

    public GenericResp updateScoreItemName(String reqJson) {
        final UpdateScoreItemNameReq req;
        try {
            req = ProtoUtils.parseJsonToProto(reqJson, UpdateScoreItemNameReq.newBuilder());
        } catch (Exception e) {
            log.error("评分元数据请求解析失败", e);
            return response(StatusCode.PARSE_JSON_FAILED);
        }
        return updateDeptScoreGroupMeta(req.getDeptId(), req.getScoreGroupCode(), meta -> {
            ScoreGroupMetaPB.Builder builder = meta.toBuilder();
            boolean found = false;
            for (int i = 0; i < meta.getItemCount(); i++) {
                ScoreItemMetaPB item = meta.getItem(i);
                if (item.getCode().equals(req.getScoreItemCode())) {
                    builder.setItem(i, item.toBuilder().setName(req.getName()));
                    found = true;
                }
            }
            return found ? new ScoreGroupMetaUpdate(StatusCode.OK, builder.build())
                : new ScoreGroupMetaUpdate(StatusCode.SCORE_ITEM_NOT_FOUND, null);
        });
    }

    public GenericResp updateScoreOptionName(String reqJson) {
        final UpdateScoreOptionNameReq req;
        try {
            req = ProtoUtils.parseJsonToProto(reqJson, UpdateScoreOptionNameReq.newBuilder());
        } catch (Exception e) {
            log.error("评分元数据请求解析失败", e);
            return response(StatusCode.PARSE_JSON_FAILED);
        }
        return updateDeptScoreGroupMeta(req.getDeptId(), req.getScoreGroupCode(), meta -> {
            ScoreGroupMetaPB.Builder builder = meta.toBuilder();
            boolean found = false;
            for (int i = 0; i < meta.getItemCount(); i++) {
                ScoreItemMetaPB item = meta.getItem(i);
                if (!item.getCode().equals(req.getScoreItemCode())) continue;
                ScoreItemMetaPB.Builder itemBuilder = item.toBuilder();
                for (int j = 0; j < item.getOptionCount(); j++) {
                    ScoreOptionPB option = item.getOption(j);
                    if (option.getCode().equals(req.getScoreOptionCode())) {
                        itemBuilder.setOption(j, option.toBuilder().setName(req.getName()));
                        found = true;
                    }
                }
                builder.setItem(i, itemBuilder);
            }
            return found ? new ScoreGroupMetaUpdate(StatusCode.OK, builder.build())
                : new ScoreGroupMetaUpdate(StatusCode.SCORE_OPTION_NOT_FOUND, null);
        });
    }

    public GenericResp addScoreGroupMeasureMeta(String reqJson) {
        final AddScoreGroupMeasureMetaReq req;
        try {
            req = ProtoUtils.parseJsonToProto(reqJson, AddScoreGroupMeasureMetaReq.newBuilder());
        } catch (Exception e) {
            log.error("评分元数据请求解析失败", e);
            return response(StatusCode.PARSE_JSON_FAILED);
        }
        return updateDeptScoreGroupMeta(req.getDeptId(), req.getScoreGroupCode(), meta -> {
            int id = 1;
            for (ScoreGroupMeasureMetaPB measure : meta.getMeasureList()) {
                if (measure.getName().equals(req.getMeasure().getName())) {
                    return new ScoreGroupMetaUpdate(
                        StatusCode.SCORE_GROUP_MEASURE_ALREADY_EXISTS, null);
                }
                id = Math.max(id, measure.getId() + 1);
            }
            return new ScoreGroupMetaUpdate(StatusCode.OK, meta.toBuilder()
                .addMeasure(req.getMeasure().toBuilder().setId(id)).build());
        });
    }

    public GenericResp updateScoreGroupMeasureMeta(String reqJson) {
        final UpdateScoreGroupMeasureMetaReq req;
        try {
            req = ProtoUtils.parseJsonToProto(reqJson, UpdateScoreGroupMeasureMetaReq.newBuilder());
        } catch (Exception e) {
            log.error("评分元数据请求解析失败", e);
            return response(StatusCode.PARSE_JSON_FAILED);
        }
        return updateDeptScoreGroupMeta(req.getDeptId(), req.getScoreGroupCode(), meta -> {
            ScoreGroupMetaPB.Builder builder = meta.toBuilder();
            boolean found = false;
            for (int i = 0; i < meta.getMeasureCount(); i++) {
                if (meta.getMeasure(i).getId() == req.getMeasure().getId()) {
                    builder.setMeasure(i, req.getMeasure());
                    found = true;
                }
            }
            return found ? new ScoreGroupMetaUpdate(StatusCode.OK, builder.build())
                : new ScoreGroupMetaUpdate(StatusCode.SCORE_GROUP_MEASURE_NOT_FOUND, null);
        });
    }

    public GenericResp deleteScoreGroupMeasureMeta(String reqJson) {
        final DeleteScoreGroupMeasureMetaReq req;
        try {
            req = ProtoUtils.parseJsonToProto(reqJson, DeleteScoreGroupMeasureMetaReq.newBuilder());
        } catch (Exception e) {
            log.error("评分元数据请求解析失败", e);
            return response(StatusCode.PARSE_JSON_FAILED);
        }
        return updateDeptScoreGroupMeta(req.getDeptId(), req.getScoreGroupCode(), meta -> {
            List<ScoreGroupMeasureMetaPB> measures = meta.getMeasureList().stream()
                .filter(measure -> !measure.getName().equals(req.getMeasureName())).toList();
            if (measures.size() == meta.getMeasureCount()) {
                return new ScoreGroupMetaUpdate(StatusCode.OK, null);
            }
            return new ScoreGroupMetaUpdate(StatusCode.OK,
                meta.toBuilder().clearMeasure().addAllMeasure(measures).build());
        });
    }

    private GenericResp updateDeptScoreGroupMeta(
        String deptId, String code, Function<ScoreGroupMetaPB, ScoreGroupMetaUpdate> update
    ) {
        if (hasBlankGroupKey(deptId, code)) return response(StatusCode.INVALID_PARAM_VALUE);
        Pair<String, String> account = userQuery.getAccountWithAutoId();
        if (account == null) return response(StatusCode.ACCOUNT_NOT_FOUND);
        // 修改接口也允许首次创建科室配置，因此同时锁住科室排序和目标评分组。
        return lockExecutor.execute(deptScoreGroupLockKeys(deptId, List.of(code)), () -> {
            DeptScoreGroup group = deptScoreGroupRepo
                .findByDeptIdAndScoreGroupCodeAndIsDeletedFalse(deptId, code).orElse(null);
            ScoreGroupMetaPB meta = group == null || StrUtils.isBlank(group.getScoreGroupMeta())
                ? configuredScoreGroupMetaByCode.get(code)
                : ProtoUtils.decodeScoreGroupMetaPB(group.getScoreGroupMeta());
            if (meta == null) return response(StatusCode.DEPT_SCORE_GROUP_NOT_FOUND);
            ScoreGroupMetaUpdate result = update.apply(meta);
            if (result.status() != StatusCode.OK) return response(result.status());
            if (result.meta() != null) {
                saveDeptScoreGroup(group, deptId, code, result.meta(), account.getFirst(),
                    TimeUtils.getNowUtc());
            }
            return response(StatusCode.OK);
        });
    }

    private ScoreGroupMetaPB resolveScoreGroupMeta(DeptScoreGroup group, String code) {
        ScoreGroupMetaPB meta = group == null ? null
            : ProtoUtils.decodeScoreGroupMetaPB(group.getScoreGroupMeta());
        return meta == null ? configuredScoreGroupMetaByCode.get(code) : meta;
    }

    private Integer saveDeptScoreGroup(
        DeptScoreGroup group, String deptId, String code, ScoreGroupMetaPB meta,
        String accountId, LocalDateTime nowUtc
    ) {
        if (group == null) {
            int displayOrder = 1;
            for (DeptScoreGroup existing :
                deptScoreGroupRepo.findByDeptIdAndIsDeletedFalse(deptId)
            ) {
                displayOrder = Math.max(displayOrder, existing.getDisplayOrder() + 1);
            }
            group = DeptScoreGroup.builder().deptId(deptId).scoreGroupCode(code)
                .displayOrder(displayOrder).isDeleted(false).build();
        }
        group.setScoreGroupMeta(ProtoUtils.encodeScoreGroupMetaPB(meta));
        group.setModifiedBy(accountId);
        group.setModifiedAt(nowUtc);
        return deptScoreGroupRepo.save(group).getId();
    }

    private List<String> deptScoreGroupLockKeys(String deptId, List<String> codes) {
        List<String> keys = new ArrayList<>();
        keys.add(LockKeys.deptScoreGroup(deptId));
        for (String code : codes) keys.add(LockKeys.deptScoreGroup(deptId, code));
        return keys;
    }

    private boolean hasBlankGroupKey(String deptId, String code) {
        return StrUtils.isBlank(deptId) || StrUtils.isBlank(code);
    }

    private GenericResp response(StatusCode status) {
        return GenericResp.newBuilder().setRt(protoService.getReturnCode(status)).build();
    }

    private AddDeptScoreGroupResp addGroupResponse(StatusCode status) {
        return AddDeptScoreGroupResp.newBuilder().setRt(protoService.getReturnCode(status)).build();
    }

    private AddDeptScoreGroupsResp addGroupsResponse(StatusCode status) {
        return AddDeptScoreGroupsResp.newBuilder()
            .setRt(protoService.getReturnCode(status)).build();
    }

    private record ScoreGroupMetaUpdate(StatusCode status, ScoreGroupMetaPB meta) {}

    private final ConfigProtoService protoService;
    private final UserQuery userQuery;
    private final DeptScoreGroupRepository deptScoreGroupRepo;
    private final LockExecutor lockExecutor;
    private final JdbcTemplate jdbcTemplate;
    private final List<String> defaultScoreGroupCodes;
    private final List<ScoreGroupMetaPB> configuredScoreGroupMetas;
    private final Map<String, ScoreGroupMetaPB> configuredScoreGroupMetaByCode;
}
