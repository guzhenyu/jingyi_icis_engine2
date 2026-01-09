package com.jingyicare.jingyi_icis_engine.service.exturls;

import java.security.MessageDigest;
import java.time.*;
import java.util.*;
import java.util.stream.*;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.google.protobuf.util.JsonFormat;
import lombok.*;
import lombok.extern.slf4j.Slf4j;

import com.jingyicare.jingyi_icis_engine.proto.IcisWebApi.*;
import com.jingyicare.jingyi_icis_engine.proto.config.IcisUrl.*;
import com.jingyicare.jingyi_icis_engine.proto.shared.Shared.*;

import com.jingyicare.jingyi_icis_engine.entity.exturls.*;
import com.jingyicare.jingyi_icis_engine.repository.exturls.*;
import com.jingyicare.jingyi_icis_engine.service.ConfigProtoService;
import com.jingyicare.jingyi_icis_engine.service.users.*;
import com.jingyicare.jingyi_icis_engine.utils.*;

@Service
@Slf4j
public class ExtUrlService {
    public ExtUrlService(
        @Autowired ConfigProtoService protoService,
        @Autowired UserService userService,
        @Autowired ExtUrlConfigRepository urlConfigRepo
    ) {
        this.statusCodeMsgList = protoService.getConfig().getText().getStatusCodeMsgList();
        this.paramValueIdNameMap = new HashMap<>();
        for (EnumValue ev : protoService.getConfig().getUrl().getPredefinedParamValueList()) {
            int evId = ev.getId();
            if (evId == 1) continue;
            this.paramValueIdNameMap.put(evId, ev.getName());
        }

        this.protoService = protoService;
        this.userService = userService;
        this.urlConfigRepo = urlConfigRepo;
    }

    @Transactional
    public GetAllExtUrlsResp getAllExtUrls(String getAllExtUrlsReqJson) {
        final GetAllExtUrlsReq req;
        try {
            GetAllExtUrlsReq.Builder builder = GetAllExtUrlsReq.newBuilder();
            JsonFormat.parser().merge(getAllExtUrlsReqJson, builder);
            req = builder.build();
        } catch (Exception e) {
            log.error("Failed to convert string to proto: ", e, "\n", e.getStackTrace());
            return GetAllExtUrlsResp.newBuilder()
                .setRt(ReturnCodeUtils.getReturnCode(statusCodeMsgList, StatusCode.PARSE_JSON_FAILED))
                .build();
        }

        //  提取参数
        final String deptId = req.getDeptId();

        // 查找数据，组装结果
        List<ExtUrlPB> extUrls = new ArrayList<>();
        for (ExtUrlConfig extUrl : urlConfigRepo.findByDeptIdAndIsDeletedFalse(deptId)) {
            ExtUrlPB extUrlPb = ProtoUtils.decodeExtUrl(extUrl.getExtUrlPb());
            if (extUrlPb != null) extUrls.add(extUrlPb.toBuilder().setId(extUrl.getId()).build());
        }
        extUrls.sort(Comparator.comparing(ExtUrlPB::getId));

        return GetAllExtUrlsResp.newBuilder()
            .setRt(ReturnCodeUtils.getReturnCode(statusCodeMsgList, StatusCode.OK))
            .addAllUrl(extUrls)
            .build();
    }

    @Transactional
    public GenericResp saveExtUrl(String saveExtUrlReqJson) {
        final SaveExtUrlReq req;
        try {
            SaveExtUrlReq.Builder builder = SaveExtUrlReq.newBuilder();
            JsonFormat.parser().merge(saveExtUrlReqJson, builder);
            req = builder.build();
        } catch (Exception e) {
            log.error("Failed to convert string to proto: ", e, "\n", e.getStackTrace());
            return GenericResp.newBuilder()
                .setRt(ReturnCodeUtils.getReturnCode(statusCodeMsgList, StatusCode.PARSE_JSON_FAILED))
                .build();
        }

        // 获取当前用户信息
        Pair<String, String> account = userService.getAccountWithAutoId();
        if (account == null) {
            return GenericResp.newBuilder()
                .setRt(ReturnCodeUtils.getReturnCode(statusCodeMsgList, StatusCode.ACCOUNT_NOT_FOUND))
                .build();
        }
        final String accountId = account.getFirst();

        // 提取参数
        ExtUrlPB extUrlPb = req.getUrl();
        Integer id = extUrlPb.getId();
        String deptId = extUrlPb.getDeptId();
        String displayName = extUrlPb.getDisplayName();

        // 检查数据
        ExtUrlConfig extUrlConfig = null;
        if (id <= 0) {  // 新增
            extUrlConfig = urlConfigRepo
                .findByDeptIdAndDisplayNameAndIsDeletedFalse(deptId, displayName).orElse(null);
            if (extUrlConfig != null) {
                return GenericResp.newBuilder()
                    .setRt(ReturnCodeUtils.getReturnCode(statusCodeMsgList, StatusCode.EXT_URL_ALREADY_EXISTS))
                    .build();
            }
            extUrlConfig = new ExtUrlConfig();
        } else {  // 更新
            extUrlConfig = urlConfigRepo.findByIdAndIsDeletedFalse(id).orElse(null);
            if (extUrlConfig == null) {
                return GenericResp.newBuilder()
                    .setRt(ReturnCodeUtils.getReturnCode(statusCodeMsgList, StatusCode.EXT_URL_NOT_FOUND))
                    .build();
            }
            ExtUrlConfig dupExtUrlConfig = urlConfigRepo
                .findByDeptIdAndDisplayNameAndIsDeletedFalse(deptId, displayName).orElse(null);
            if (dupExtUrlConfig != null && !id.equals(dupExtUrlConfig.getId())) {
                return GenericResp.newBuilder()
                    .setRt(ReturnCodeUtils.getReturnCode(statusCodeMsgList, StatusCode.EXT_URL_ALREADY_EXISTS))
                    .build();
            }
        }
        LocalDateTime nowUtc = TimeUtils.getNowUtc();
        extUrlConfig = extUrlConfig.toBuilder()
            .deptId(deptId)
            .displayName(displayName)
            .pattern(getUrlPattern(extUrlPb, paramValueIdNameMap))
            .extUrlPb(ProtoUtils.encodeExtUrl(extUrlPb))
            .isDeleted(false)
            .modifiedAt(nowUtc)
            .modifiedBy(accountId)
            .build();
        urlConfigRepo.save(extUrlConfig);

        return GenericResp.newBuilder()
            .setRt(ReturnCodeUtils.getReturnCode(statusCodeMsgList, StatusCode.OK))
            .build();
    }

