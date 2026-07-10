package com.skybook.praveen.apigateway.filter;

import jakarta.servlet.ServletException;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class RequestLoggingFilterTest {

    private final RequestLoggingFilter filter = new RequestLoggingFilter();

    @Test
    void generatesACorrelationIdWhenNoneIsSuppliedAndEchoesItOnTheResponse() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/flights");
        MockHttpServletResponse response = new MockHttpServletResponse();

        filter.doFilter(request, response, (req, res) -> {});

        assertThat(response.getHeader(RequestLoggingFilter.CORRELATION_ID_HEADER)).isNotBlank();
    }

    @Test
    void reusesAnIncomingCorrelationId() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/flights");
        request.addHeader(RequestLoggingFilter.CORRELATION_ID_HEADER, "req-123");
        MockHttpServletResponse response = new MockHttpServletResponse();

        filter.doFilter(request, response, (req, res) -> {});

        assertThat(response.getHeader(RequestLoggingFilter.CORRELATION_ID_HEADER)).isEqualTo("req-123");
    }

    @Test
    void generatesADifferentCorrelationIdWhenTheIncomingOneIsBlank() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/flights");
        request.addHeader(RequestLoggingFilter.CORRELATION_ID_HEADER, "   ");
        MockHttpServletResponse response = new MockHttpServletResponse();

        filter.doFilter(request, response, (req, res) -> {});

        assertThat(response.getHeader(RequestLoggingFilter.CORRELATION_ID_HEADER)).isNotBlank().isNotEqualTo("   ");
    }

    @Test
    void stillSetsTheCorrelationIdHeaderWhenTheChainThrows() {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/flights");
        request.addHeader(RequestLoggingFilter.CORRELATION_ID_HEADER, "req-456");
        MockHttpServletResponse response = new MockHttpServletResponse();

        assertThatThrownBy(() -> filter.doFilter(request, response, (req, res) -> {
            throw new ServletException("downstream boom");
        })).isInstanceOf(ServletException.class);

        assertThat(response.getHeader(RequestLoggingFilter.CORRELATION_ID_HEADER)).isEqualTo("req-456");
    }
}
