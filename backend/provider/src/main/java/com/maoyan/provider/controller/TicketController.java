package com.maoyan.provider.controller;

import com.maoyan.domain.model.vo.Result;
import com.maoyan.domain.model.vo.TicketVO;
import com.maoyan.service.TicketService;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/api/tickets")
@RequiredArgsConstructor
public class TicketController {

    private final TicketService ticketService;

    @GetMapping
    public Result<List<TicketVO>> getTickets(
            @RequestParam(required = false) String orderNo,
            HttpServletRequest request) {
        Long userId = (Long) request.getAttribute("userId");
        return Result.ok(ticketService.getUserTickets(userId, orderNo));
    }

    @GetMapping("/{ticketNo}")
    public Result<TicketVO> getTicket(@PathVariable String ticketNo, HttpServletRequest request) {
        Long userId = (Long) request.getAttribute("userId");
        return Result.ok(ticketService.getUserTicket(userId, ticketNo));
    }
}
