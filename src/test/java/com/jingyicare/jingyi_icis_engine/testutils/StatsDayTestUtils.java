package com.jingyicare.jingyi_icis_engine.testutils;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.jingyicare.jingyi_icis_engine.proto.IcisConfig.Config;
import com.jingyicare.jingyi_icis_engine.service.ProtoConfig;
import com.jingyicare.jingyi_icis_engine.service.settings.stats.StatsDayConfigReader;
import com.jingyicare.jingyi_icis_engine.service.settings.stats.StatsDayWindowResolver;

public final class StatsDayTestUtils {
    private StatsDayTestUtils() {}

    public static StatsDayWindowResolver newStatsDayWindowResolver(
        StatsDayConfigReader statsDayConfigReader, String zoneId
    ) {
        return new StatsDayWindowResolver(
            mockProtoConfig(Config.newBuilder().setZoneId(zoneId).build()),
            statsDayConfigReader);
    }

    public static ProtoConfig mockProtoConfig(Config config) {
        ProtoConfig protoConfig = mock(ProtoConfig.class);
        when(protoConfig.getConfig()).thenReturn(config);
        return protoConfig;
    }
}
