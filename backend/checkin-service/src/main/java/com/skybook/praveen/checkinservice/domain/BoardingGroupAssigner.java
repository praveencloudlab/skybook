package com.skybook.praveen.checkinservice.domain;

import org.springframework.stereotype.Component;

/**
 * v1: a simple fixed rule by travelClass/fareType - BUSINESS boards first,
 * then PREMIUM_ECONOMY, then ECONOMY FLEXI/PREMIUM, then ECONOMY SAVER last.
 * Not configurable per airline/fare-matrix yet (design doc section 10/14).
 */
@Component
public class BoardingGroupAssigner {

    public String assign(String travelClass, String fareType) {

        if (travelClass == null) {
            return "4";
        }

        return switch (travelClass.toUpperCase()) {
            case "BUSINESS" -> "1";
            case "PREMIUM_ECONOMY" -> "2";
            case "ECONOMY" -> "SAVER".equalsIgnoreCase(fareType) ? "4" : "3";
            default -> "4";
        };
    }
}
