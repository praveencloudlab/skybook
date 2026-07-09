package com.skybook.praveen.checkinservice.service.impl;

import com.skybook.praveen.checkinservice.domain.BaggageAllowanceCalculator;
import com.skybook.praveen.checkinservice.domain.BaggageTagGenerator;
import com.skybook.praveen.checkinservice.domain.CheckInStateMachine;
import com.skybook.praveen.checkinservice.domain.CheckInValidator;
import com.skybook.praveen.checkinservice.dto.request.CreateBaggageRequest;
import com.skybook.praveen.checkinservice.dto.response.BaggageResponse;
import com.skybook.praveen.checkinservice.entity.Baggage;
import com.skybook.praveen.checkinservice.entity.CheckIn;
import com.skybook.praveen.checkinservice.enums.CheckInStatus;
import com.skybook.praveen.checkinservice.repository.BaggageRepository;
import com.skybook.praveen.checkinservice.repository.CheckInHistoryRepository;
import com.skybook.praveen.checkinservice.repository.CheckInRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class BaggageServiceImplTest {

    @Mock
    private BaggageRepository baggageRepository;
    @Mock
    private CheckInHistoryRepository checkInHistoryRepository;
    @Mock
    private CheckInRepository checkInRepository;

    private BaggageServiceImpl baggageService;

    @BeforeEach
    void setUp() {
        CheckInServiceImpl checkInService = new CheckInServiceImpl(
                checkInRepository, new CheckInStateMachine(), new CheckInValidator(24, 45, 45, 20));

        baggageService = new BaggageServiceImpl(
                baggageRepository, checkInHistoryRepository, checkInService,
                new CheckInValidator(24, 45, 45, 20),
                new BaggageAllowanceCalculator(
                        new BigDecimal("15"), new BigDecimal("20"), new BigDecimal("25"), new BigDecimal("32"),
                        new BigDecimal("10")),
                new BaggageTagGenerator());

        lenient().when(baggageRepository.save(any(Baggage.class))).thenAnswer(inv -> inv.getArgument(0));
        lenient().when(baggageRepository.existsByTagNumber(any())).thenReturn(false);
    }

    private CheckIn checkInWith(CheckInStatus status) {
        return CheckIn.builder()
                .id(1L).bookingId(42L).bookingReference("SBTEST").bookingPassengerId(100L)
                .flightId(7L).passengerName("Test Passenger").travelClass("ECONOMY").fareType("SAVER")
                .status(status)
                .build();
    }

    @Test
    void addBaggageRejectedWhenNotCheckedIn() {
        when(checkInRepository.findById(1L)).thenReturn(Optional.of(checkInWith(CheckInStatus.OPEN)));

        assertThatThrownBy(() -> baggageService.addBaggage(new CreateBaggageRequest(1L, new BigDecimal("10"))))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void addBaggageWithinAllowanceIsNotExcess() {
        when(checkInRepository.findById(1L)).thenReturn(Optional.of(checkInWith(CheckInStatus.CHECKED_IN)));

        BaggageResponse response = baggageService.addBaggage(new CreateBaggageRequest(1L, new BigDecimal("12")));

        assertThat(response.excess()).isFalse();
        assertThat(response.excessCharge()).isNull();
        assertThat(response.tagNumber()).matches("BAG-\\d{4}-[A-HJ-NP-Z2-9]{6}");
        verify(checkInHistoryRepository).save(any());
    }

    @Test
    void addBaggageOverAllowanceIsExcessAndCharged() {
        // ECONOMY/SAVER allowance is 15kg.
        when(checkInRepository.findById(1L)).thenReturn(Optional.of(checkInWith(CheckInStatus.CHECKED_IN)));

        BaggageResponse response = baggageService.addBaggage(new CreateBaggageRequest(1L, new BigDecimal("20")));

        assertThat(response.excess()).isTrue();
        assertThat(response.excessCharge()).isEqualByComparingTo("50"); // 5kg over * 10/kg
    }

    @Test
    void getByCheckInIdReturnsMappedBaggage() {
        Baggage baggage = Baggage.builder()
                .id(5L).checkIn(checkInWith(CheckInStatus.CHECKED_IN))
                .tagNumber("BAG-2026-ABCDEF").weightKg(new BigDecimal("18")).excess(false)
                .build();
        when(baggageRepository.findByCheckInId(1L)).thenReturn(List.of(baggage));

        List<BaggageResponse> responses = baggageService.getByCheckInId(1L);

        assertThat(responses).hasSize(1);
        assertThat(responses.getFirst().tagNumber()).isEqualTo("BAG-2026-ABCDEF");
    }
}
