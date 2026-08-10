package com.jingyicare.jingyi_icis_engine.service.ca;

import java.util.Base64;
import java.util.List;

import org.springframework.stereotype.Service;

import com.jingyicare.jingyi_icis_engine.entity.users.Account;
import com.jingyicare.jingyi_icis_engine.repository.users.AccountRepository;
import com.jingyicare.jingyi_icis_engine.repository.users.RbacDepartmentRepository;
import com.jingyicare.jingyi_icis_engine.proto.IcisWebApi.GetRealtimeCaSignImageReq;
import com.jingyicare.jingyi_icis_engine.proto.IcisWebApi.GetRealtimeCaSignImageResp;
import com.jingyicare.jingyi_icis_engine.proto.IcisWebApi.StatusCode;
import com.jingyicare.jingyi_icis_engine.proto.config.IcisSettings.AppSettingsPB;
import com.jingyicare.jingyi_icis_engine.service.ConfigProtoService;
import com.jingyicare.jingyi_icis_engine.service.ca.CaSignImageValidator.ValidatedSignImage;
import com.jingyicare.jingyi_icis_engine.service.ca.client.CigCaClient;
import com.jingyicare.jingyi_icis_engine.service.settings.SettingService;
import com.jingyicare.jingyi_icis_engine.service.users.UserService;
import com.jingyicare.jingyi_icis_engine.utils.ProtoUtils;
import com.jingyicare.jingyi_icis_engine.utils.ReturnCodeUtils;
import com.jingyicare.jingyi_icis_engine.utils.StrUtils;

import io.grpc.Status;
import io.grpc.StatusRuntimeException;
import lombok.extern.slf4j.Slf4j;

@Service
@Slf4j
public class CaService {
    public CaService(
        ConfigProtoService protoService,
        UserService userService,
        AccountRepository accountRepo,
        RbacDepartmentRepository deptRepo,
        CaAccessPolicy accessPolicy,
        SettingService settingService,
        CigCaClient cigCaClient,
        CaSignImageValidator imageValidator
    ) {
        this.statusCodeMsgs = protoService.getConfig().getText().getStatusCodeMsgList();
        this.userService = userService;
        this.accountRepo = accountRepo;
        this.deptRepo = deptRepo;
        this.accessPolicy = accessPolicy;
        this.settingService = settingService;
        this.cigCaClient = cigCaClient;
        this.imageValidator = imageValidator;
    }

