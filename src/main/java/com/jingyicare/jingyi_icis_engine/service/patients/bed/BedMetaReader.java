package com.jingyicare.jingyi_icis_engine.service.patients.bed;

import static com.jingyicare.jingyi_icis_engine.service.patients.PatientEnums.BED_TYPE_FIXED;
import static com.jingyicare.jingyi_icis_engine.service.patients.PatientEnums.BED_TYPE_TEMP;

import java.util.List;
import java.util.Objects;
import java.util.Optional;

import org.springframework.stereotype.Service;

import lombok.extern.slf4j.Slf4j;

import com.jingyicare.jingyi_icis_engine.entity.patients.BedConfig;
import com.jingyicare.jingyi_icis_engine.entity.patients.BedCount;
import com.jingyicare.jingyi_icis_engine.repository.patients.BedConfigRepository;
import com.jingyicare.jingyi_icis_engine.repository.patients.BedCountRepository;
import com.jingyicare.jingyi_icis_engine.service.certs.CertificateReader;
import com.jingyicare.jingyi_icis_engine.service.certs.CertificateReader.BedLimits;

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

    private final BedConfigRepository bedConfigRepo;
    private final BedCountRepository bedCountRepo;
    private final CertificateReader certificateReader;
}
