package com.jingyicare.jingyi_icis_engine.utils;

import java.util.*;
import lombok.*;
import lombok.extern.slf4j.Slf4j;

import com.jingyicare.jingyi_icis_engine.proto.IcisWebApi.*;
import com.jingyicare.jingyi_icis_engine.proto.shared.Shared.*;

@Slf4j
public class ReturnCodeUtils {
    public static ReturnCode getReturnCode(List<String> statusCodeMsgList, StatusCode code) {
        return ReturnCode.newBuilder().setCode(code.ordinal())
            .setMsg(statusCodeMsgList.get(code.getNumber())).build();
    }

    public static ReturnCode getReturnCode(List<String> statusCodeMsgList, StatusCode code, String msg) {
        return ReturnCode.newBuilder().setCode(code.ordinal())
            .setMsg(statusCodeMsgList.get(code.getNumber()) + ", " + msg).build();
    }
}