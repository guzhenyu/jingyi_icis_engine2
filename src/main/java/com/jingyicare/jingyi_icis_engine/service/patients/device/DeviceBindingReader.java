package com.jingyicare.jingyi_icis_engine.service.patients.device;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

import org.springframework.stereotype.Service;

import com.jingyicare.jingyi_icis_engine.entity.patients.BedConfig;
import com.jingyicare.jingyi_icis_engine.entity.patients.DeviceInfo;
import com.jingyicare.jingyi_icis_engine.entity.patients.PatientDevice;
import com.jingyicare.jingyi_icis_engine.entity.patients.PatientRecord;
import com.jingyicare.jingyi_icis_engine.proto.IcisWebApi.DeviceWithBindingsPB;
import com.jingyicare.jingyi_icis_engine.proto.IcisWebApi.StatusCode;
import com.jingyicare.jingyi_icis_engine.proto.config.IcisDevice.DeviceBindingRecordPB;
import com.jingyicare.jingyi_icis_engine.repository.patients.PatientDeviceRepository;
import com.jingyicare.jingyi_icis_engine.service.ProtoConfig;
import com.jingyicare.jingyi_icis_engine.service.patients.PatientReader;
import com.jingyicare.jingyi_icis_engine.utils.Pair;
import com.jingyicare.jingyi_icis_engine.utils.TimeUtils;

@Service
public class DeviceBindingReader {
    public DeviceBindingReader(
        ProtoConfig protoConfig, PatientDeviceRepository bindingRepo,
        PatientReader patientReader, DeviceService deviceService
    ) {
        this.zoneId = protoConfig.getConfig().getZoneId();
        this.bindingRepo = bindingRepo;
        this.patientReader = patientReader;
        this.deviceService = deviceService;
    }

    Pair<StatusCode, Map<Integer, DeviceBindingRecordPB>> findCurrentBindings(
        Collection<Integer> deviceIds
    ) {
        if (deviceIds.isEmpty()) return new Pair<>(StatusCode.OK, Map.of());
        List<PatientDevice> bindings = bindingRepo
            .findByDeviceIdInAndUnbindingTimeIsNullAndIsDeletedFalse(deviceIds);
        Pair<StatusCode, List<DeviceBindingRecordPB>> result = toBindingRecords(bindings);
        if (result.getFirst() != StatusCode.OK) {
            return new Pair<>(result.getFirst(), Map.of());
        }
        return new Pair<>(StatusCode.OK, result.getSecond().stream().collect(
            Collectors.toMap(DeviceBindingRecordPB::getDeviceId, Function.identity())));
    }

    Pair<StatusCode, BindingHistory> findBindingHistory(
        int deviceId, LocalDateTime fromUtc, LocalDateTime toUtc
    ) {
        if (fromUtc == null) fromUtc = TimeUtils.getLocalTime(1900, 1, 1);
        if (toUtc == null) toUtc = TimeUtils.getLocalTime(9999, 1, 1);
        // 历史查询保留已软删除的绑定记录。
        List<PatientDevice> bindings = new ArrayList<>(bindingRepo
            .findByDeviceIdAndUnbindingTimeIsNotNullAndUnbindingTimeAfterAndBindingTimeBefore(
                deviceId, fromUtc, toUtc));
        List<PatientDevice> currentBindings = bindingRepo
            .findByDeviceIdAndUnbindingTimeIsNullAndBindingTimeBefore(deviceId, toUtc);
        bindings.addAll(currentBindings);
        bindings.sort(Comparator.comparing(PatientDevice::getBindingTime));

        Pair<StatusCode, List<DeviceBindingRecordPB>> result = toBindingRecords(bindings);
        if (result.getFirst() != StatusCode.OK) return new Pair<>(result.getFirst(), null);
        return new Pair<>(StatusCode.OK,
            new BindingHistory(result.getSecond(), !currentBindings.isEmpty()));
    }

