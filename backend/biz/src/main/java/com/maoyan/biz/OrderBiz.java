package com.maoyan.biz;

import com.maoyan.domain.model.dto.CreateOrderDTO;
import com.maoyan.domain.model.po.ActivitySessionPO;
import com.maoyan.domain.model.vo.OrderVO;
import com.maoyan.domain.model.vo.RefundResult;
import com.maoyan.service.ActivityService;
import com.maoyan.service.ActivitySessionService;
import com.maoyan.service.OrderService;
import com.maoyan.service.RefundService;
import com.maoyan.service.observability.BusinessMetrics;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.List;

@Slf4j
@Component
@RequiredArgsConstructor
public class OrderBiz {

    private final OrderService orderService;
    private final ActivitySessionService activitySessionService;
    private final ActivityService activityService;
    private final RefundService refundService;
    private final BusinessMetrics businessMetrics;

    public OrderVO createOrder(Long userId, CreateOrderDTO dto) {
        OrderVO orderVO = orderService.createOrder(userId, dto);

        long enrichmentStarted = System.nanoTime();
        try {
            ActivitySessionPO schedule = activitySessionService.getSessionById(dto.getScheduleId());
            if (schedule != null) {
                var movieDetail = activityService.getActivityDetail(schedule.getActivityId());
                if (movieDetail != null) {
                    orderVO.setMovieName(movieDetail.getName());
                    orderVO.setMovieImg(movieDetail.getCoverUrl());
                }
            }
        } catch (Exception e) {
            log.warn("Failed to enrich order snapshot", e);
        } finally {
            businessMetrics.recordOrderCreateStage("response_enrichment",
                    System.nanoTime() - enrichmentStarted);
        }

        return orderVO;
    }

    public void cancelOrder(Long userId, String orderNo) {
        orderService.cancelOrder(userId, orderNo);
    }

    public RefundResult refundOrder(Long userId, String orderNo) {
        return refundService.refund(userId, orderNo);
    }

    public List<OrderVO> getUserOrders(Long userId, int page, int size) {
        return orderService.getUserOrders(userId, page, size);
    }
}
