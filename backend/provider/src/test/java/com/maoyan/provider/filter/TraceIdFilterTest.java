package com.maoyan.provider.filter;

import com.maoyan.common.observability.TraceContext;
import jakarta.servlet.ServletException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class TraceIdFilterTest {

    private final TraceIdFilter filter = new TraceIdFilter();

    @AfterEach
    void clearMdc() {
        TraceContext.clear();
    }

    @Test
    void missingTraceIdGeneratesThirtyTwoLowercaseHexCharactersAndCleansMdc() throws Exception {
        MockHttpServletResponse response = execute(null);

        assertThat(response.getHeader(TraceContext.HEADER_NAME)).matches("[0-9a-f]{32}");
        assertThat(TraceContext.currentTraceId()).isNull();
    }

    @Test
    void validClientTraceIdIsReturnedUnchanged() throws Exception {
        MockHttpServletResponse response = execute("phase6a-test_001");

        assertThat(response.getHeader(TraceContext.HEADER_NAME)).isEqualTo("phase6a-test_001");
    }

    @Test
    void invalidOrOversizedTraceIdIsReplaced() throws Exception {
        assertThat(execute("invalid trace").getHeader(TraceContext.HEADER_NAME))
                .matches("[0-9a-f]{32}");
        assertThat(execute("a".repeat(65)).getHeader(TraceContext.HEADER_NAME))
                .matches("[0-9a-f]{32}");
    }

    @Test
    void earlyUnauthorizedResponseStillHasTraceHeaderAndCleansMdc() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest();
        MockHttpServletResponse response = new MockHttpServletResponse();

        filter.doFilter(request, response, (req, res) -> ((MockHttpServletResponse) res).setStatus(401));

        assertThat(response.getStatus()).isEqualTo(401);
        assertThat(response.getHeader(TraceContext.HEADER_NAME)).matches("[0-9a-f]{32}");
        assertThat(TraceContext.currentTraceId()).isNull();
    }

    @Test
    void exceptionalRequestStillHasTraceHeaderAndCleansMdc() {
        MockHttpServletRequest request = new MockHttpServletRequest();
        MockHttpServletResponse response = new MockHttpServletResponse();

        assertThatThrownBy(() -> filter.doFilter(request, response,
                (req, res) -> { throw new ServletException("boom"); }))
                .isInstanceOf(ServletException.class);
        assertThat(response.getHeader(TraceContext.HEADER_NAME)).matches("[0-9a-f]{32}");
        assertThat(TraceContext.currentTraceId()).isNull();
    }

    @Test
    void followingRequestDoesNotInheritPreviousTraceId() throws Exception {
        MockHttpServletResponse first = execute("first-trace");
        MockHttpServletResponse second = execute(null);

        assertThat(first.getHeader(TraceContext.HEADER_NAME)).isEqualTo("first-trace");
        assertThat(second.getHeader(TraceContext.HEADER_NAME)).matches("[0-9a-f]{32}")
                .isNotEqualTo("first-trace");
    }

    private MockHttpServletResponse execute(String traceId) throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest();
        if (traceId != null) {
            request.addHeader(TraceContext.HEADER_NAME, traceId);
        }
        MockHttpServletResponse response = new MockHttpServletResponse();
        filter.doFilter(request, response, (req, res) ->
                assertThat(TraceContext.currentTraceId())
                        .isEqualTo(response.getHeader(TraceContext.HEADER_NAME)));
        return response;
    }
}
