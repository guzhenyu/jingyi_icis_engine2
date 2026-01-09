package com.jingyicare.jingyi_icis_engine.grpc;

import java.util.List;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import io.grpc.ManagedChannel;
import io.grpc.ManagedChannelBuilder;
import io.grpc.stub.StreamObserver;

import lombok.extern.slf4j.Slf4j;

import com.jingyicare.jingyi_icis_engine.proto.shared.Shared.*;

@Service
@Slf4j
public class EngineExtClient {
    public EngineExtClient(
        @Value("${engine_ext_url}")String serverUrl, @Value("${engine_ext_port}") int serverPort
    ) {
        this.serverUrl = serverUrl;
        this.serverPort = serverPort;

        // 设置连接 gRPC 服务的地址
        this.channel = ManagedChannelBuilder.forAddress(serverUrl, serverPort)
                .usePlaintext()  // 禁用加密，假设不使用 SSL/TLS
                .build();
        this.asyncStub = EngineServiceGrpc.newStub(channel); // 异步调用的存根（stub）
        this.blockingStub = EngineServiceGrpc.newBlockingStub(channel); // 同步调用的存根（stub）

        // 检查连接是否成功
        if (channel.isShutdown() || channel.isTerminated()) {
            throw new RuntimeException("Failed to connect to gRPC server.");
        }
    }

    public ReturnCode syncHisPatient(SyncHisPatientReq req) {
        // 使用同步方式调用 gRPC 服务
        GenericResp response = blockingStub.syncHisPatient(req);
        return response.getRt();
    }

    // 关闭连接
    public void shutdown() throws InterruptedException {
        if (channel != null) {
            channel.shutdown().awaitTermination(5, java.util.concurrent.TimeUnit.SECONDS);
        }
    }

    private final String serverUrl;
    private final int serverPort;

    private final ManagedChannel channel;
    private final EngineServiceGrpc.EngineServiceStub asyncStub;
    private final EngineServiceGrpc.EngineServiceBlockingStub blockingStub;
}