package com.jingyicare.jingyi_icis_engine.grpc;

import java.io.IOException;
import javax.annotation.PostConstruct;
import javax.annotation.PreDestroy;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import io.grpc.Server;
import io.grpc.ServerBuilder;
import io.grpc.stub.StreamObserver;
import lombok.extern.slf4j.Slf4j;

import com.jingyicare.jingyi_icis_engine.proto.shared.Shared.*;
import com.jingyicare.jingyi_icis_engine.service.*;
import com.jingyicare.jingyi_icis_engine.service.patients.PatientDeviceService;
import com.jingyicare.jingyi_icis_engine.utils.*;

@Service
@Slf4j
public class EngineServiceImpl extends EngineServiceGrpc.EngineServiceImplBase {

    public EngineServiceImpl(
        @Value("${engine_port}") int port,
        @Value("${grpc.server.enabled:true}") boolean grpcServerEnabled,
        @Autowired EngineExtClient engineExtClient,
        @Autowired ConfigProtoService configProtoService,
        @Autowired PatientDeviceService patientDeviceService
    ) {
        this.port = port;
        this.grpcServerEnabled = grpcServerEnabled;
        this.engineExtClient = engineExtClient;
        this.configProtoService = configProtoService;

        log.info("EngineServiceImpl: " + port);
    }

    @Override
    public void syncHisPatient(SyncHisPatientReq request, StreamObserver<GenericResp> responseObserver) {
        // 调用引擎接口
        GenericResp response = GenericResp.newBuilder().setRt(
            configProtoService.getEngineReturnCode(EngineStatusCode.ENGINE_OK)
        ).build();
        responseObserver.onNext(response);
        responseObserver.onCompleted();
    }

    @PostConstruct
    public void startGrpcServer() throws IOException {
        if (!grpcServerEnabled) {
            log.info("gRPC Server is disabled");
            return;
        }
        server = ServerBuilder.forPort(port)
                .addService(this)
                .build()
                .start();
        log.info("gRPC Server started, listening on " + port);
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            System.err.println("*** shutting down gRPC server since JVM is shutting down");
            stopGrpcServer();
            System.err.println("*** server shut down");
        }));
    }

    @PreDestroy
    public void stopGrpcServer() {
        if (server != null) {
            server.shutdown();
            System.out.println("gRPC Server shut down");
        }
    }

    public void blockUntilShutdown() throws InterruptedException {
        if (server != null) {
            server.awaitTermination();
        }
    }

    private boolean grpcServerEnabled;
    private int port;
    private Server server;
    private EngineExtClient engineExtClient;
    private ConfigProtoService configProtoService;
    private PatientDeviceService patientDeviceService;
}