    @Transactional
    public GenericResp deleteExtUrl(String deleteExtUrlReqJson) {
        final DeleteExtUrlReq req;
        try {
            DeleteExtUrlReq.Builder builder = DeleteExtUrlReq.newBuilder();
            JsonFormat.parser().merge(deleteExtUrlReqJson, builder);
            req = builder.build();
        } catch (Exception e) {
            log.error("Failed to convert string to proto: ", e, "\n", e.getStackTrace());
            return GenericResp.newBuilder()
                .setRt(ReturnCodeUtils.getReturnCode(statusCodeMsgList, StatusCode.PARSE_JSON_FAILED))
                .build();
        }

        // 获取当前用户信息
        Pair<String, String> account = userService.getAccountWithAutoId();
        if (account == null) {
            return GenericResp.newBuilder()
                .setRt(ReturnCodeUtils.getReturnCode(statusCodeMsgList, StatusCode.ACCOUNT_NOT_FOUND))
                .build();
        }
        final String accountId = account.getFirst();

        // 提取参数
        Integer idToDelete = req.getId();
        if (idToDelete == null || idToDelete <= 0) {
            return GenericResp.newBuilder()
                .setRt(ReturnCodeUtils.getReturnCode(statusCodeMsgList, StatusCode.OK))
                .build();
        }
        LocalDateTime nowUtc = TimeUtils.getNowUtc();

        // 删除逻辑
        ExtUrlConfig extUrlConfig = urlConfigRepo.findByIdAndIsDeletedFalse(idToDelete).orElse(null);
        if (extUrlConfig == null) {
            return GenericResp.newBuilder()
                .setRt(ReturnCodeUtils.getReturnCode(statusCodeMsgList, StatusCode.OK))
                .build();
        }
        extUrlConfig.setIsDeleted(true);
        extUrlConfig.setModifiedAt(nowUtc);
        extUrlConfig.setModifiedBy(accountId);
        urlConfigRepo.save(extUrlConfig);

        return GenericResp.newBuilder()
            .setRt(ReturnCodeUtils.getReturnCode(statusCodeMsgList, StatusCode.OK))
            .build();
    }

    @Transactional
    public ComposeExtUrlResp composeExtUrl(String composeExtUrlReqJson) {
        final ComposeExtUrlReq req;
        try {
            ComposeExtUrlReq.Builder builder = ComposeExtUrlReq.newBuilder();
            JsonFormat.parser().merge(composeExtUrlReqJson, builder);
            req = builder.build();
        } catch (Exception e) {
            log.error("Failed to convert string to proto: ", e, "\n", e.getStackTrace());
            return ComposeExtUrlResp.newBuilder()
                .setRt(ReturnCodeUtils.getReturnCode(statusCodeMsgList, StatusCode.PARSE_JSON_FAILED))
                .build();
        }

        // 提取参数
        Integer id = req.getExtUrlId();
        Map<Integer, String> kvMap = new HashMap<>();
        for (IntStrKvPB kv : req.getParamValList()) {
            kvMap.put(kv.getKey(), kv.getVal());
        }

        // 获取扩展URL配置
        ExtUrlConfig extUrlConfig = urlConfigRepo.findByIdAndIsDeletedFalse(id).orElse(null);
        if (extUrlConfig == null) {
            return ComposeExtUrlResp.newBuilder()
                .setRt(ReturnCodeUtils.getReturnCode(statusCodeMsgList, StatusCode.EXT_URL_NOT_FOUND))
                .build();
        }
        ExtUrlPB extUrlPb = ProtoUtils.decodeExtUrl(extUrlConfig.getExtUrlPb());
        if (extUrlPb == null) {
            return ComposeExtUrlResp.newBuilder()
                .setRt(ReturnCodeUtils.getReturnCode(statusCodeMsgList, StatusCode.EXT_URL_NOT_FOUND))
                .build();
        }

        // 生成URL
        StringBuilder sb = new StringBuilder();
        sb.append(extUrlPb.getSchemaToPort());

        for (UrlParamPB pb : extUrlPb.getPathList()) {
            String param = fillParam(kvMap, pb, true);
            if (StrUtils.isBlank(param)) continue;
            sb.append("/");
            sb.append(param);
        }

        if (extUrlPb.getParamCount() > 0) {
            sb.append("?");
            List<String> params = new ArrayList<>();
            for (UrlParamPB pb : extUrlPb.getParamList()) {
                String param = fillParam(kvMap, pb, false);
                if (StrUtils.isBlank(param)) continue;
                params.add(param);
            }
            sb.append(String.join("&", params));
        }
        String composedUrl = sb.toString();

        return ComposeExtUrlResp.newBuilder()
            .setRt(ReturnCodeUtils.getReturnCode(statusCodeMsgList, StatusCode.OK))
            .setComposedUrl(composedUrl)
            .build();
    }

