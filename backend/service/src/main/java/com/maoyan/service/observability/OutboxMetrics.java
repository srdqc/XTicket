package com.maoyan.service.observability;

import com.maoyan.common.observability.TraceContext;
import com.maoyan.dao.mapper.OutboxEventMapper;
import com.maoyan.domain.model.dto.OutboxStatusCountDTO;
import io.micrometer.core.instrument.MeterRegistry;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.Locale;
import java.util.concurrent.atomic.AtomicLong;

@Slf4j
@Component
public class OutboxMetrics {

    private final OutboxEventMapper outboxEventMapper;
    private final AtomicLong pending = new AtomicLong();
    private final AtomicLong failed = new AtomicLong();
    private final AtomicLong processing = new AtomicLong();

    public OutboxMetrics(OutboxEventMapper outboxEventMapper, MeterRegistry registry) {
        this.outboxEventMapper = outboxEventMapper;
        registry.gauge("xticket.outbox.pending", pending);
        registry.gauge("xticket.outbox.failed", failed);
        registry.gauge("xticket.outbox.processing", processing);
    }

    @Scheduled(fixedDelayString = "${maoyan.outbox.metrics-refresh-ms:10000}")
    public void refresh() {
        TraceContext.setOrGenerate(null);
        try {
            long pendingCount = 0;
            long failedCount = 0;
            long processingCount = 0;
            for (OutboxStatusCountDTO row : outboxEventMapper.selectObservableStatusCounts()) {
                if (row.getStatus() == null || row.getCount() == null) {
                    continue;
                }
                switch (row.getStatus().toUpperCase(Locale.ROOT)) {
                    case "PENDING" -> pendingCount = row.getCount();
                    case "FAILED" -> failedCount = row.getCount();
                    case "PROCESSING" -> processingCount = row.getCount();
                    default -> { }
                }
            }
            pending.set(pendingCount);
            failed.set(failedCount);
            processing.set(processingCount);
        } catch (RuntimeException e) {
            log.warn("[OutboxMetrics] Failed to refresh status gauges", e);
        } finally {
            TraceContext.clear();
        }
    }
}
