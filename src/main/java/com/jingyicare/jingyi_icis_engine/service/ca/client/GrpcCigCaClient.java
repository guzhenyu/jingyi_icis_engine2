package com.jingyicare.jingyi_icis_engine.service.ca.client;

import java.util.concurrent.TimeUnit;

import com.jingyicare.jingyi_icis_engine.service.ca.config.CaClientProperties;
import com.jingyicare.jingyi_cig.grpc.ca.CaSourceSystemPB;
import com.jingyicare.jingyi_cig.grpc.ca.CigCaServiceGrpc;
import com.jingyicare.jingyi_cig.grpc.ca.GetSignImageRequestPB;
import com.jingyicare.jingyi_cig.grpc.ca.GetSignImageResponsePB;

import io.grpc.ManagedChannel;
import io.grpc.ManagedChannelBuilder;

public class GrpcCigCaClient implements CigCaClient {
    public GrpcCigCaClient(CaClientProperties properties) {
        this.enabled = properties.isEnabled();
        this.deadlineMs = properties.getGetSignImageDeadlineMs();
        this.channel = enabled
            ? ManagedChannelBuilder.forAddress(properties.getHost().trim(), properties.getPort())
                .usePlaintext()
                .build()
            : null;
        this.stub = channel == null ? null : CigCaServiceGrpc.newBlockingStub(channel);
    }

    @Override
    public boolean isEnabled() {
        return enabled;
    }

    @Override
    public GetSignImageResponsePB getSignImage(long accountId) {
        if (!enabled || stub == null) throw new IllegalStateException("CIG CA client is disabled");
        GetSignImageRequestPB request = GetSignImageRequestPB.newBuilder()
            .setSourceSystem(CaSourceSystemPB.CA_SOURCE_SYSTEM_ICIS)
            .setAccountId(accountId)
            .build();
        return stub.withDeadlineAfter(deadlineMs, TimeUnit.MILLISECONDS).getSignImage(request);
    }

    @Override
    public void close() {
        if (channel == null) return;
        channel.shutdown();
        try {
            if (!channel.awaitTermination(3, TimeUnit.SECONDS)) channel.shutdownNow();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            channel.shutdownNow();
        }
    }

    private final boolean enabled;
    private final long deadlineMs;
    private final ManagedChannel channel;
    private final CigCaServiceGrpc.CigCaServiceBlockingStub stub;
}