    public String getUrlPattern(ExtUrlPB extUrlPb, Map<Integer, String> paramMap) {
        if (extUrlPb == null) return "";

        StringBuilder sb = new StringBuilder();
        sb.append(extUrlPb.getSchemaToPort());
        for (UrlParamPB pb : extUrlPb.getPathList()) {
            String paramPattern = getParamPattern(paramMap, pb, true);
            if (StrUtils.isBlank(paramPattern)) continue;
            sb.append("/");
            sb.append(paramPattern);
        }

        if (extUrlPb.getParamCount() <= 0) return sb.toString();
        sb.append("?");
        List<String> params = new ArrayList<>();
        for (UrlParamPB pb : extUrlPb.getParamList()) {
            String paramPattern = getParamPattern(paramMap, pb, false);
            if (StrUtils.isBlank(paramPattern)) continue;
            params.add(paramPattern);
        }
        sb.append(String.join("&", params));

        return sb.toString();
    }

    private String getParamPattern(Map<Integer, String> paramMap, UrlParamPB pb, boolean isPath) {
        String paramIdName = paramMap.get(pb.getParamValueId());
        if (paramIdName == null) {
            paramIdName = pb.getCustomValue();
        }
        String encoderName = pb.getParamEncoderId() == Consts.EXT_URL_BASE64_ENCODE_ID ?
            "base64" : pb.getParamEncoderId() == Consts.EXT_URL_MD5_ENCODE_ID ? "md5" : null;

        StringBuilder sb = new StringBuilder();
        if (!isPath) {  // param
            if (StrUtils.isBlank(paramIdName)) return null;
            sb.append(pb.getParamName()).append("=");
        }
        sb.append("${");
        if (encoderName != null) {
            sb.append(encoderName).append("(");
        }
        sb.append(paramIdName);
        if (encoderName != null) {
            sb.append(")");
        }
        sb.append("}");

        return sb.toString();
    }

    private String fillParam(Map<Integer, String> kvMap, UrlParamPB pb, boolean isPath) {
        // 获取参数值（含编码）
        String paramIdValue = kvMap.get(pb.getParamValueId());
        if (paramIdValue == null) {
            paramIdValue = pb.getCustomValue();
        }
        if (pb.getParamEncoderId() == Consts.EXT_URL_BASE64_ENCODE_ID) {
            try {
                paramIdValue = Base64.getEncoder().encodeToString(paramIdValue.getBytes("UTF-8"));
            } catch (Exception e) {
                log.error("Base64 encoding error: ", e);
                return null;
            }
        } else if (pb.getParamEncoderId() == Consts.EXT_URL_MD5_ENCODE_ID) {
            paramIdValue = getMD5(paramIdValue);
        }

        StringBuilder sb = new StringBuilder();
        if (!isPath) {  // param
            if (StrUtils.isBlank(paramIdValue)) return null;
            sb.append(pb.getParamName()).append("=");
        }
        sb.append(paramIdValue);

        return sb.toString();
    }

    public static String getMD5(String inputTxt) {
        try {
            // 创建 MD5 实例
            MessageDigest md = MessageDigest.getInstance("MD5");
            // 将输入字符串转换为字节数组（默认 UTF-8 编码）
            byte[] hashBytes = md.digest(inputTxt.getBytes("UTF-8"));
            // 转换为十六进制字符串
            StringBuilder sb = new StringBuilder();
            for (byte b : hashBytes) {
                sb.append(String.format("%02x", b));
            }
            return sb.toString();
        } catch (Exception e) {
            log.error("MD5 calculation error: ", e);
            return "";
        }
    }

    private final List<String> statusCodeMsgList;
    private final Map<Integer, String> paramValueIdNameMap;

    private final ConfigProtoService protoService;
    private final UserService userService;
    private final ExtUrlConfigRepository urlConfigRepo;
}