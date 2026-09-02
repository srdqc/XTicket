package com.maoyan.domain.model.vo.api;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serializable;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class FollowStatus implements Serializable {

    private boolean followed;
    private long followCount;
}
