package com.maoyan.provider.actuator;

import com.maoyan.service.observability.BusinessMetrics;
import lombok.RequiredArgsConstructor;
import org.springframework.boot.actuate.endpoint.annotation.Endpoint;
import org.springframework.boot.actuate.endpoint.annotation.ReadOperation;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.util.Map;

/** Read-only diagnostic snapshot for the fixed order-create stage timers. */
@Component
@Endpoint(id = "ordercreateprofile")
@RequiredArgsConstructor
@ConditionalOnProperty(name = "maoyan.profiling.order-create.enabled", havingValue = "true")
public class OrderCreateProfileEndpoint {

    private final BusinessMetrics businessMetrics;

    @ReadOperation
    public Map<String, BusinessMetrics.OrderCreateStageSnapshot> profile() {
        return businessMetrics.orderCreateStageSnapshots();
    }
}
