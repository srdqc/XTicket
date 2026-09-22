package com.maoyan.service.infrastructure;

import org.junit.jupiter.api.Test;
import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class DistributedLockServiceTest {

    @Test
    void partialSeatLockFailureReleasesAcquiredLocksInReverseOrder() throws Exception {
        RedissonClient redisson = mock(RedissonClient.class);
        RLock first = mock(RLock.class);
        RLock second = mock(RLock.class);
        RLock third = mock(RLock.class);
        when(redisson.getLock("lock:seat:40:1:1")).thenReturn(first);
        when(redisson.getLock("lock:seat:40:1:2")).thenReturn(second);
        when(redisson.getLock("lock:seat:40:1:3")).thenReturn(third);
        when(first.tryLock(anyLong(), anyLong(), eq(TimeUnit.NANOSECONDS))).thenReturn(true);
        when(second.tryLock(anyLong(), anyLong(), eq(TimeUnit.NANOSECONDS))).thenReturn(true);
        when(third.tryLock(anyLong(), anyLong(), eq(TimeUnit.NANOSECONDS))).thenReturn(false);
        when(first.isHeldByCurrentThread()).thenReturn(true);
        when(second.isHeldByCurrentThread()).thenReturn(true);

        DistributedLockService service = new DistributedLockService();
        ReflectionTestUtils.setField(service, "redissonClient", redisson);

        String result = service.executeWithBoundedLocks(List.of(
                "seat:40:1:1", "seat:40:1:2", "seat:40:1:3"), 3, 12, () -> "unexpected");

        assertThat(result).isNull();
        var releaseOrder = inOrder(second, first);
        releaseOrder.verify(second).unlock();
        releaseOrder.verify(first).unlock();
        verify(third, never()).unlock();
    }
}
