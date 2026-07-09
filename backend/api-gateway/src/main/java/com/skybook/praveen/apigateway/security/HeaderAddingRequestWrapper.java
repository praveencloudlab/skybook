package com.skybook.praveen.apigateway.security;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletRequestWrapper;

import java.util.Collections;
import java.util.Enumeration;
import java.util.LinkedHashSet;
import java.util.Set;

/**
 * HttpServletRequest doesn't let a filter add a header directly - the only
 * way to inject one before it reaches the downstream proxy call is to wrap
 * the request and override the header-reading methods. Used to attach
 * X-Auth-User once JwtAuthenticationFilter has validated the token (design
 * doc §4) - the seam a future "downstream services trust the gateway"
 * pass would read from.
 */
public class HeaderAddingRequestWrapper extends HttpServletRequestWrapper {

    private final String headerName;
    private final String headerValue;

    public HeaderAddingRequestWrapper(HttpServletRequest request, String headerName, String headerValue) {
        super(request);
        this.headerName = headerName;
        this.headerValue = headerValue;
    }

    @Override
    public String getHeader(String name) {
        if (headerName.equalsIgnoreCase(name)) {
            return headerValue;
        }
        return super.getHeader(name);
    }

    @Override
    public Enumeration<String> getHeaders(String name) {
        if (headerName.equalsIgnoreCase(name)) {
            return Collections.enumeration(Set.of(headerValue));
        }
        return super.getHeaders(name);
    }

    @Override
    public Enumeration<String> getHeaderNames() {
        Set<String> names = new LinkedHashSet<>(Collections.list(super.getHeaderNames()));
        names.add(headerName);
        return Collections.enumeration(names);
    }
}