    public GetRealtimeCaSignImageResp getSignImage(String requestJson) {
        String callerAccountId = userService.getCtxAccountId();
        if (StrUtils.isBlank(callerAccountId)) return error(StatusCode.ACCOUNT_NOT_FOUND);

        final GetRealtimeCaSignImageReq request;
        try {
            request = ProtoUtils.parseJsonToProto(requestJson, GetRealtimeCaSignImageReq.newBuilder());
        } catch (Exception e) {
            log.warn("[CA_SIGN_TRACE][ICIS_ENGINE] stage=request_rejected reason=parse_failed");
            return error(StatusCode.PARSE_JSON_FAILED);
        }
        String deptId = request.getDeptId();
        long accountId = request.getAccountId();
        log.info("[CA_SIGN_TRACE][ICIS_ENGINE] stage=request_received callerAccountId={} deptId={} accountId={}",
            callerAccountId, deptId, accountId);
        if (StrUtils.isBlank(deptId) || accountId <= 0) return error(StatusCode.PARSE_JSON_FAILED);
        if (deptRepo.findByDeptId(deptId).isEmpty()) return error(StatusCode.DEPARTMENT_NOT_FOUND);
        if (!accessPolicy.canCurrentUserAccessDept(deptId)) return error(StatusCode.CA_ACCOUNT_NOT_IN_DEPT);

        Account account = accountRepo.findByIdAndIsDeletedFalse(accountId).orElse(null);
        if (account == null || (account.getIsDisabled() != null && account.getIsDisabled() != 0)) {
            return error(StatusCode.ACCOUNT_NOT_FOUND);
        }
        if (!accessPolicy.accountBelongsToDept(account.getAccountId(), deptId)) {
            return error(StatusCode.CA_ACCOUNT_NOT_IN_DEPT);
        }

        AppSettingsPB settings = settingService.getAppSettingsForService(deptId);
        log.info("[CA_SIGN_TRACE][ICIS_ENGINE] stage=config_checked deptId={} accountId={} appEnableCa={} cigClientEnabled={}",
            deptId, accountId, settings.getEnableCa(), cigCaClient.isEnabled());
        if (!settings.getEnableCa() || !cigCaClient.isEnabled()) return error(StatusCode.CA_SERVICE_NOT_ENABLED);

        try {
            log.info("[CA_SIGN_TRACE][ICIS_ENGINE] stage=grpc_request deptId={} accountId={}", deptId, accountId);
            ValidatedSignImage image = imageValidator.validate(cigCaClient.getSignImage(accountId), accountId);
            log.info("[CA_SIGN_TRACE][ICIS_ENGINE] stage=http_response_ready deptId={} accountId={} source={} mediaType={} imageBytes={} sha256={} width={} height={}",
                deptId, accountId, image.source(), image.mediaType(), image.data().length,
                image.sha256(), image.width(), image.height());
            return GetRealtimeCaSignImageResp.newBuilder()
                .setRt(ReturnCodeUtils.getReturnCode(statusCodeMsgs, StatusCode.OK))
                .setAccountId(accountId)
                .setImageB64(Base64.getEncoder().encodeToString(image.data()))
                .setMediaType(image.mediaType())
                .setSha256(image.sha256())
                .setWidth(image.width())
                .setHeight(image.height())
                .setSource(image.source())
                .build();
        } catch (StatusRuntimeException e) {
            StatusCode mapped = mapGrpcStatus(e.getStatus().getCode());
            log.warn("[CA_SIGN_TRACE][ICIS_ENGINE] stage=grpc_failed deptId={} accountId={} grpcCode={}",
                deptId, accountId, e.getStatus().getCode());
            return error(mapped);
        } catch (IllegalArgumentException e) {
            log.warn("[CA_SIGN_TRACE][ICIS_ENGINE] stage=validation_rejected deptId={} accountId={} reason={}",
                deptId, accountId, e.getMessage());
            return error(StatusCode.CA_SERVICE_INVALID_RESPONSE);
        } catch (RuntimeException e) {
            log.error("[CA_SIGN_TRACE][ICIS_ENGINE] stage=unexpected_error deptId={} accountId={}", deptId, accountId, e);
            return error(StatusCode.CA_SERVICE_ERROR);
        }
    }

    private StatusCode mapGrpcStatus(Status.Code code) {
        return switch (code) {
            case INVALID_ARGUMENT -> StatusCode.PARSE_JSON_FAILED;
            case NOT_FOUND, FAILED_PRECONDITION -> StatusCode.CA_SIGN_IMAGE_NOT_FOUND;
            case UNAVAILABLE -> StatusCode.CA_SERVICE_UNAVAILABLE;
            case DEADLINE_EXCEEDED -> StatusCode.CA_SERVICE_TIMEOUT;
            default -> StatusCode.CA_SERVICE_ERROR;
        };
    }

    private GetRealtimeCaSignImageResp error(StatusCode statusCode) {
        return GetRealtimeCaSignImageResp.newBuilder()
            .setRt(ReturnCodeUtils.getReturnCode(statusCodeMsgs, statusCode))
            .build();
    }

    private final List<String> statusCodeMsgs;
    private final UserService userService;
    private final AccountRepository accountRepo;
    private final RbacDepartmentRepository deptRepo;
    private final CaAccessPolicy accessPolicy;
    private final SettingService settingService;
    private final CigCaClient cigCaClient;
    private final CaSignImageValidator imageValidator;
}
