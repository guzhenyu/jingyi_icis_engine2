package com.jingyicare.jingyi_icis_engine.service.reports;

import java.time.LocalDateTime;
import java.util.*;

import org.apache.pdfbox.pdmodel.*;
import org.apache.pdfbox.pdmodel.common.*;
import org.apache.pdfbox.pdmodel.font.*;
import org.apache.pdfbox.pdmodel.graphics.image.PDImageXObject;

import com.jingyicare.jingyi_icis_engine.proto.config.IcisMonitoring.*;
import com.jingyicare.jingyi_icis_engine.proto.config.IcisMonitoringReport.*;
import com.jingyicare.jingyi_icis_engine.proto.config.IcisMonitoringReportAh2.*;
import com.jingyicare.jingyi_icis_engine.proto.shared.ValueMeta.*;

import com.jingyicare.jingyi_icis_engine.utils.*;

public class Ah2PdfContext {
    public Long pid;

    public PDDocument document;
    public PDType0Font font;
    public PDPage pdPage;
    public PDPageContentStream contentStream;

    public TableCommonPB tblCommon;
    public Map<String, ParamColMetaPB> colMetaMap;
    public TextStylePB tblTxtStyle;
    public LineStylePB tblLineStyle;

    public TablePB table;
    public float tableHeaderBottom;

    public Ah2PageData pageData;

    // 其他辅助信息
    public Map<String, MonitoringParamPB> mpMap;  // monitoring param map
    public Map<String, ValueMetaPB> mpVmMap;      // monitoring param valuemeta map
    public List<Pair<Pair<LocalDateTime/*start*/, LocalDateTime/*end*/>, Long/*accountId*/>> shiftNurses; // 交班护士列表

    public Map<Long, String> accountSignPicMap; // accountId -> signPic data url/base64
    public Map<Long, PDImageXObject> signImageCache; // runtime cache
}
