package com.jingyicare.jingyi_icis_engine.service.certs;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.util.Optional;

import org.junit.jupiter.api.Test;
import org.springframework.context.ConfigurableApplicationContext;

import com.jingyicare.jingyi_icis_engine.proto.IcisConfig.*;
import com.jingyicare.jingyi_icis_engine.proto.config.IcisDevice.*;
import com.jingyicare.jingyi_icis_engine.proto.shared.Shared.*;

import com.jingyicare.jingyi_icis_engine.repository.patients.BedConfigRepository;
import com.jingyicare.jingyi_icis_engine.repository.users.DepartmentRepository;
import com.jingyicare.jingyi_icis_engine.service.ConfigProtoService;

public class CertificateServiceTests {
    @Test
    public void testFixedAndTemporaryBedLimitsAreCheckedSeparately() {
        final Integer fixedBedType = 1;
        final Integer tempBedType = 2;
        final String deptId = "certificate-test-dept";

        ConfigProtoService configProtoService = mock(ConfigProtoService.class);
        Config config = Config.newBuilder()
            .setDevice(DeviceConfigPB.newBuilder()
                .setEnums(DeviceEnums.newBuilder()
                    .addBedType(EnumValue.newBuilder().setId(fixedBedType).setName("固定授权"))
                    .addBedType(EnumValue.newBuilder().setId(tempBedType).setName("临时授权"))))
            .build();
        when(configProtoService.getConfig()).thenReturn(config);

        DepartmentRepository departmentRepository = mock(DepartmentRepository.class);
        when(departmentRepository.findByDeptIdAndIsDeletedFalse(deptId)).thenReturn(Optional.empty());

        BedConfigRepository bedConfigRepository = mock(BedConfigRepository.class);
        when(bedConfigRepository.countByDepartmentIdAndBedTypeAndIsDeletedFalse(deptId, fixedBedType))
            .thenReturn(2);
        when(bedConfigRepository.countByDepartmentIdAndBedTypeAndIsDeletedFalse(deptId, tempBedType))
            .thenReturn(0);

        CertificateService service = new CertificateService(
            mock(ConfigurableApplicationContext.class), "", "", configProtoService,
            departmentRepository, bedConfigRepository);

        assertThat(service.checkBedAvailable(deptId)).isTrue();
        assertThat(service.checkBedAvailable(deptId, fixedBedType, 1)).isTrue();
        assertThat(service.checkBedAvailable(deptId, fixedBedType, 2)).isFalse();
        assertThat(service.checkBedAvailable(deptId, tempBedType, 0)).isTrue();
        assertThat(service.checkBedAvailable(deptId, tempBedType, 1)).isFalse();
        assertThat(service.checkBedAvailable(deptId, 999, 1)).isFalse();
    }
}
