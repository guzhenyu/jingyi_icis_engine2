package com.jingyicare.jingyi_icis_engine.service.archives;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Objects;

import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

import lombok.extern.slf4j.Slf4j;

import com.jingyicare.jingyi_icis_engine.entity.archives.PatientArchive;
import com.jingyicare.jingyi_icis_engine.proto.config.IcisArchive.NursingReportCompactArchivePB;
import com.jingyicare.jingyi_icis_engine.repository.archives.PatientArchiveRepository;
import com.jingyicare.jingyi_icis_engine.service.ConfigProtoService;
import com.jingyicare.jingyi_icis_engine.service.users.UserService;
import com.jingyicare.jingyi_icis_engine.utils.Consts;
import com.jingyicare.jingyi_icis_engine.utils.Pair;
import com.jingyicare.jingyi_icis_engine.utils.ProtoUtils;
import com.jingyicare.jingyi_icis_engine.utils.StrUtils;
import com.jingyicare.jingyi_icis_engine.utils.TimeUtils;

@Service
@Slf4j
public class ArchiveServiceImpl implements ArchiveService {
    public ArchiveServiceImpl(
        ArchiveProperties archiveProperties,
        PatientArchiveRepository archiveRepo,
        UserService userService,
        ConfigProtoService protoService,
        TransactionTemplate transactionTemplate
    ) {
        this.archiveProperties = archiveProperties;
        this.archiveRepo = archiveRepo;
        this.userService = userService;
        this.zoneId = protoService.getConfig().getZoneId();
        this.transactionTemplate = transactionTemplate;
    }

    @Override
    public String buildRelativeUrl(long pid, int type, LocalDateTime effectiveTimeUtc, String name) {
        LocalDateTime localMidnightUtc = localMidnightUtc(effectiveTimeUtc);
        return "/archives/" + pid + "/" + archiveFileName(pid, type, localMidnightUtc, name);
    }

    @Override
    public boolean store(
        long pid,
        int type,
        LocalDateTime effectiveTimeUtc,
        String name,
        String pbStr,
        int pageCount,
        Path sourcePdfPath
    ) throws IOException {
        if (pid <= 0) {
            throw new IllegalArgumentException("pid must be positive");
        }
        if (type <= 0) {
            throw new IllegalArgumentException("type must be positive");
        }
        if (StrUtils.isBlank(pbStr)) {
            throw new IllegalArgumentException("pbStr is required");
        }
        if (pageCount < 0) {
            throw new IllegalArgumentException("pageCount must be non-negative");
        }
        if (sourcePdfPath == null) {
            throw new IllegalArgumentException("sourcePdfPath is required");
        }

        LocalDateTime localMidnightUtc = localMidnightUtc(effectiveTimeUtc);
        String relativeUrl = buildRelativeUrl(pid, type, localMidnightUtc, name);
        PatientArchive existing = archiveRepo
            .findByPidAndTypeAndLocalMidnightUtcAndIsDeletedFalse(pid, type, localMidnightUtc)
            .orElse(null);
        if (existing != null && sameArchive(existing, type, pbStr, pageCount, relativeUrl)) {
            return true;
        }
        if (existing != null && archiveChanged(existing, type, pbStr, pageCount, relativeUrl)) {
            notifyPaperlessBeforeChange(existing, relativeUrl);
        }

        copyPdf(pid, type, localMidnightUtc, name, sourcePdfPath);
        String accountId = currentAccountId();

        Boolean result = transactionTemplate.execute(status ->
            storeRecord(pid, type, localMidnightUtc, pbStr, pageCount, relativeUrl, accountId));
        return Boolean.TRUE.equals(result);
    }

    private boolean storeRecord(
        long pid,
        int type,
        LocalDateTime localMidnightUtc,
        String pbStr,
        int pageCount,
        String relativeUrl,
        String accountId
    ) {
        PatientArchive existing = archiveRepo
            .findActiveForUpdate(pid, type, localMidnightUtc)
            .orElse(null);
        LocalDateTime nowUtc = TimeUtils.getNowUtc();

        if (existing == null) {
            archiveRepo.save(newArchive(pid, type, localMidnightUtc, pageCount, pbStr, relativeUrl, accountId, nowUtc));
            return false;
        }

        boolean samePb = sameArchiveData(type, existing.getDataPb(), pbStr);
        boolean sameUrl = Objects.equals(existing.getRelativeUrl(), relativeUrl);
        boolean samePageCount = Objects.equals(existing.getPageCount(), pageCount);
        if (samePb && sameUrl && samePageCount) {
            return true;
        }
        if (samePb) {
            existing.setPageCount(pageCount);
            existing.setRelativeUrl(relativeUrl);
            existing.setModifiedAt(nowUtc);
            existing.setModifiedBy(accountId);
            archiveRepo.save(existing);
            return true;
        }

        existing.setIsDeleted(true);
        existing.setDeletedAt(nowUtc);
        existing.setDeletedBy(accountId);
        archiveRepo.saveAndFlush(existing);

        archiveRepo.save(newArchive(pid, type, localMidnightUtc, pageCount, pbStr, relativeUrl, accountId, nowUtc));
        return false;
    }

