package com.maoyan.domain.model.dto;

import lombok.Data;

@Data
public class OutboxStatusCountDTO {
    private String status;
    private Long count;
}
