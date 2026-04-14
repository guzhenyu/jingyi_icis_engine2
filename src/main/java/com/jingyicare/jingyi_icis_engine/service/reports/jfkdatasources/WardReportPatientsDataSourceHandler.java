package com.jingyicare.jingyi_icis_engine.service.reports.jfkdatasources;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.font.PDFont;
import org.apache.pdfbox.pdmodel.font.PDType0Font;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import com.jingyicare.jingyi_icis_engine.entity.reports.WardReport;
import com.jingyicare.jingyi_icis_engine.proto.IcisWebApi.StatusCode;
import com.jingyicare.jingyi_icis_engine.proto.IcisWebApi.WardPatientStatsPB;
import com.jingyicare.jingyi_icis_engine.proto.IcisWebApi.WardReportPB;
import com.jingyicare.jingyi_icis_engine.proto.config.IcisJfk.JfkDataSourcePB;
import com.jingyicare.jingyi_icis_engine.proto.shared.Shared.ReturnCode;
import com.jingyicare.jingyi_icis_engine.service.reports.common.JfkPdfUtils;
import com.jingyicare.jingyi_icis_engine.utils.Pair;
import com.jingyicare.jingyi_icis_engine.utils.ProtoUtils;
import com.jingyicare.jingyi_icis_engine.utils.StrUtils;
import com.jingyicare.jingyi_icis_engine.utils.TimeUtils;

@Component
public class WardReportPatientsDataSourceHandler extends AbstractJfkDataSourceHandler {
    public WardReportPatientsDataSourceHandler(JfkDataSourceSupport support) {
        super(support);
    }

    @Override
    public String getMetaId() {
        return META_ID;
    }

    @Override
    public Pair<ReturnCode, JfkDataSourcePB> handle(JfkDataSourcePB input) {
        String deptId = getDeptIdInput(input, "dept_id");
        String shiftTimeIso = getStringInput(input, "shift_time");

        if (StrUtils.isBlank(deptId) || StrUtils.isBlank(shiftTimeIso)) {
            List<String> missingFields = new ArrayList<>();
            if (StrUtils.isBlank(deptId)) missingFields.add("dept_id");
            if (StrUtils.isBlank(shiftTimeIso)) missingFields.add("shift_time");
            return error(StatusCode.JFK_MISSING_REQUIRED_FIELD, joinMissingFields(missingFields));
        }

        LocalDateTime shiftStartTime = TimeUtils.fromIso8601String(shiftTimeIso, "UTC");
        if (shiftStartTime == null) {
            return error(StatusCode.INVALID_TIME_FORMAT);
        }

        JfkDataSourcePB.Builder outputBuilder = newOutputBuilder(input);
        WardReport wardReport = support.getWardReportRepo()
            .findByDeptIdAndShiftStartTimeAndIsDeletedFalse(deptId, shiftStartTime)
            .orElse(null);
        if (wardReport == null) {
            support.fillWardReportPatientsEmpty(outputBuilder);
            return new Pair<>(returnCode(StatusCode.OK), outputBuilder.build());
        }

        WardReportPB wardReportPb = ProtoUtils.decodeWardReport(wardReport.getWardReportPb());
        if (wardReportPb == null) {
            support.fillWardReportPatientsEmpty(outputBuilder);
            return new Pair<>(returnCode(StatusCode.OK), outputBuilder.build());
        }

        byte[] wardReportFontDataBytes = support.getWardReportFontDataBytes();
        if (wardReportFontDataBytes == null) {
            support.fillWardReportPatientsEmpty(outputBuilder);
            return new Pair<>(returnCode(StatusCode.OK), outputBuilder.build());
        }

        List<String> diagnosisList = new ArrayList<>();
        List<String> dayNoteList = new ArrayList<>();
        List<String> eveningNoteList = new ArrayList<>();
        List<String> nightNoteList = new ArrayList<>();

        try (PDDocument document = new PDDocument()) {
            PDFont font = PDType0Font.load(document, new ByteArrayInputStream(wardReportFontDataBytes));
            for (WardPatientStatsPB stat : wardReportPb.getPatientStatsList()) {
                List<String> tmpDiagnosis = new ArrayList<>();
                JfkPdfUtils.wrapInto(
                    tmpDiagnosis,
                    support.buildPatientHeader(stat.getPid(), shiftStartTime),
                    font, 8f, 68f, 0f);
                List<String> diagnosisLines = stat.getDayShiftHandoverNote() == null
                    ? List.of("") : Arrays.asList(stat.getDiagnosis().split("\n"));
                tmpDiagnosis.addAll(JfkPdfUtils.getWrappedLines(font, 8f, 68f, 0f, diagnosisLines));

                List<String> dayNoteLines = stat.getDayShiftHandoverNote() == null
                    ? List.of("") : Arrays.asList(stat.getDayShiftHandoverNote().split("\n"));
                List<String> tmpDayNotes = JfkPdfUtils.getWrappedLines(font, 8f, 218f, 0f, dayNoteLines);

                List<String> eveningNoteLines = stat.getEveningShiftHandoverNote() == null
                    ? List.of("") : Arrays.asList(stat.getEveningShiftHandoverNote().split("\n"));
                List<String> tmpEveningNotes = JfkPdfUtils.getWrappedLines(font, 8f, 218f, 0f, eveningNoteLines);

                List<String> nightNoteLines = stat.getNightShiftHandoverNote() == null
                    ? List.of("") : Arrays.asList(stat.getNightShiftHandoverNote().split("\n"));
                List<String> tmpNightNotes = JfkPdfUtils.getWrappedLines(font, 8f, 218f, 0f, nightNoteLines);

                int maxSize = Math.max(
                    Math.max(tmpDiagnosis.size(), tmpDayNotes.size()),
                    Math.max(tmpEveningNotes.size(), tmpNightNotes.size()));
                support.padWithBlank(tmpDiagnosis, maxSize);
                support.padWithBlank(tmpDayNotes, maxSize);
                support.padWithBlank(tmpEveningNotes, maxSize);
                support.padWithBlank(tmpNightNotes, maxSize);

                tmpDiagnosis.add(" ");
                tmpDayNotes.add(" ");
                tmpEveningNotes.add(" ");
                tmpNightNotes.add(" ");

                diagnosisList.addAll(tmpDiagnosis);
                dayNoteList.addAll(tmpDayNotes);
                eveningNoteList.addAll(tmpEveningNotes);
                nightNoteList.addAll(tmpNightNotes);
            }
        } catch (IOException e) {
            log.error("Failed to wrap ward report patient notes: {}", e.getMessage());
            support.fillWardReportPatientsEmpty(outputBuilder);
            return new Pair<>(returnCode(StatusCode.OK), outputBuilder.build());
        }

        support.addArrayOutput(outputBuilder, "diagnosis", support.addPagePadding(diagnosisList));
        support.addArrayOutput(outputBuilder, "day_shift_notes", support.addPagePadding(dayNoteList));
        support.addArrayOutput(outputBuilder, "evening_shift_notes", support.addPagePadding(eveningNoteList));
        support.addArrayOutput(outputBuilder, "night_shift_notes", support.addPagePadding(nightNoteList));

        return new Pair<>(returnCode(StatusCode.OK), outputBuilder.build());
    }

    private static final Logger log = LoggerFactory.getLogger(WardReportPatientsDataSourceHandler.class);
    private static final String META_ID = "ward_report_patients";
}