    private PatientArchive newArchive(
        long pid,
        int type,
        LocalDateTime localMidnightUtc,
        int pageCount,
        String pbStr,
        String relativeUrl,
        String accountId,
        LocalDateTime nowUtc
    ) {
        return PatientArchive.builder()
            .pid(pid)
            .type(type)
            .localMidnightUtc(localMidnightUtc)
            .pageCount(pageCount)
            .dataPb(pbStr)
            .relativeUrl(relativeUrl)
            .isDeleted(false)
            .modifiedAt(nowUtc)
            .modifiedBy(accountId)
            .build();
    }

    private void copyPdf(
        long pid,
        int type,
        LocalDateTime localMidnightUtc,
        String name,
        Path sourcePdfPath
    ) throws IOException {
        Path sourcePath = sourcePdfPath.toAbsolutePath().normalize();
        if (!Files.exists(sourcePath)) {
            throw new IOException("Archive source PDF does not exist: " + sourcePath);
        }

        Path root = archiveRoot();
        Path patientDir = root.resolve(String.valueOf(pid)).normalize();
        if (!patientDir.startsWith(root)) {
            throw new IOException("Invalid archive patient directory: " + patientDir);
        }

        Files.createDirectories(patientDir);
        Path targetPath = patientDir.resolve(archiveFileName(pid, type, localMidnightUtc, name)).normalize();
        if (!targetPath.startsWith(patientDir)) {
            throw new IOException("Invalid archive target path: " + targetPath);
        }
        if (sourcePath.equals(targetPath)) {
            return;
        }
        Files.copy(sourcePath, targetPath, StandardCopyOption.REPLACE_EXISTING);
    }

    private Path archiveRoot() {
        return Path.of(archiveProperties.getRootPath()).toAbsolutePath().normalize();
    }

    private String archiveFileName(long pid, int type, LocalDateTime localMidnightUtc, String name) {
        LocalDateTime localMidnight = TimeUtils.getLocalDateTimeFromUtc(localMidnightUtc, zoneId);
        String timestamp = localMidnight.format(FILE_TIME_FORMATTER);
        return pid + "_" + type + "_" + timestamp + "_" + sanitizeName(name) + ".pdf";
    }

    private String sanitizeName(String name) {
        String value = StrUtils.isBlank(name) ? "archive" : name.trim();
        value = value.replaceAll("[\\\\/:*?\"<>|\\p{Cntrl}]+", "_");
        value = value.replaceAll("\\s+", "_");
        value = value.replaceAll("^\\.+|\\.+$", "");
        return StrUtils.isBlank(value) ? "archive" : value;
    }

    private LocalDateTime localMidnightUtc(LocalDateTime effectiveTimeUtc) {
        LocalDateTime localMidnightUtc = TimeUtils.getLocalMidnightUtc(effectiveTimeUtc, zoneId);
        if (localMidnightUtc == null) {
            throw new IllegalArgumentException("effectiveTimeUtc is required");
        }
        return localMidnightUtc;
    }

    private String currentAccountId() {
        Pair<String, String> account = userService.getAccountWithAutoId();
        if (account == null || StrUtils.isBlank(account.getFirst())) {
            throw new IllegalStateException("Current account not found");
        }
        return account.getFirst();
    }

    private boolean sameArchive(PatientArchive existing, int type, String pbStr, int pageCount, String relativeUrl) {
        return sameArchiveData(type, existing.getDataPb(), pbStr)
            && Objects.equals(existing.getPageCount(), pageCount)
            && Objects.equals(existing.getRelativeUrl(), relativeUrl);
    }

    private boolean archiveChanged(PatientArchive existing, int type, String pbStr, int pageCount, String relativeUrl) {
        return !sameArchiveData(type, existing.getDataPb(), pbStr)
            || !Objects.equals(existing.getPageCount(), pageCount)
            || !Objects.equals(existing.getRelativeUrl(), relativeUrl);
    }

    private boolean sameArchiveData(int type, String leftPbStr, String rightPbStr) {
        if (Objects.equals(leftPbStr, rightPbStr)) {
            return true;
        }
        return Objects.equals(normalizedArchiveData(type, leftPbStr), normalizedArchiveData(type, rightPbStr));
    }

    private String normalizedArchiveData(int type, String pbStr) {
        if (type != Consts.PATIENT_ARCHIVE_TYPE_NURSING_REPORT_COMPACT || StrUtils.isBlank(pbStr)) {
            return pbStr;
        }
        NursingReportCompactArchivePB archivePb = ProtoUtils.decodeNursingReportCompactArchive(pbStr);
        if (archivePb == null) {
            return pbStr;
        }
        return ProtoUtils.encodeNursingReportCompactArchive(archivePb.toBuilder()
            .clearGeneratedAtIso8601()
            .clearRelativeUrl()
            .build());
    }

    private void notifyPaperlessBeforeChange(PatientArchive existing, String nextRelativeUrl) {
        log.info(
            "TODO notify paperless archive change before DB transaction, archiveId={}, oldUrl={}, newUrl={}",
            existing.getId(), existing.getRelativeUrl(), nextRelativeUrl
        );
    }

    private static final DateTimeFormatter FILE_TIME_FORMATTER = DateTimeFormatter.ofPattern("yyyyMMddHHmm");

    private final ArchiveProperties archiveProperties;
    private final PatientArchiveRepository archiveRepo;
    private final UserService userService;
    private final String zoneId;
    private final TransactionTemplate transactionTemplate;
}
