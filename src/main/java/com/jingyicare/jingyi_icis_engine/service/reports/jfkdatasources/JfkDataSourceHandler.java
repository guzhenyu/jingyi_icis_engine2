package com.jingyicare.jingyi_icis_engine.service.reports.jfkdatasources;

import com.jingyicare.jingyi_icis_engine.proto.config.IcisJfk.JfkDataSourcePB;
import com.jingyicare.jingyi_icis_engine.proto.shared.Shared.ReturnCode;
import com.jingyicare.jingyi_icis_engine.utils.Pair;

public interface JfkDataSourceHandler {
    String getMetaId();

    Pair<ReturnCode, JfkDataSourcePB> handle(JfkDataSourcePB input);
}
