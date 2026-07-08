package com.skybook.praveen.checkinservice.service.impl;

import com.skybook.praveen.checkinservice.domain.BaggageAllowanceCalculator;
import com.skybook.praveen.checkinservice.domain.BaggageTagGenerator;
import com.skybook.praveen.checkinservice.domain.CheckInValidator;
import com.skybook.praveen.checkinservice.dto.request.CreateBaggageRequest;
import com.skybook.praveen.checkinservice.dto.response.BaggageResponse;
import com.skybook.praveen.checkinservice.entity.Baggage;
import com.skybook.praveen.checkinservice.entity.CheckIn;
import com.skybook.praveen.checkinservice.entity.CheckInHistory;
import com.skybook.praveen.checkinservice.enums.CheckInHistoryType;
import com.skybook.praveen.checkinservice.mapper.BaggageMapper;
import com.skybook.praveen.checkinservice.repository.BaggageRepository;
import com.skybook.praveen.checkinservice.repository.CheckInHistoryRepository;
import com.skybook.praveen.checkinservice.service.BaggageService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

@Slf4j
@Service
@RequiredArgsConstructor
public class BaggageServiceImpl implements BaggageService {

    private static final int MAX_REFERENCE_ATTEMPTS = 10;

    private final BaggageRepository baggageRepository;
    private final CheckInHistoryRepository checkInHistoryRepository;

    // Injects the impl (not the interface) to reuse the aggregate lookup -
    // same pattern as BoardingPassServiceImpl.
    private final CheckInServiceImpl checkInService;

    private final CheckInValidator validator;
    private final BaggageAllowanceCalculator allowanceCalculator;
    private final BaggageTagGenerator tagGenerator;

    @Override
    @Transactional
    public BaggageResponse addBaggage(CreateBaggageRequest request) {

        CheckIn checkIn = checkInService.findCheckInOrThrow(request.checkInId());
        validator.validateBaggageAllowed(checkIn);

        var computation = allowanceCalculator.compute(request.weightKg(), checkIn.getTravelClass(), checkIn.getFareType());

        Baggage baggage = Baggage.builder()
                .checkIn(checkIn)
                .tagNumber(uniqueTagNumber())
                .weightKg(request.weightKg())
                .excess(computation.excess())
                .excessCharge(computation.excessCharge())
                .build();

        Baggage saved = baggageRepository.save(baggage);

        checkInHistoryRepository.save(CheckInHistory.builder()
                .checkIn(checkIn)
                .historyType(CheckInHistoryType.BAGGAGE_ADDED)
                .actor("USER")
                .source("API")
                .details("Baggage " + saved.getTagNumber() + " added (" + request.weightKg() + "kg"
                        + (computation.excess() ? ", excess" : "") + ")")
                .build());

        log.info("Added baggage {} ({}kg{}) for check-in {}",
                saved.getTagNumber(), request.weightKg(), computation.excess() ? ", excess" : "", request.checkInId());

        return BaggageMapper.toResponse(saved);
    }

    @Override
    @Transactional(readOnly = true)
    public List<BaggageResponse> getByCheckInId(Long checkInId) {
        return baggageRepository.findByCheckInId(checkInId).stream().map(BaggageMapper::toResponse).toList();
    }

    private String uniqueTagNumber() {
        for (int attempt = 0; attempt < MAX_REFERENCE_ATTEMPTS; attempt++) {
            String candidate = tagGenerator.generate();
            if (!baggageRepository.existsByTagNumber(candidate)) {
                return candidate;
            }
        }
        throw new IllegalStateException("Could not generate a unique baggage tag number");
    }
}
