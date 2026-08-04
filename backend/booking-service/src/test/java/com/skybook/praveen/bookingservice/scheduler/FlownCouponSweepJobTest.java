package com.skybook.praveen.bookingservice.scheduler;

import com.skybook.praveen.bookingservice.client.FlightBookingStatus;
import com.skybook.praveen.bookingservice.client.FlightDetails;
import com.skybook.praveen.bookingservice.client.FlightServiceClient;
import com.skybook.praveen.bookingservice.entity.BookingPassenger;
import com.skybook.praveen.bookingservice.entity.TicketCoupon;
import com.skybook.praveen.bookingservice.enums.CouponStatus;
import com.skybook.praveen.bookingservice.exception.FlightServiceUnavailableException;
import com.skybook.praveen.bookingservice.repository.TicketCouponRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * "Has it flown" is judged by the DEPARTURE airport's wall clock, not the
 * server's - the pair of Sydney/London tests below is the whole point of the
 * job: the same stored departure time is history in one place and still
 * upcoming in the other.
 */
@ExtendWith(MockitoExtension.class)
class FlownCouponSweepJobTest {

    @Mock
    private TicketCouponRepository ticketCouponRepository;
    @Mock
    private FlightServiceClient flightServiceClient;

    @InjectMocks
    private FlownCouponSweepJob job;

    private static TicketCoupon coupon(Long id, Long flightId) {
        return TicketCoupon.builder()
                .id(id)
                .couponNumber(1)
                .status(CouponStatus.CHECKED_IN)
                .bookingPassenger(BookingPassenger.builder().id(id).flightId(flightId).build())
                .build();
    }

    private static FlightDetails flight(Long id, String origin, LocalDateTime departure) {
        return new FlightDetails(id, "SB101", origin, "DXB",
                departure, departure == null ? null : departure.plusHours(6),
                "T1", "T3", FlightBookingStatus.DEPARTED);
    }

    @Nested
    @DisplayName("the airport-local departure test")
    class AirportLocalClock {

        @Test
        @DisplayName("a departure three hours ahead of UTC has already gone in Sydney")
        void aDepartureThreeHoursAheadOfUtcHasGoneInSydney() {
            // Sydney runs UTC+10/+11, so its wall clock is already past this.
            LocalDateTime departure = LocalDateTime.now(ZoneOffset.UTC).plusHours(3);
            TicketCoupon flown = coupon(1L, 9L);
            when(ticketCouponRepository.findByStatus(CouponStatus.CHECKED_IN)).thenReturn(List.of(flown));
            when(flightServiceClient.getFlightAsService(9L)).thenReturn(flight(9L, "SYD", departure));

            job.sweepFlownCoupons();

            assertThat(flown.getStatus()).isEqualTo(CouponStatus.FLOWN);
            verify(ticketCouponRepository).saveAll(List.of(flown));
        }

        @Test
        @DisplayName("the same departure is still upcoming in London, so the coupon does not move")
        void theSameDepartureIsStillUpcomingInLondon() {
            LocalDateTime departure = LocalDateTime.now(ZoneOffset.UTC).plusHours(3);
            TicketCoupon pending = coupon(1L, 9L);
            when(ticketCouponRepository.findByStatus(CouponStatus.CHECKED_IN)).thenReturn(List.of(pending));
            when(flightServiceClient.getFlightAsService(9L)).thenReturn(flight(9L, "LHR", departure));

            job.sweepFlownCoupons();

            assertThat(pending.getStatus()).isEqualTo(CouponStatus.CHECKED_IN);
            verify(ticketCouponRepository, never()).saveAll(any());
        }
    }

    @Nested
    @DisplayName("batch behaviour")
    class Batch {

        @Test
        @DisplayName("no checked-in coupons means no flight lookups at all")
        void noCandidatesMeansNoLookups() {
            when(ticketCouponRepository.findByStatus(CouponStatus.CHECKED_IN)).thenReturn(List.of());

            job.sweepFlownCoupons();

            verifyNoInteractions(flightServiceClient);
            verify(ticketCouponRepository, never()).saveAll(any());
        }

