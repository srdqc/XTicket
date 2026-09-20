package com.maoyan.domain.model.vo;

import lombok.Data;

import java.io.Serializable;
import java.util.List;

/**
 * Compact seat layout response using zero-based row-major seat indexes.
 *
 * <p>Index conversion: {@code index = (row - 1) * cols + (col - 1)}.</p>
 */
@Data
public class CompactSeatLayoutVO implements Serializable {

    private Long sessionId;
    private String hallName;
    private String hallType;
    private LayoutMetadata layout;
    private List<Integer> sold;
    private List<Integer> locked;
    private List<Integer> myLocked;

    @Data
    public static class LayoutMetadata implements Serializable {
        private Integer rows;
        private Integer cols;
        private List<Integer> aisles;
        private List<Integer> coupleRows;
        private List<Integer> disabled;
    }
}
