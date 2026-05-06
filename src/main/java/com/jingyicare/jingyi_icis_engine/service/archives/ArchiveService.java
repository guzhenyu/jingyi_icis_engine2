package com.jingyicare.jingyi_icis_engine.service.archives;

import java.io.IOException;
import java.nio.file.Path;
import java.time.LocalDateTime;

public interface ArchiveService {
    String buildRelativeUrl(long pid, int type, LocalDateTime effectiveTimeUtc, String name);

    boolean store(
        long pid,
        int type,
        LocalDateTime effectiveTimeUtc,
        String name,
        String pbStr,
        int pageCount,
        Path sourcePdfPath
    ) throws IOException;
}
