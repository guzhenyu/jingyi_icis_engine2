package com.jingyicare.jingyi_icis_engine.service.ca.client;

import com.jingyicare.jingyi_cig.grpc.ca.GetSignImageResponsePB;

public interface CigCaClient extends AutoCloseable {
    boolean isEnabled();
    GetSignImageResponsePB getSignImage(long accountId);

    @Override
    void close();
}
