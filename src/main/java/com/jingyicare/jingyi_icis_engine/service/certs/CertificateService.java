package com.jingyicare.jingyi_icis_engine.service.certs;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileReader;
import java.security.PublicKey;
import java.security.spec.X509EncodedKeySpec;
import java.time.LocalDateTime;
import java.util.*;
import java.util.stream.Collectors;
import javax.crypto.Cipher;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.stereotype.Service;

import com.google.protobuf.TextFormat;
import lombok.*;
import lombok.extern.slf4j.Slf4j;

import com.jingyicare.jingyi_icis_engine.proto.config.IcisCertificate.*;
import com.jingyicare.jingyi_icis_engine.proto.shared.Shared.*;

import com.jingyicare.jingyi_icis_engine.entity.patients.*;
import com.jingyicare.jingyi_icis_engine.entity.users.*;
import com.jingyicare.jingyi_icis_engine.repository.patients.*;
import com.jingyicare.jingyi_icis_engine.repository.users.*;
import com.jingyicare.jingyi_icis_engine.service.*;
import com.jingyicare.jingyi_icis_engine.utils.*;

@Service
@Slf4j
public class CertificateService {
    public CertificateService(
        ConfigurableApplicationContext context,
        @Value("${public_key_file_path}") String publicKeyFilePath,
        @Value("${cert_pb_txt}") String certPbTxtPath,
        @Autowired ConfigProtoService configProtoService,
        @Autowired DepartmentRepository departmentRepository,
        @Autowired BedConfigRepository bedConfigRepository
    ) {
        // 获取固定床位和临时床位的枚举值
        this.configProtoService = configProtoService;
        EnumValue fixedBedEnum = configProtoService.getConfig().getDevice().getEnums().getBedTypeList()
            .stream().filter(bedType -> bedType.getName().equals("固定授权")).findFirst()
            .orElse(null);
        if (fixedBedEnum == null) {
            log.error("Fixed bed type not found in config proto.");
            LogUtils.flushAndQuit(context);
        }
        this.FIXED_BED_TYPE_ID = fixedBedEnum.getId();
        EnumValue tempBedEnum = configProtoService.getConfig().getDevice().getEnums().getBedTypeList()
            .stream().filter(bedType -> bedType.getName().equals("临时授权")).findFirst()
            .orElse(null);
        if (tempBedEnum == null) {
            log.error("Temporary bed type not found in config proto.");
            LogUtils.flushAndQuit(context);
        }
        this.TEMP_BED_TYPE_ID = tempBedEnum.getId();

        this.hospitalLicensePb = null;
        if (!StrUtils.isBlank(certPbTxtPath) && !StrUtils.isBlank(publicKeyFilePath)) {
            try {
                // Load the public key from the file
                BufferedReader reader = new BufferedReader(new FileReader(publicKeyFilePath));
                StringBuilder sb = new StringBuilder();
                String line;
                while ((line = reader.readLine()) != null) {
                    if (!line.startsWith("-----")) sb.append(line);
                }
                String jingyiPubStr = sb.toString();
                reader.close();
                PublicKey publicKey = RsaUtils.getJingyiPublicKey(jingyiPubStr);
                log.info("load public key success");

                // Load the certificate
                File certFile = new File(certPbTxtPath);
                if (!certFile.exists() || !certFile.isFile()) {
                    log.error("Certificate file does not exist or is not a file: " + certPbTxtPath);
                    return;
                }
                reader = new BufferedReader(new FileReader(certFile));
                CertificatePB.Builder certPbBuilder = CertificatePB.newBuilder();
                TextFormat.getParser().merge(reader, certPbBuilder);
                CertificatePB certPb = certPbBuilder.build();
                reader.close();
                log.info("load certificate success");

                // Validate the certificate
                byte[] encryptedData = Base64.getDecoder().decode(certPb.getCertificateData());
                Cipher cipher = Cipher.getInstance("RSA");
                cipher.init(Cipher.DECRYPT_MODE, publicKey);
                byte[] decryptedData = cipher.doFinal(encryptedData);
                HospitalLicensePB decryptedCert = HospitalLicensePB.parseFrom(
                    Base64.getDecoder().decode(new String(decryptedData)));

                if (certPb.getHospitalLicense().equals(decryptedCert)) {
                    log.info("The certificate's hospital license matches the decrypted data.");
                } else {
                    log.error("The certificate's hospital license does not match the decrypted data.");
                    LogUtils.flushAndQuit(context);
                }
                this.hospitalLicensePb = decryptedCert;
            } catch (Exception e) {
                log.error(e.getMessage());
                log.error("Error reading the certificate");
                LogUtils.flushAndQuit(context);
            }
        }

        this.defaultDepartmentLicencePb = DepartmentLicencePB.newBuilder()
            .addMenuGroupId(1)
            .addMenuGroupId(15)
            .setMaxBedCount(3)
            .setMaxTempBedCount(0)
            .build();
        this.issueTime = TimeUtils.getLocalTime(1900, 1, 1);
        this.expireTime = TimeUtils.getLocalTime(9900, 1, 1);
        this.departmentLicencePbMap = new HashMap<>();
        if (this.hospitalLicensePb != null) {
            defaultDepartmentLicencePb = DepartmentLicencePB.newBuilder()
                .addAllMenuGroupId(this.hospitalLicensePb.getMenuGroupIdList())
                .setMaxBedCount(this.hospitalLicensePb.getMaxBedCount())
                .setMaxTempBedCount(0)
                .build();
            LocalDateTime t1 = TimeUtils.fromIso8601String(
                this.hospitalLicensePb.getIssueTimeIso8601(), "UTC");
            if (t1 != null) this.issueTime = t1;
            LocalDateTime t2 = TimeUtils.fromIso8601String(
                this.hospitalLicensePb.getExpirationTimeIso8601(), "UTC");
            if (t2 != null) this.expireTime = t2;

            for (DepartmentLicencePB deptCert : this.hospitalLicensePb.getDepartmentLicencesList()) {
                this.departmentLicencePbMap.put(deptCert.getDepartmentName(), deptCert);
            }
        }

        this.departmentRepository = departmentRepository;
        this.bedConfigRepository = bedConfigRepository;
    }

