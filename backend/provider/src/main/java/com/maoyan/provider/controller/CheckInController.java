package com.maoyan.provider.controller;

import com.maoyan.domain.model.dto.CheckInRequestDTO;
import com.maoyan.domain.model.vo.CheckInResult;
import com.maoyan.domain.model.vo.Result;
import com.maoyan.service.CheckInService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/checkin")
@RequiredArgsConstructor
public class CheckInController {

    private final CheckInService checkInService;

    @PostMapping("/tickets/{ticketNo}")
    public Result<CheckInResult> checkIn(@PathVariable String ticketNo,
                                         @Valid @RequestBody CheckInRequestDTO dto,
                                         HttpServletRequest request) {
        Long operatorUserId = (Long) request.getAttribute("userId");
        return Result.ok(checkInService.checkIn(operatorUserId, ticketNo, dto.getSessionId()));
    }
}
