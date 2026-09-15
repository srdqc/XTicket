package com.maoyan.domain.model.dto;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import lombok.Data;

import java.io.Serializable;

@Data
public class CheckInRequestDTO implements Serializable {

    @NotNull(message = "场次ID不能为空")
    @Positive(message = "场次ID必须为正数")
    private Long sessionId;
}