    public List<DeviceWithBindingsPB> findBindingHistory(
        PatientRecord patient, Integer deviceType, LocalDateTime fromUtc, LocalDateTime toUtc
    ) {
        Long patientId = patient.getId();
        if (fromUtc == null) fromUtc = TimeUtils.getMin();
        if (toUtc == null) toUtc = TimeUtils.getMax();

        List<PatientDevice> bindings = new ArrayList<>(bindingRepo
            .findByPatientIdAndUnbindingTimeIsNotNullAndUnbindingTimeAfterAndBindingTimeBefore(
                patientId, fromUtc, toUtc));
        List<PatientDevice> currentBindings = bindingRepo
            .findByPatientIdAndUnbindingTimeIsNullAndBindingTimeBefore(patientId, toUtc);
        Set<Integer> inUseDeviceIds = currentBindings.stream()
            .map(PatientDevice::getDeviceId).collect(Collectors.toSet());
        bindings.addAll(currentBindings);

        // 入科时间明确时才合成固定设备历史，真实绑定记录始终保留。
        Set<Integer> remainingDeviceIds = bindings.stream()
            .map(PatientDevice::getDeviceId).collect(Collectors.toSet());
        List<DeviceInfo> devices = new ArrayList<>();
        if (patient.getAdmissionTime() != null) devices.addAll(findFixedDevices(patient));
        for (DeviceInfo device : devices) {
            int id = device.getId();
            remainingDeviceIds.remove(id);
            inUseDeviceIds.add(id);
            bindings.add(PatientDevice.builder().id(0L).patientId(patientId)
                .deviceId(id).bindingTime(patient.getAdmissionTime()).build());
        }
        devices.addAll(deviceService.findDevicesByIds(remainingDeviceIds));

        Map<Integer, List<DeviceBindingRecordPB>> bindingsByDevice = bindings.stream()
            .sorted(Comparator.comparing(PatientDevice::getBindingTime))
            .map(binding -> DeviceMapper.toDeviceBindingRecordPB(
                binding, patient.getIcuName(), "", zoneId))
            .collect(Collectors.groupingBy(DeviceBindingRecordPB::getDeviceId));
        return devices.stream()
            .filter(device -> matchesDeviceType(device, deviceType))
            .sorted(DEVICE_ORDER)
            .map(device -> DeviceMapper.toDeviceWithBindingsPB(
                device, inUseDeviceIds.contains(device.getId()),
                bindingsByDevice.get(device.getId())))
            .toList();
    }

    boolean hasCurrentDeviceOfType(long patientId, Integer deviceType) {
        if (bindingRepo.existsCurrentDeviceOfType(patientId, deviceType, TimeUtils.getMax())) {
            return true;
        }
        PatientRecord patient = patientReader.findById(patientId).orElse(null);
        return findFixedDevices(patient).stream()
            .anyMatch(device -> matchesDeviceType(device, deviceType));
    }

    LocalDateTime findLatestUnbindingTime(long patientId, Integer deviceType) {
        return bindingRepo.findLatestUnbindingTime(patientId, deviceType);
    }

    List<PatientDevice> findActiveBindingsByDeviceId(int deviceId) {
        return bindingRepo.findByDeviceIdAndUnbindingTimeIsNullAndIsDeletedFalse(deviceId);
    }

    Optional<PatientDevice> findActiveBinding(long patientId, int deviceId) {
        return bindingRepo.findByPatientIdAndDeviceIdAndUnbindingTimeIsNullAndIsDeletedFalse(
            patientId, deviceId);
    }

    List<PatientDevice> findActiveBindingsByPatientId(long patientId) {
        return bindingRepo.findByPatientIdAndUnbindingTimeNullAndIsDeletedFalse(patientId);
    }

    record BindingHistory(List<DeviceBindingRecordPB> records, boolean isBinding) {}

    private Pair<StatusCode, List<DeviceBindingRecordPB>> toBindingRecords(
        List<PatientDevice> bindings
    ) {
        if (bindings.isEmpty()) return new Pair<>(StatusCode.OK, List.of());
        List<Long> patientIds = bindings.stream()
            .map(PatientDevice::getPatientId).distinct().toList();
        Map<Long, PatientRecord> patients = patientReader.findByIds(patientIds);
        Pair<StatusCode, Map<Long, String>> bedNumbers =
            patientReader.findDisplayBedNumbers(patients.values());
        if (bedNumbers.getFirst() != StatusCode.OK) {
            return new Pair<>(bedNumbers.getFirst(), List.of());
        }
        List<DeviceBindingRecordPB> records = bindings.stream().map(binding -> {
            PatientRecord patient = patients.get(binding.getPatientId());
            return DeviceMapper.toDeviceBindingRecordPB(binding,
                patient == null ? "" : patient.getIcuName(),
                bedNumbers.getSecond().getOrDefault(binding.getPatientId(), ""), zoneId);
        }).toList();
        return new Pair<>(StatusCode.OK, records);
    }

    private List<DeviceInfo> findFixedDevices(PatientRecord patient) {
        if (patient == null) return List.of();
        BedConfig bed = patientReader.findBed(patient).orElse(null);
        if (bed == null) return List.of();
        return deviceService.findDevicesAtBed(patient.getDeptId(), bed.getDeviceBedNumber());
    }

    private static boolean matchesDeviceType(DeviceInfo device, Integer type) {
        return type == null || type <= 0 || type.equals(device.getDeviceType());
    }

    private static final Comparator<DeviceInfo> DEVICE_ORDER = Comparator
        .comparing(DeviceInfo::getDeviceType).thenComparing(DeviceInfo::getDeviceName);

    private final String zoneId;
    private final PatientDeviceRepository bindingRepo;
    private final PatientReader patientReader;
    private final DeviceService deviceService;
}
