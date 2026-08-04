package com.skybook.praveen.notificationservice.service;

import com.skybook.praveen.notificationservice.service.AirlineLookup.AirlineBrand;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Branding is derived from the flight number alone - no event in the system
 * carries an airline code - so a parsing slip would silently repaint every
 * boarding-pass email in the wrong carrier's colours. These pin the parse and
 * the fallback.
 */
class AirlineLookupTest {

    @Nested
    @DisplayName("Known carriers")
    class KnownCarriers {

        @Test
        void theLetterPrefixOfAFlightNumberSelectsTheCarriersBrand() {
            AirlineBrand brand = AirlineLookup.forFlightNumber("BA178");

            assertThat(brand.code()).isEqualTo("BA");
            assertThat(brand.displayName()).isEqualTo("British Airways");
            assertThat(brand.primaryColor()).isEqualTo("#075AAA");
            assertThat(brand.secondaryColor()).isEqualTo("#EB2226");
        }

        @Test
        void aLowercaseFlightNumberResolvesToTheSameCarrier() {
            assertThat(AirlineLookup.forFlightNumber("vs003").displayName()).isEqualTo("Virgin Atlantic");
        }

        @Test
        void aBareAirlineCodeWithNoFlightDigitsStillResolves() {
            assertThat(AirlineLookup.forFlightNumber("QR").displayName()).isEqualTo("Qatar Airways");
        }

        @Test
        void carriersAcrossTheMapKeepTheirOwnIdentity() {
            assertThat(AirlineLookup.forFlightNumber("EK7").displayName()).isEqualTo("Emirates");
            assertThat(AirlineLookup.forFlightNumber("SQ317").displayName()).isEqualTo("Singapore Airlines");
            assertThat(AirlineLookup.forFlightNumber("AI101").displayName()).isEqualTo("Air India");
            assertThat(AirlineLookup.forFlightNumber("LH900").primaryColor()).isEqualTo("#05164D");
        }
    }

    @Nested
    @DisplayName("Fallback brand")
    class FallbackBrand {

        private void assertIsSkyBooksOwnBrand(AirlineBrand brand) {
            assertThat(brand.code()).isEqualTo("SB");
            assertThat(brand.displayName()).isEqualTo("SkyBook Airways");
            assertThat(brand.primaryColor()).isEqualTo("#0b3d91");
            assertThat(brand.secondaryColor()).isEqualTo("#E8B923");
        }

        @Test
        void anAirlineOutsideTheMapFallsBackToSkyBook() {
            assertIsSkyBooksOwnBrand(AirlineLookup.forFlightNumber("ZZ999"));
        }

        @Test
        void aFlightNumberWithNoLetterPrefixFallsBackToSkyBook() {
            assertIsSkyBooksOwnBrand(AirlineLookup.forFlightNumber("1442"));
        }

        @Test
        void aMissingFlightNumberFallsBackToSkyBook() {
            assertIsSkyBooksOwnBrand(AirlineLookup.forFlightNumber(null));
        }

        @Test
        void aThreeLetterPrefixIsNotMistakenForItsTwoLetterCarrier() {
            // "BAX999" is not British Airways - the whole prefix is the code.
            assertIsSkyBooksOwnBrand(AirlineLookup.forFlightNumber("BAX999"));
        }
    }
}
