package com.maoyan.common.observability;

import org.slf4j.MDC;

import java.util.UUID;
import java.util.regex.Pattern;

public final class TraceContext {

    public static final String HEADER_NAME = "X-Trace-Id";
    public static final String REQUEST_ATTRIBUTE = "traceId";
    private static final String MDC_KEY = "traceId";
    private static final Pattern VALID_TRACE_ID = Pattern.compile("[A-Za-z0-9_-]{1,64}");

    private TraceContext() {
    }

    public static boolean isValid(String traceId) {
        return traceId != null && VALID_TRACE_ID.matcher(traceId).matches();
    }

    public static String generate() {
        return UUID.randomUUID().toString().replace("-", "");
    }

    public static String validOrGenerate(String traceId) {
        return isValid(traceId) ? traceId : generate();
    }

    public static String currentTraceId() {
        return MDC.get(MDC_KEY);
    }

    public static String setOrGenerate(String traceId) {
        String effectiveTraceId = validOrGenerate(traceId);
        MDC.put(MDC_KEY, effectiveTraceId);
        return effectiveTraceId;
    }

    public static void clear() {
        MDC.remove(MDC_KEY);
    }

    public static Scope open(String traceId) {
        String previous = currentTraceId();
        String effective = setOrGenerate(traceId);
        return new Scope(previous, effective);
    }

    public static final class Scope implements AutoCloseable {
        private final String previous;
        private final String traceId;
        private boolean closed;

        private Scope(String previous, String traceId) {
            this.previous = previous;
            this.traceId = traceId;
        }

        public String traceId() {
            return traceId;
        }

        @Override
        public void close() {
            if (closed) {
                return;
            }
            closed = true;
            if (previous == null) {
                clear();
            } else {
                MDC.put(MDC_KEY, previous);
            }
        }
    }
}
