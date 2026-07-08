package com.skybook.praveen.checkinservice.domain;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class BoardingGroupAssignerTest {

    private final BoardingGroupAssigner assigner = new BoardingGroupAssigner();

    @Test
    void businessBoardsFirst() {
        assertThat(assigner.assign("BUSINESS", "FLEXI")).isEqualTo("1");
    }

    @Test
    void premiumEconomyBoardsSecond() {
        assertThat(assigner.assign("PREMIUM_ECONOMY", "SAVER")).isEqualTo("2");
    }

    @Test
    void economyFlexiBoardsThird() {
        assertThat(assigner.assign("ECONOMY", "FLEXI")).isEqualTo("3");
        assertThat(assigner.assign("ECONOMY", "PREMIUM")).isEqualTo("3");
    }

    @Test
    void economySaverBoardsLast() {
        assertThat(assigner.assign("ECONOMY", "SAVER")).isEqualTo("4");
    }

    @Test
    void nullOrUnknownTravelClassBoardsLast() {
        assertThat(assigner.assign(null, "FLEXI")).isEqualTo("4");
        assertThat(assigner.assign("SUPERSONIC", "FLEXI")).isEqualTo("4");
    }
}
