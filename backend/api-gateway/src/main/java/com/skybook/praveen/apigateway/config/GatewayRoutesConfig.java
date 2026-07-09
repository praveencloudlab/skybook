package com.skybook.praveen.apigateway.config;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.function.RouterFunction;
import org.springframework.web.servlet.function.ServerResponse;

import static org.springframework.cloud.gateway.server.mvc.handler.GatewayRouterFunctions.route;
import static org.springframework.cloud.gateway.server.mvc.handler.HandlerFunctions.http;
import static org.springframework.cloud.gateway.server.mvc.predicate.GatewayRequestPredicates.path;

/**
 * Static routing table (design doc §3) - one RouterFunction bean per
 * downstream service. Spring auto-detects and composes every RouterFunction
 * bean in the context (standard functional-web-framework behavior), so no
 * single combined route list is needed.
 *
 * http(baseUrl) proxies the request's original path/query as-is onto that
 * base URL - e.g. base-url "http://localhost:8081" + incoming
 * "/api/auth/login" -> "http://localhost:8081/api/auth/login". No path
 * rewriting: every downstream controller's @RequestMapping prefix is used
 * unchanged (confirmed against each service's actual controllers, not
 * assumed), so the gateway is a pure pass-through, not a path-rewriting proxy.
 *
 * No service discovery (no Eureka/Consul anywhere in this codebase) -
 * base-url values come from ServicesProperties (services.*.base-url in
 * application.yml), the same static-URI pattern every Feign client in the
 * fleet already uses.
 */
@Configuration
@EnableConfigurationProperties(ServicesProperties.class)
public class GatewayRoutesConfig {

    @Bean
    public RouterFunction<ServerResponse> authServiceRoute(ServicesProperties services) {
        return route("auth-service")
                .route(path("/api/auth/**"), http(services.getAuthService().getBaseUrl()))
                .build();
    }

    @Bean
    public RouterFunction<ServerResponse> flightServiceRoute(ServicesProperties services) {
        return route("flight-service")
                .route(path("/api/flights/**", "/api/flight-schedules/**"), http(services.getFlightService().getBaseUrl()))
                .build();
    }

    @Bean
    public RouterFunction<ServerResponse> bookingServiceRoute(ServicesProperties services) {
        return route("booking-service")
                .route(path("/api/bookings/**"), http(services.getBookingService().getBaseUrl()))
                .build();
    }

    @Bean
    public RouterFunction<ServerResponse> inventoryServiceRoute(ServicesProperties services) {
        return route("inventory-service")
                .route(path("/api/reservations/**", "/api/inventory/**", "/api/aircraft/**"),
                        http(services.getInventoryService().getBaseUrl()))
                .build();
    }

    @Bean
    public RouterFunction<ServerResponse> paymentServiceRoute(ServicesProperties services) {
        return route("payment-service")
                .route(path("/api/payments/**", "/api/refunds/**", "/api/invoices/**"),
                        http(services.getPaymentService().getBaseUrl()))
                .build();
    }

    @Bean
    public RouterFunction<ServerResponse> checkinServiceRoute(ServicesProperties services) {
        return route("checkin-service")
                .route(path("/api/checkins/**", "/api/boarding-passes/**", "/api/baggage/**", "/api/manifests/**"),
                        http(services.getCheckinService().getBaseUrl()))
                .build();
    }
}
