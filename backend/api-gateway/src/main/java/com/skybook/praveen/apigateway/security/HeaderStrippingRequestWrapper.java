package com.skybook.praveen.apigateway.security;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletRequestWrapper;

import java.util.Collections;
import java.util.Enumeration;
import java.util.LinkedHashSet;
import java.util.Set;

/**
 * Removes one header from the request view (GUEST_CHECKIN_MODULE.md §2.9).
 * Downstream services never read X-Auth-User as identity - they re-validate
 * the token - but a client-supplied value would still flow into their logs
 * and traces on PUBLIC paths, where no {@link HeaderAddingRequestWrapper}
 * replaces it. The gateway is the header's only legitimate author, so
 * inbound copies are dropped at the door.
 */
public class HeaderStrippingRequestWrapper extends HttpServletRequestWrapper {

    private final String headerName;

    public HeaderStrippingRequestWrapper(HttpServletRequest request, String headerName) {
        super(request);
        this.headerName = headerName;
    }

    @Override
    public String getHeader(String name) {
        return headerName.equalsIgnoreCase(name) ? null : super.getHeader(name);
    }

    @Override
    public Enumeration<String> getHeaders(String name) {
        if (headerName.equalsIgnoreCase(name)) {
            return Collections.emptyEnumeration();
        }
        return super.getHeaders(name);
    }

    @Override
    public Enumeration<String> getHeaderNames() {
        Set<String> names = new LinkedHashSet<>(Collections.list(super.getHeaderNames()));
        names.removeIf(headerName::equalsIgnoreCase);
        return Collections.enumeration(names);
    }
}
