package com.skybook.praveen.apigateway.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Binds the `services.<name>.base-url` keys in application.yml (same
 * base-url-per-dependency shape checkin-service already uses for its Feign
 * clients, e.g. `flight-service.base-url`). Spring Boot relaxed binding maps
 * these camelCase fields to the kebab-case YAML keys automatically
 * (authService <-> auth-service) - no Map<String,Endpoint> indirection
 * needed for a small, fixed set of six downstream services.
 */
@ConfigurationProperties(prefix = "services")
public class ServicesProperties {

    private Endpoint authService;
    private Endpoint flightService;
    private Endpoint bookingService;
    private Endpoint inventoryService;
    private Endpoint paymentService;
    private Endpoint checkinService;

    public Endpoint getAuthService() {
        return authService;
    }

    public void setAuthService(Endpoint authService) {
        this.authService = authService;
    }

    public Endpoint getFlightService() {
        return flightService;
    }

    public void setFlightService(Endpoint flightService) {
        this.flightService = flightService;
    }

    public Endpoint getBookingService() {
        return bookingService;
    }

    public void setBookingService(Endpoint bookingService) {
        this.bookingService = bookingService;
    }

    public Endpoint getInventoryService() {
        return inventoryService;
    }

    public void setInventoryService(Endpoint inventoryService) {
        this.inventoryService = inventoryService;
    }

    public Endpoint getPaymentService() {
        return paymentService;
    }

    public void setPaymentService(Endpoint paymentService) {
        this.paymentService = paymentService;
    }

    public Endpoint getCheckinService() {
        return checkinService;
    }

    public void setCheckinService(Endpoint checkinService) {
        this.checkinService = checkinService;
    }

    public static class Endpoint {

        private String baseUrl;

        public String getBaseUrl() {
            return baseUrl;
        }

        public void setBaseUrl(String baseUrl) {
            this.baseUrl = baseUrl;
        }
    }
}
