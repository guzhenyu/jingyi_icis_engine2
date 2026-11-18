package com.jingyicare.jingyi_icis_engine.service.patients.bed;

import static com.jingyicare.jingyi_icis_engine.service.patients.PatientEnums.BED_TYPE_FIXED;
import static com.jingyicare.jingyi_icis_engine.service.patients.PatientEnums.BED_TYPE_TEMP;

import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

import org.springframework.stereotype.Service;

import lombok.extern.slf4j.Slf4j;

import com.jingyicare.jingyi_icis_engine.entity.patients.BedConfig;
import com.jingyicare.jingyi_icis_engine.entity.patients.BedCount;
import com.jingyicare.jingyi_icis_engine.proto.IcisWebApi.StatusCode;
import com.jingyicare.jingyi_icis_engine.repository.patients.BedConfigRepository;
import com.jingyicare.jingyi_icis_engine.repository.patients.BedCountRepository;
import com.jingyicare.jingyi_icis_engine.service.certs.CertificateReader;
import com.jingyicare.jingyi_icis_engine.service.certs.CertificateReader.BedLimits;
import com.jingyicare.jingyi_icis_engine.utils.Pair;
import com.jingyicare.jingyi_icis_engine.utils.StrUtils;

@Service
@Slf4j
public class BedMetaReader {
    public BedMetaReader(
        BedConfigRepository bedConfigRepo, BedCountRepository bedCountRepo,
        CertificateReader certificateReader
    ) {
        this.bedConfigRepo = bedConfigRepo;
        this.bedCountRepo = bedCountRepo;
        this.certificateReader = certificateReader;
    }

    public Optional<BedConfig> findActiveBedByHisNumber(String deptId, String hisBedNumber) {
        return bedConfigRepo.findByDepartmentIdAndHisBedNumberAndIsDeletedFalse(
            deptId, hisBedNumber);
    }

    public List<BedConfig> findActiveBedsByDepartment(String deptId) {
        return bedConfigRepo.findByDepartmentIdAndIsDeletedFalse(deptId);
    }

    public List<BedConfig> findActiveBedsByHisNumbers(List<String> hisBedNumbers) {
        if (hisBedNumbers.isEmpty()) return List.of();
        return bedConfigRepo.findByHisBedNumberInAndIsDeletedFalse(hisBedNumbers);
    }

    public Pair<StatusCode, Map<BedKey, String>> findDisplayBedNumbers(Collection<BedKey> beds) {
        Set<BedKey> requestedBeds = beds.stream()
            .filter(bed -> !StrUtils.isBlank(bed.deptId())
                && !StrUtils.isBlank(bed.hisBedNumber()))
            .collect(Collectors.toSet());
        List<String> hisBedNumbers = requestedBeds.stream()
            .map(BedKey::hisBedNumber).distinct().toList();
        Map<BedKey, String> displayBedNumbers = new HashMap<>();
        for (BedConfig config : findActiveBedsByHisNumbers(hisBedNumbers)) {
            BedKey key = new BedKey(config.getDepartmentId(), config.getHisBedNumber());
            if (!requestedBeds.contains(key)) continue;
            String previous = displayBedNumbers.putIfAbsent(key, config.getDisplayBedNumber());
            if (previous != null) {
                log.error("有效床位配置重复，科室={}，HIS 床号={}，显示床号=[{}, {}]",
                    key.deptId(), key.hisBedNumber(), previous, config.getDisplayBedNumber());
                return new Pair<>(StatusCode.INTERNAL_EXCEPTION, Map.of());
            }
        }
        return new Pair<>(StatusCode.OK, displayBedNumbers);
    }

    // 质控按生效时间从早到晚应用科室床位数变更。
    public List<BedCount> findBedCountHistory(String deptId) {
        return bedCountRepo.findByDeptIdAndIsDeletedFalseOrderByEffectiveTimeAsc(deptId);
    }

    public int countActiveBedsByType(String deptId, int bedType) {
        return Objects.requireNonNullElse(
            bedConfigRepo.countByDepartmentIdAndBedTypeAndIsDeletedFalse(deptId, bedType), 0);
    }

    public boolean checkBedAvailable(String deptId) {
        if (certificateReader.isTestMode()) return true;
        BedLimits limits = certificateReader.findBedLimits(deptId).orElse(null);
        if (limits == null) return false;

        int fixedBeds = countActiveBedsByType(deptId, BED_TYPE_FIXED);
        int tempBeds = countActiveBedsByType(deptId, BED_TYPE_TEMP);
        if (fixedBeds <= limits.maxFixedBeds() && tempBeds <= limits.maxTempBeds()) return true;
        log.error("科室 {} 床位超出授权：固定床 {}/{}，临时床 {}/{}", deptId,
            fixedBeds, limits.maxFixedBeds(), tempBeds, limits.maxTempBeds());
        return false;
    }

    public record BedKey(String deptId, String hisBedNumber) {}

    private final BedConfigRepository bedConfigRepo;
    private final BedCountRepository bedCountRepo;
    private final CertificateReader certificateReader;
}
