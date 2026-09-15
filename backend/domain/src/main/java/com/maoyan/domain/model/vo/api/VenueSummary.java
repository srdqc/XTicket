package com.maoyan.domain.model.vo.api;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.maoyan.domain.model.po.VenuePO;
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

    public static VenueSummary from(VenuePO po, List<String> hallTypes) {
        if (po == null) {
            return null;
        }
        VenueSummary summary = new VenueSummary();
        summary.setId(po.getId());
        summary.setName(po.getNm());
        summary.setAddress(po.getAddr());
        summary.setDistance(po.getDistance());

        Features features = new Features();
        features.setAllowRefund(po.getAllowRefund() != null && po.getAllowRefund() == 1);
        features.setEndorse(po.getEndorse() != null && po.getEndorse() == 1);
        features.setSnack(po.getSnack() != null && po.getSnack() == 1);
        features.setVipTag(po.getVipTag());
        features.setHallTypes(hallTypes);
        summary.setFeatures(features);

        Promotion promotion = new Promotion();
        promotion.setCardPromotionTag(po.getCardPromotionTag());
        summary.setPromotion(promotion);
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
