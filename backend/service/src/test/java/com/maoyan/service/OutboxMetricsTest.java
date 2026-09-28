package com.maoyan.service;

import com.maoyan.common.observability.TraceContext;
import com.maoyan.dao.mapper.OutboxEventMapper;
import com.maoyan.domain.model.dto.OutboxStatusCountDTO;
import com.maoyan.service.observability.OutboxMetrics;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class OutboxMetricsTest {

    @Test
    void refreshLoadsObservableStatusesIntoMemoryGaugesAndClearsTrace() {
        OutboxEventMapper mapper = mock(OutboxEventMapper.class);
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        when(mapper.selectObservableStatusCounts()).thenReturn(List.of(
                count("PENDING", 3), count("FAILED", 2), count("PROCESSING", 1), count("PUBLISHED", 9)));
        OutboxMetrics metrics = new OutboxMetrics(mapper, registry);

        metrics.refresh();

        assertThat(registry.get("xticket.outbox.pending").gauge().value()).isEqualTo(3);
        assertThat(registry.get("xticket.outbox.failed").gauge().value()).isEqualTo(2);
        assertThat(registry.get("xticket.outbox.processing").gauge().value()).isEqualTo(1);
        assertThat(registry.find("xticket.outbox.dead").gauge()).isNull();
        assertThat(TraceContext.currentTraceId()).isNull();
    }

    private OutboxStatusCountDTO count(String status, long value) {
        OutboxStatusCountDTO count = new OutboxStatusCountDTO();
        count.setStatus(status);
        count.setCount(value);
        return count;
    }
}
