package com.skybook.praveen.checkinservice.jpa;

import com.skybook.praveen.checkinservice.entity.Baggage;
import com.skybook.praveen.checkinservice.entity.BoardingPass;
import com.skybook.praveen.checkinservice.entity.CheckIn;
import com.skybook.praveen.checkinservice.enums.BoardingPassStatus;
import com.skybook.praveen.checkinservice.enums.CheckInStatus;
import com.skybook.praveen.checkinservice.repository.BaggageRepository;
import com.skybook.praveen.checkinservice.repository.BoardingPassRepository;
import com.skybook.praveen.checkinservice.repository.CheckInRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataIntegrityViolationException;

import java.math.BigDecimal;
import java.time.LocalDateTime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** BoardingPass/Baggage invariants - design doc section 3.1.1/3.2/3.3. */
class CheckInChildEntitiesJpaTest extends AbstractPostgresJpaTest {

    @Autowired
    private CheckInRepository checkInRepository;
    @Autowired
    private BoardingPassRepository boardingPassRepository;
    @Autowired
    private BaggageRepository baggageRepository;

    private long passengerSeq = 2000;

    @BeforeEach
    void cleanUp() {
        baggageRepository.deleteAll();
        boardingPassRepository.deleteAll();
        checkInRepository.deleteAll();
    }

    private CheckIn savedCheckIn() {
        return checkInRepository.saveAndFlush(CheckIn.builder()
                .status(CheckInStatus.CHECKED_IN)
                .bookingId(42L).bookingReference("SBTEST").bookingPassengerId(++passengerSeq)
                .flightId(7L).passengerName("Test Passenger").seatNumber("12B")
                .build());
    }

    private BoardingPass.BoardingPassBuilder boardingPass(CheckIn checkIn, String number) {
        return BoardingPass.builder()
                .status(BoardingPassStatus.ACTIVE)
                .issuedAt(LocalDateTime.now())
                .checkIn(checkIn)
                .boardingPassNumber(number)
                .token(number + "-token")
                .passengerName("Test Passenger")
                .bookingReference("SBTEST")
                .seatNumber("12B");
    }

    @Test
    void boardingPassNumberIsUnique() {
        CheckIn checkIn = savedCheckIn();
        boardingPassRepository.saveAndFlush(boardingPass(checkIn, "BP-2026-UNIQAA").token("token-a").build());

        assertThatThrownBy(() -> boardingPassRepository.saveAndFlush(
                boardingPass(savedCheckIn(), "BP-2026-UNIQAA").token("token-b").build()))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void boardingPassTokenIsUnique() {
        CheckIn checkIn = savedCheckIn();
        boardingPassRepository.saveAndFlush(boardingPass(checkIn, "BP-2026-TOKAAA").token("same-token").build());

        assertThatThrownBy(() -> boardingPassRepository.saveAndFlush(
                boardingPass(savedCheckIn(), "BP-2026-TOKAAB").token("same-token").build()))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void aCheckInCanAccumulateOneRevokedAndOneActiveBoardingPass() {
        // Design doc section 3.2/18 - @ManyToOne, not @OneToOne: a seat
        // change revokes the old pass and inserts a new one rather than
        // mutating it in place.
        CheckIn checkIn = savedCheckIn();

        BoardingPass revoked = boardingPass(checkIn, "BP-2026-REVAAA").token("token-rev")
                .status(BoardingPassStatus.REVOKED).revokedAt(LocalDateTime.now()).build();
        boardingPassRepository.saveAndFlush(revoked);

        BoardingPass active = boardingPass(checkIn, "BP-2026-ACTAAA").token("token-act").build();
        boardingPassRepository.saveAndFlush(active);

        assertThat(boardingPassRepository.findByCheckInIdOrderByIssuedAtAsc(checkIn.getId())).hasSize(2);
        assertThat(boardingPassRepository.findByCheckInIdAndStatus(checkIn.getId(), BoardingPassStatus.ACTIVE))
                .get().extracting(BoardingPass::getBoardingPassNumber).isEqualTo("BP-2026-ACTAAA");
    }

    @Test
    void baggageTagNumberIsUnique() {
        CheckIn checkIn = savedCheckIn();
        baggageRepository.saveAndFlush(Baggage.builder()
                .checkIn(checkIn).tagNumber("BAG-2026-UNIQAA").weightKg(new BigDecimal("18")).excess(false).build());

        assertThatThrownBy(() -> baggageRepository.saveAndFlush(Baggage.builder()
                .checkIn(checkIn).tagNumber("BAG-2026-UNIQAA").weightKg(new BigDecimal("10")).excess(false).build()))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void baggageFindsByCheckInIdAndFlightId() {
        CheckIn checkIn = savedCheckIn();
        baggageRepository.saveAndFlush(Baggage.builder()
                .checkIn(checkIn).tagNumber("BAG-2026-FNDAAA").weightKg(new BigDecimal("18")).excess(false).build());

        assertThat(baggageRepository.findByCheckInId(checkIn.getId())).hasSize(1);
        assertThat(baggageRepository.findByCheckInFlightId(7L)).hasSize(1);
        assertThat(baggageRepository.existsByTagNumber("BAG-2026-FNDAAA")).isTrue();
    }
}
