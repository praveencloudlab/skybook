package com.skybook.praveen.notificationservice.service;

import com.skybook.praveen.common.event.CheckInEvent;
import com.skybook.praveen.common.event.CheckInEventType;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Locale;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The check-in email and the attached boarding pass are generated from the
 * same event, so the values a passenger reads in the mail body must be the
 * ones the PDF prints - above all the boarding clock, which is DERIVED rather
 * than taken from the event (checkin-service stamps boardingTime with the
 * departure clock). These assert the rendered HTML, including the branding
 * that is looked up from the flight number alone.
 */
class CheckInEmailTemplateTest {

    private static final DateTimeFormatter PASS_DATE =
            DateTimeFormatter.ofPattern("d MMM yyyy", Locale.ENGLISH);

    /** Anchored to now so the fixture never expires; the clock is pinned so the derived times are stable. */
    private static final LocalDateTime DEPARTURE =
            LocalDateTime.now().plusDays(30).withHour(9).withMinute(15).withSecond(0).withNano(0);

    private final CheckInEmailTemplate template = new CheckInEmailTemplate();

    private static CheckInEvent.CheckInEventBuilder boardingPass() {
        return CheckInEvent.builder()
                .type(CheckInEventType.BOARDING_PASS_GENERATED)
                .bookingReference("SBCW53")
                .passengerName("Divya Gopu")
                .contactEmail("divya@example.com")
                .flightNumber("BA178")
                .originAirportCode("LHR")
                .destinationAirportCode("JFK")
                .departureTime(DEPARTURE)
                .travelClass("BUSINESS")
                .seatNumber("5B")
                .boardingGroup("1")
                .gate("A12")
                .boardingPassNumber("BP-2026-UWFQ7D")
                .issuedAt(DEPARTURE.minusDays(1));
    }

    @Nested
    @DisplayName("Boarding details")
    class BoardingDetails {

        @Test
        void carriesThePnrPassNumberRouteAndSeatChips() {
            String html = template.render(boardingPass().build());

            assertThat(html).contains("Hello Divya Gopu,");
            assertThat(html).contains("You're checked in. Here's your boarding pass for BA178.");
            assertThat(html).contains(">SBCW53</div>");
            assertThat(html).contains(">BP-2026-UWFQ7D</div>");
            assertThat(html).contains(">LHR</div>");
            assertThat(html).contains(">London</div>");
            assertThat(html).contains(">JFK</div>");
            assertThat(html).contains(">New York</div>");
            assertThat(html).contains("FLIGHT BA178");
            assertThat(html).contains(">5B</div>");
            assertThat(html).contains(">A12</div>");
            assertThat(html).contains(">1</div>");
            assertThat(html).contains("Reference SBCW53");
        }

        @Test
        void theBoardingChipReadsFortyMinutesBeforeDepartureWhenTheServerEchoesTheDepartureClock() {
            // checkin-service stamps boardingTime with the departure clock, so
            // the email must derive departure - 40 exactly like the PDF pass.
            String html = template.render(boardingPass().boardingTime(DEPARTURE).build());

            assertThat(html).contains(PASS_DATE.format(DEPARTURE) + ", 08:35");
        }

        @Test
        void aGenuinelyEarlierServerBoardingTimeWins() {
            String html = template.render(boardingPass()
                    .boardingTime(DEPARTURE.withHour(8).withMinute(50))
                    .build());

            assertThat(html).contains(PASS_DATE.format(DEPARTURE) + ", 08:50");
        }

        @Test
        void anEventWithNoTimesAtAllLeavesTheBoardingChipBlankRatherThanThrowing() {
            String html = template.render(boardingPass()
                    .departureTime(null)
                    .boardingTime(null)
                    .build());

            assertThat(html).contains(">BOARDING</div><div style=\"font-weight:700;font-size:13px;margin-top:2px;\">-</div>");
        }

        @Test
        void anUnassignedGateReadsTbaAndOtherMissingFieldsShowDashes() {
            String html = template.render(boardingPass()
                    .gate(null)
                    .seatNumber(null)
                    .boardingGroup(null)
                    .boardingPassNumber(null)
                    .bookingReference(null)
                    .build());

            assertThat(html).contains(">TBA</div>");
            assertThat(html).contains(">-</div>");
            assertThat(html).doesNotContain("null");
        }

        @Test
        void blankFieldsFromTheProducerAreTreatedAsMissingRatherThanPrinted() {
            String html = template.render(boardingPass()
                    .passengerName("  ")
                    .seatNumber(" ")
                    .gate("")
                    .build());

            assertThat(html).contains("Hello traveler,");
            assertThat(html).contains(">TBA</div>");
            assertThat(html).contains(">-</div>");
        }

        @Test
        void anAnonymousPassengerOnAnUnknownFlightStillGetsAReadableEmail() {
            String html = template.render(boardingPass()
                    .passengerName(null)
                    .flightNumber(null)
                    .originAirportCode(null)
                    .destinationAirportCode(null)
                    .build());

            assertThat(html).contains("Hello traveler,");
            assertThat(html).contains("boarding pass for your flight.");
            assertThat(html).doesNotContain("null");
        }

        @Test
        void theQrReferencesTheContentIdTheConsumerAttachesUnder() {
            String html = template.render(boardingPass().build());

            assertThat(html).contains("src=\"cid:" + CheckInEmailTemplate.QR_CID + "\"");
            assertThat(html).contains("Show this QR code at the gate.");
        }

        @Test
        void aPassengerNameCarryingMarkupIsEscapedNotEmbedded() {
            String html = template.render(boardingPass()
                    .passengerName("<script>alert('pwn')</script>")
                    .build());

            assertThat(html).doesNotContain("<script>");
            assertThat(html).contains("&lt;script&gt;alert('pwn')&lt;/script&gt;");
        }
    }

    @Nested
    @DisplayName("Airline branding")
    class AirlineBranding {

        @Test
        void aKnownCarrierBrandsTheHeaderAndTheMonogramBadge() {
            String html = template.render(boardingPass().flightNumber("BA178").build());

            assertThat(html).contains("British Airways");
            assertThat(html).contains("#075AAA");
            assertThat(html).contains("#EB2226");
            assertThat(html).contains(">BA</td>");
        }

        @Test
        void anUnknownCarrierFallsBackToSkyBooksOwnBrand() {
            String html = template.render(boardingPass().flightNumber("ZZ999").build());

            assertThat(html).contains("SkyBook Airways");
            assertThat(html).contains("#0b3d91");
            assertThat(html).contains(">SB</td>");
            assertThat(html).doesNotContain("British Airways");
        }
    }
}