        @Test
        @DisplayName("coupons sharing a flight cost exactly one lookup")
        void couponsSharingAFlightCostOneLookup() {
            LocalDateTime departed = LocalDateTime.now().minusDays(2);
            List<TicketCoupon> sameFlight = List.of(coupon(1L, 9L), coupon(2L, 9L), coupon(3L, 9L));
            when(ticketCouponRepository.findByStatus(CouponStatus.CHECKED_IN)).thenReturn(sameFlight);
            when(flightServiceClient.getFlightAsService(9L)).thenReturn(flight(9L, "LHR", departed));

            job.sweepFlownCoupons();

            verify(flightServiceClient, times(1)).getFlightAsService(9L);
            assertThat(sameFlight).allSatisfy(c -> assertThat(c.getStatus()).isEqualTo(CouponStatus.FLOWN));
        }

        @Test
        @DisplayName("a departed leg flips while an upcoming one on the same batch stays put")
        void onlyTheDepartedLegFlips() {
            TicketCoupon outbound = coupon(1L, 9L);
            TicketCoupon inbound = coupon(2L, 10L);
            when(ticketCouponRepository.findByStatus(CouponStatus.CHECKED_IN))
                    .thenReturn(List.of(outbound, inbound));
            when(flightServiceClient.getFlightAsService(9L))
                    .thenReturn(flight(9L, "LHR", LocalDateTime.now().minusDays(2)));
            when(flightServiceClient.getFlightAsService(10L))
                    .thenReturn(flight(10L, "DXB", LocalDateTime.now().plusDays(5)));

            job.sweepFlownCoupons();

            assertThat(outbound.getStatus()).isEqualTo(CouponStatus.FLOWN);
            assertThat(inbound.getStatus()).isEqualTo(CouponStatus.CHECKED_IN);
            // The whole candidate list is saved once, not row by row.
            verify(ticketCouponRepository, times(1)).saveAll(List.of(outbound, inbound));
        }
    }

    @Nested
    @DisplayName("degraded flight-service")
    class Degraded {

        @Test
        @DisplayName("a failed lookup skips that coupon instead of failing the batch")
        void aFailedLookupSkipsThatCouponOnly() {
            TicketCoupon unresolvable = coupon(1L, 9L);
            TicketCoupon flown = coupon(2L, 10L);
            when(ticketCouponRepository.findByStatus(CouponStatus.CHECKED_IN))
                    .thenReturn(List.of(unresolvable, flown));
            when(flightServiceClient.getFlightAsService(9L))
                    .thenThrow(new FlightServiceUnavailableException(9L, new IllegalStateException("down")));
            when(flightServiceClient.getFlightAsService(10L))
                    .thenReturn(flight(10L, "LHR", LocalDateTime.now().minusDays(2)));

            assertThatCode(job::sweepFlownCoupons).doesNotThrowAnyException();

            assertThat(unresolvable.getStatus()).isEqualTo(CouponStatus.CHECKED_IN);
            assertThat(flown.getStatus()).isEqualTo(CouponStatus.FLOWN);
        }

        @Test
        @DisplayName("an unfetchable flight is re-attempted per coupon - computeIfAbsent never caches the null")
        void anUnfetchableFlightIsReattemptedPerCoupon() {
            when(ticketCouponRepository.findByStatus(CouponStatus.CHECKED_IN))
                    .thenReturn(List.of(coupon(1L, 9L), coupon(2L, 9L)));
            when(flightServiceClient.getFlightAsService(9L))
                    .thenThrow(new FlightServiceUnavailableException(9L, new IllegalStateException("down")));

            job.sweepFlownCoupons();

            verify(flightServiceClient, times(2)).getFlightAsService(anyLong());
            verify(ticketCouponRepository, never()).saveAll(any());
        }

        @Test
        @DisplayName("a flight with no departure time on file is left alone")
        void aFlightWithoutADepartureTimeIsLeftAlone() {
            TicketCoupon untouched = coupon(1L, 9L);
            when(ticketCouponRepository.findByStatus(CouponStatus.CHECKED_IN)).thenReturn(List.of(untouched));
            when(flightServiceClient.getFlightAsService(9L)).thenReturn(flight(9L, "LHR", null));

            job.sweepFlownCoupons();

            assertThat(untouched.getStatus()).isEqualTo(CouponStatus.CHECKED_IN);
            verify(ticketCouponRepository, never()).saveAll(any());
        }
    }
}
