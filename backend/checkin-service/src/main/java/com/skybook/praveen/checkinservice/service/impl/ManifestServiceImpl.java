package com.skybook.praveen.checkinservice.service.impl;

import com.skybook.praveen.checkinservice.domain.CheckInValidator;
import com.skybook.praveen.checkinservice.dto.response.CheckInResponse;
import com.skybook.praveen.checkinservice.dto.response.FlightManifestResponse;
import com.skybook.praveen.checkinservice.entity.Baggage;
import com.skybook.praveen.checkinservice.entity.CheckIn;
import com.skybook.praveen.checkinservice.entity.FlightManifest;
import com.skybook.praveen.checkinservice.enums.CheckInStatus;
import com.skybook.praveen.checkinservice.enums.ManifestStatus;
import com.skybook.praveen.checkinservice.mapper.CheckInMapper;
import com.skybook.praveen.checkinservice.mapper.FlightManifestMapper;
import com.skybook.praveen.checkinservice.repository.BaggageRepository;
import com.skybook.praveen.checkinservice.repository.CheckInRepository;
import com.skybook.praveen.checkinservice.repository.FlightManifestRepository;
import com.skybook.praveen.checkinservice.service.ManifestService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;

@Slf4j
@Service
@RequiredArgsConstructor
public class ManifestServiceImpl implements ManifestService {

    private static final Set<CheckInStatus> EVER_CHECKED_IN = EnumSet.of(
            CheckInStatus.CHECKED_IN, CheckInStatus.BOARDED, CheckInStatus.COMPLETED);
    private static final Set<CheckInStatus> BOARDED_OR_LATER = EnumSet.of(
            CheckInStatus.BOARDED, CheckInStatus.COMPLETED);

    private final CheckInRepository checkInRepository;
    private final BaggageRepository baggageRepository;
    private final FlightManifestRepository flightManifestRepository;
    private final CheckInValidator validator;

    @Override
    @Transactional(readOnly = true)
    public FlightManifestResponse getManifest(Long flightId) {

        FlightManifest manifest = flightManifestRepository.findByFlightId(flightId)
                .orElseGet(() -> FlightManifest.builder().flightId(flightId).status(ManifestStatus.OPEN).build());

        return buildResponse(manifest, flightId);
    }

    @Override
    @Transactional
    public FlightManifestResponse finalizeManifest(Long flightId, LocalDateTime now) {

        FlightManifest manifest = flightManifestRepository.findByFlightId(flightId).orElse(null);

        if (manifest != null && manifest.getStatus() == ManifestStatus.FINALIZED) {
            return buildResponse(manifest, flightId);
        }

        List<CheckIn> checkIns = checkInRepository.findByFlightId(flightId);

        // No CheckIns at all for this flight - nothing to validate a gate-close
        // cutoff against; finalize immediately (design doc section 5.7 - an
        // empty manifest is a legitimate, if unusual, outcome).
        LocalDateTime departureTime = checkIns.stream()
                .map(CheckIn::getDepartureTime)
                .filter(dt -> dt != null)
                .findFirst()
                .orElse(null);

        if (departureTime != null) {
            validator.validateManifestFinalizable(flightId, departureTime, now);
        }

        Counts counts = computeCounts(flightId, checkIns);

        FlightManifest toSave = manifest != null ? manifest
                : FlightManifest.builder().flightId(flightId).build();

        toSave.setStatus(ManifestStatus.FINALIZED);
        toSave.setFinalizedAt(now);
        toSave.setCheckedInCount(counts.checkedInCount());
        toSave.setBoardedCount(counts.boardedCount());
        toSave.setNoShowCount(counts.noShowCount());
        toSave.setBaggageCount(counts.baggageCount());
        toSave.setBaggageWeightKg(counts.baggageWeightKg());

        FlightManifest saved = flightManifestRepository.save(toSave);
        log.info("Finalized manifest for flight {}: {} checked in, {} boarded, {} no-show",
                flightId, counts.checkedInCount(), counts.boardedCount(), counts.noShowCount());

        return FlightManifestMapper.toResponse(saved, counts.checkedInCount(), counts.boardedCount(),
                counts.noShowCount(), counts.baggageCount(), counts.baggageWeightKg(), counts.passengers());
    }

    private FlightManifestResponse buildResponse(FlightManifest manifest, Long flightId) {
        List<CheckIn> checkIns = checkInRepository.findByFlightId(flightId);
        Counts counts = computeCounts(flightId, checkIns);
        return FlightManifestMapper.toResponse(manifest, counts.checkedInCount(), counts.boardedCount(),
                counts.noShowCount(), counts.baggageCount(), counts.baggageWeightKg(), counts.passengers());
    }

    private Counts computeCounts(Long flightId, List<CheckIn> checkIns) {

        List<CheckIn> excludingCancelled = checkIns.stream()
                .filter(c -> c.getStatus() != CheckInStatus.CANCELLED)
                .toList();

        int checkedInCount = (int) excludingCancelled.stream()
                .filter(c -> EVER_CHECKED_IN.contains(c.getStatus())).count();
        int boardedCount = (int) excludingCancelled.stream()
                .filter(c -> BOARDED_OR_LATER.contains(c.getStatus())).count();
        int noShowCount = (int) excludingCancelled.stream()
                .filter(c -> c.getStatus() == CheckInStatus.NO_SHOW).count();

        List<Baggage> baggage = baggageRepository.findByCheckInFlightId(flightId);
        int baggageCount = baggage.size();
        BigDecimal baggageWeightKg = baggage.stream()
                .map(Baggage::getWeightKg)
                .reduce(BigDecimal.ZERO, BigDecimal::add);

        List<CheckInResponse> passengers = excludingCancelled.stream().map(CheckInMapper::toResponse).toList();

        return new Counts(checkedInCount, boardedCount, noShowCount, baggageCount, baggageWeightKg, passengers);
    }

    private record Counts(int checkedInCount, int boardedCount, int noShowCount,
                           int baggageCount, BigDecimal baggageWeightKg, List<CheckInResponse> passengers) {
    }
}