    public Set<Integer> getMenuGroupIdList(String deptId) {
        // 检查时间范围
        LocalDateTime nowUtc = TimeUtils.getNowUtc();
        if (nowUtc.isBefore(issueTime) || nowUtc.isAfter(expireTime)) {
            return defaultDepartmentLicencePb.getMenuGroupIdList()
                .stream().collect(Collectors.toSet());
        }

        // 获取科室名称
        Department dept = departmentRepository.findByDeptIdAndIsDeletedFalse(deptId).orElse(null);
        if (dept == null) {
            log.error("Department not found: " + deptId);
            return defaultDepartmentLicencePb.getMenuGroupIdList()
                .stream().collect(Collectors.toSet());
        }
        final String deptName = dept.getName();
        final String hospitalName = dept.getHospitalName();

        if (deptName.contains("晶医") || hospitalName.contains("晶医")) {
            return new HashSet<>();
        }

        // 获取科室证书
        DepartmentLicencePB deptCert = departmentLicencePbMap.get(deptName);
        if (hospitalLicensePb == null || !hospitalName.equals(hospitalLicensePb.getHospitalName()) || deptCert == null) {
            log.error("Department certificate not found for: " + deptName);
            return defaultDepartmentLicencePb.getMenuGroupIdList()
                .stream().collect(Collectors.toSet());
        }

        Set<Integer> menuGroupIdSet = new HashSet<>();
        for (Integer menuGroupId : defaultDepartmentLicencePb.getMenuGroupIdList()) {
            if (menuGroupId != null && menuGroupId > 0) {
                menuGroupIdSet.add(menuGroupId);
            }
        }
        for (Integer menuGroupId : deptCert.getMenuGroupIdList()) {
            if (menuGroupId != null && menuGroupId > 0) {
                menuGroupIdSet.add(menuGroupId);
            }
        }

        return menuGroupIdSet;
    }

    public Pair<Integer, Integer> getMaxBedCount(String deptId) {
        final Integer defaultMaxBedCount = defaultDepartmentLicencePb.getMaxBedCount();
        final Integer defaultMaxTempBedCount = defaultDepartmentLicencePb.getMaxTempBedCount();

        // 检查时间范围
        LocalDateTime nowUtc = TimeUtils.getNowUtc();
        if (nowUtc.isBefore(issueTime) || nowUtc.isAfter(expireTime)) {
            return new Pair<>(defaultMaxBedCount, defaultMaxTempBedCount);
        }

        // 获取科室名称
        Department dept = departmentRepository.findByDeptIdAndIsDeletedFalse(deptId).orElse(null);
        if (dept == null) {
            log.error("Department not found: " + deptId);
            return new Pair<>(defaultMaxBedCount, defaultMaxTempBedCount);
        }
        final String deptName = dept.getName();
        final String hospitalName = dept.getHospitalName();
        if (deptName.contains("晶医") || hospitalName.contains("晶医")) {
            return new Pair<>(999999, 999999);
        }

        // 获取科室证书
        DepartmentLicencePB deptCert = departmentLicencePbMap.get(deptName);
        if (hospitalLicensePb == null || !hospitalName.equals(hospitalLicensePb.getHospitalName()) || deptCert == null) {
            log.error("Department certificate not found for: " + deptName);
            return new Pair<>(defaultMaxBedCount, defaultMaxTempBedCount);
        }

        Integer maxBedCount = deptCert.getMaxBedCount() == 0 ? defaultMaxBedCount : deptCert.getMaxBedCount();
        Integer maxTempBedCount = deptCert.getMaxTempBedCount() == 0 ? defaultMaxTempBedCount : deptCert.getMaxTempBedCount();
        return new Pair<>(maxBedCount, maxTempBedCount);
    }

    public Boolean checkBedAvailable(String deptId, Integer bedCount) {
        if (isTest) return true;

        // 获取最大可用床位数
        Pair<Integer, Integer> maxBedCount = getMaxBedCount(deptId);
        if (maxBedCount == null) {
            log.error("Failed to get max bed count for department: " + deptId);
            return false;
        }

        // 获取当前床位数
        final Integer currentFixedBedCount = bedConfigRepository.countByDepartmentIdAndBedTypeAndIsDeletedFalse(
            deptId, FIXED_BED_TYPE_ID);

        if (maxBedCount.getFirst() < (currentFixedBedCount + bedCount)) {
            log.error("Fixed bed count exceeds the limit: " + deptId);
            return false;
        }

        return true;
    }

    private final Integer FIXED_BED_TYPE_ID;
    private final Integer TEMP_BED_TYPE_ID;

    private HospitalLicensePB hospitalLicensePb;

    private DepartmentLicencePB defaultDepartmentLicencePb;
    private LocalDateTime issueTime;
    private LocalDateTime expireTime;
    private Map<String, DepartmentLicencePB> departmentLicencePbMap;

    private ConfigProtoService configProtoService;
    private DepartmentRepository departmentRepository;
    private BedConfigRepository bedConfigRepository;

    // test only
    private Boolean isTest = false;
    public void setTest(Boolean test) {
        this.isTest = test;
    }
}