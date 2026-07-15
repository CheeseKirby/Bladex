package org.springblade.aiworkflow.common;

import org.junit.jupiter.api.Test;
import org.slf4j.MDC;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;

class TraceIdFilterTest {

    private final TraceIdFilter filter = new TraceIdFilter();

    @Test
    void validIncomingRequestIdIsPreservedAndRemovedFromMdcAfterRequest() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.addHeader(TraceIdFilter.REQUEST_HEADER, "request-12345678");
        MockHttpServletResponse response = new MockHttpServletResponse();

        filter.doFilter(request, response, (req, res) ->
                assertEquals("request-12345678", MDC.get("traceId")));

        assertEquals("request-12345678", response.getHeader(TraceIdFilter.RESPONSE_HEADER));
        assertNull(MDC.get("traceId"));
    }

    @Test
    void unsafeIncomingRequestIdIsReplaced() {
        String generated = TraceIdFilter.normalize("bad header with spaces and secrets");
        assertFalse(generated.contains(" "));
        assertEquals(32, generated.length());
    }
}
