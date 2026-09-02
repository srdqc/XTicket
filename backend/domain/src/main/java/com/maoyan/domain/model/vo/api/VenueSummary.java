package com.maoyan.domain.model.vo.api;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.maoyan.domain.model.vo.CinemaVO;
import lombok.Data;

import java.io.Serializable;
import java.util.List;

@Data
@JsonInclude(JsonInclude.Include.NON_NULL)
public class VenueSummary implements Serializable {

    private Long id;
    private String name;
    private String address;
    private String distance;
    private Features features;
    private Promotion promotion;

    public static VenueSummary from(CinemaVO vo) {
        if (vo == null) {
            return null;
        }
        VenueSummary summary = new VenueSummary();
        summary.setId(vo.getId());
        summary.setName(vo.getNm());
        summary.setAddress(vo.getAddr());
        summary.setDistance(vo.getDistance());
        if (vo.getTag() != null) {
            Features features = new Features();
            features.setAllowRefund(vo.getTag().getAllowRefund());
            features.setEndorse(vo.getTag().getEndorse());
            features.setSnack(vo.getTag().getSnack());
            features.setVipTag(vo.getTag().getVipTag());
            features.setHallTypes(vo.getTag().getHallType());
            summary.setFeatures(features);
        }
        if (vo.getPromotion() != null) {
            Promotion promotion = new Promotion();
            promotion.setCardPromotionTag(vo.getPromotion().getCardPromotionTag());
            summary.setPromotion(promotion);
        }
        return summary;
    }

    @Data
    public static class Features implements Serializable {
        private Boolean allowRefund;
        private Boolean endorse;
        private Boolean snack;
        private String vipTag;
        private List<String> hallTypes;
    }

    @Data
    public static class Promotion implements Serializable {
        private String cardPromotionTag;
    }
}
