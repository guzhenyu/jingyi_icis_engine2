package com.jingyicare.jingyi_icis_engine.config;

import java.util.ArrayList;
import java.util.List;

import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.io.PrintWriter;
import java.util.Enumeration;

import org.springframework.security.core.AuthenticationException;
import org.springframework.security.web.authentication.AuthenticationFailureHandler;

import com.jingyicare.jingyi_icis_engine.proto.IcisWebApi.*;
import com.jingyicare.jingyi_icis_engine.proto.shared.Shared.*;
import com.jingyicare.jingyi_icis_engine.utils.*;

public class IcisAuthenticationFailureHandler implements AuthenticationFailureHandler {
    @Override
    public void onAuthenticationFailure(HttpServletRequest request, HttpServletResponse response,
                                        AuthenticationException exception) throws IOException, ServletException {
        boolean isAjax = "application/x-www-form-urlencoded".equals(request.getHeader("Content-Type"));

        if (isAjax) {
            response.setStatus(HttpServletResponse.SC_OK);
            response.setContentType("application/json;charset=UTF-8");

            PrintWriter writer = response.getWriter();
            GenericResp resp = GenericResp.newBuilder()
                .setRt(
                    ReturnCode.newBuilder().setCode(StatusCode.WRONG_PASSWORD.ordinal())
                        .setMsg("用户名或密码错误").build()
                )
                .build();
            writer.write(ProtoUtils.protoToJson(resp));
            writer.flush();
            writer.close();
        } else {
            response.sendRedirect("/login.html?error=true");
        }
    }

    private void printRequestDetails(HttpServletRequest request) {
        List<String> dbgLines = new ArrayList<>();
        dbgLines.add("Request Method: " + request.getMethod());
        dbgLines.add("Request URL: " + request.getRequestURL());
        dbgLines.add("Request URI: " + request.getRequestURI());
        dbgLines.add("Query String: " + request.getQueryString());
        dbgLines.add("Protocol: " + request.getProtocol());
        dbgLines.add("Remote Addr: " + request.getRemoteAddr());
        dbgLines.add("Remote Host: " + request.getRemoteHost());
        dbgLines.add("Remote Port: " + request.getRemotePort());
        dbgLines.add("Local Addr: " + request.getLocalAddr());
        dbgLines.add("Local Name: " + request.getLocalName());
        dbgLines.add("Local Port: " + request.getLocalPort());

        dbgLines.add("Headers:");
        Enumeration<String> headerNames = request.getHeaderNames();
        while (headerNames.hasMoreElements()) {
            String headerName = headerNames.nextElement();
            dbgLines.add("  " + headerName + ": " + request.getHeader(headerName));
        }

        dbgLines.add("Parameters:");
        Enumeration<String> parameterNames = request.getParameterNames();
        while (parameterNames.hasMoreElements()) {
            String parameterName = parameterNames.nextElement();
            dbgLines.add("  " + parameterName + ": " + request.getParameter(parameterName));
        }

        for (String line : dbgLines) {
            System.out.println(line);
        }
    }
}