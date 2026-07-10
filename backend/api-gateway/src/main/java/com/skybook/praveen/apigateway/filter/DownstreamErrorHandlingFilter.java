package com.skybook.praveen.apigateway.filter;

import com.skybook.praveen.common.exception.ErrorResponse;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.servlet.function.HandlerFilterFunction;
import org.springframework.web.servlet.function.HandlerFunction;
import org.springframework.web.servlet.function.ServerRequest;
import org.springframework.web.servlet.function.ServerResponse;

import java.time.LocalDateTime;

/**
 * Applied to every route in GatewayRoutesConfig - without this, a
 * downstream service being down surfaces as Spring Boot's generic /error
 * page with a 500, which looks like a gateway bug rather than "the target
 * service is unreachable". Converts connection failures into 502 with the
 * same ErrorResponse shape every other gateway-originated error uses;
 * anything else is rethrown and left to Boot's default handling.
 *
 * Catches ResourceAccessException, not java.net.ConnectException directly -
 * spring-cloud-gateway-server-webmvc's http() route proxies through Spring's
 * RestClient, which wraps every I/O failure (connection refused, reset,
 * timeout) in a ResourceAccessException. Found live: a connect-failure test
 * against a closed port surfaced this filter's ConnectException catch as
 * dead code - the real exception in the stack was ResourceAccessException.
 */
public class DownstreamErrorHandlingFilter implements HandlerFilterFunction<ServerResponse, ServerResponse> {

    @Override
    public ServerResponse filter(ServerRequest request, HandlerFunction<ServerResponse> next) throws Exception {
        try {
            return next.handle(request);
        } catch (ResourceAccessException e) {
            var errorBody = new ErrorResponse(
                    LocalDateTime.now(), 502, "Bad Gateway",
                    "Downstream service unreachable", request.path());
            return ServerResponse.status(502).body(errorBody);
        }
    }
}
