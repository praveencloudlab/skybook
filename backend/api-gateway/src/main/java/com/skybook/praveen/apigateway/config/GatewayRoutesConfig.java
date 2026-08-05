package com.skybook.praveen.apigateway.config;

import com.skybook.praveen.apigateway.filter.DownstreamErrorHandlingFilter;
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
        // Auth endpoints are listed EXPLICITLY, never by wildcard
        // (SECURITY_HARDENING_MODULE.md §3.3): /api/auth/service-token must stay
        // unreachable from the public edge - it lives on the internal-only
        // client-credential chain. Adding a new auth endpoint therefore means
        // adding it here on purpose, which is exactly the point.
        //
        // logout and me exist for the browser session cookie (FRONTEND_MODULE.md
        // §10.1): because the cookie is httpOnly, JavaScript can neither clear it
        // nor read who it belongs to, so both have to be server round-trips.
        return route("auth-service")
                .route(path("/api/auth/register", "/api/auth/login",
                                "/api/auth/logout", "/api/auth/me",
                                // Password reset: pre-authentication, so both are
                                // also in the gateway's PUBLIC_PATHS. Listed here
                                // explicitly like every other auth path - the
                                // wildcard is deliberately never used, so
                                // /api/auth/service-token stays off the edge.
                                "/api/auth/forgot-password", "/api/auth/reset-password",
                                // Sign in with Google (SSO_MODULE.md §5): the OAuth
                                // start + callback legs and provider discovery.
                                // Exact paths for the same reason as everything
                                // above them.
                                "/api/auth/oauth2/authorization/google",
                                "/api/auth/oauth2/callback/google",
                                "/api/auth/sso/providers"),
                        http(services.getAuthService().getBaseUrl()))
                .filter(new DownstreamErrorHandlingFilter())
                .build();
    }

    @Bean
    public RouterFunction<ServerResponse> profileRoute(ServicesProperties services) {
        // Passenger profile + saved travellers (FRONTEND_MODULE.md Module 14),
        // served by auth-service (it owns the users table). A wildcard is fine
        // here - unlike /api/auth, this prefix has no internal-only endpoint to
        // keep off the edge, and every path requires a valid session (gated by
        // the gateway JWT filter, since it is NOT in PUBLIC_PATHS).
        return route("profile")
                .route(path("/api/profile/**"), http(services.getAuthService().getBaseUrl()))
                .filter(new DownstreamErrorHandlingFilter())
                .build();
    }

    @Bean
    public RouterFunction<ServerResponse> flightServiceRoute(ServicesProperties services) {
        return route("flight-service")
                .route(path("/api/flights/**", "/api/flight-schedules/**"), http(services.getFlightService().getBaseUrl()))
                .filter(new DownstreamErrorHandlingFilter())
                .build();
    }

    @Bean
    public RouterFunction<ServerResponse> bookingServiceRoute(ServicesProperties services) {
        return route("booking-service")
                .route(path("/api/bookings/**"), http(services.getBookingService().getBaseUrl()))
                .filter(new DownstreamErrorHandlingFilter())
                .build();
    }

    @Bean
    public RouterFunction<ServerResponse> inventoryServiceRoute(ServicesProperties services) {
        return route("inventory-service")
                .route(path("/api/reservations/**", "/api/inventory/**", "/api/aircraft/**"),
                        http(services.getInventoryService().getBaseUrl()))
                .filter(new DownstreamErrorHandlingFilter())
                .build();
    }

    @Bean
    public RouterFunction<ServerResponse> paymentServiceRoute(ServicesProperties services) {
        return route("payment-service")
                .route(path("/api/payments/**", "/api/refunds/**", "/api/invoices/**"),
                        http(services.getPaymentService().getBaseUrl()))
                .filter(new DownstreamErrorHandlingFilter())
                .build();
    }

    @Bean
    public RouterFunction<ServerResponse> checkinServiceRoute(ServicesProperties services) {
        return route("checkin-service")
                .route(path("/api/checkins/**", "/api/boarding-passes/**", "/api/baggage/**", "/api/manifests/**"),
                        http(services.getCheckinService().getBaseUrl()))
                .filter(new DownstreamErrorHandlingFilter())
                .build();
    }
}
