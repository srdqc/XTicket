package com.maoyan.domain.model.vo.api;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.maoyan.domain.model.po.VenuePO;
import lombok.Data;

import java.io.Serializable;
import java.util.List;

@Data
@JsonInclude(JsonInclude.Include.NON_NULL)
public class VenueDetail implements Serializable {

    private Long id;
    private String name;
    private String address;
    private Boolean allowRefund;
    private Boolean endorse;
    private Boolean snack;
    private String vipTag;
    private List<String> hallTypes;

    public static VenueDetail from(VenuePO po, List<String> hallTypes) {
        if (po == null) {
            return null;
        }
        VenueDetail detail = new VenueDetail();
        detail.setId(po.getId());
        detail.setName(po.getNm());
        detail.setAddress(po.getAddr());
        detail.setAllowRefund(po.getAllowRefund() != null && po.getAllowRefund() == 1);
        detail.setEndorse(po.getEndorse() != null && po.getEndorse() == 1);
        detail.setSnack(po.getSnack() != null && po.getSnack() == 1);
        detail.setVipTag(po.getVipTag());
        detail.setHallTypes(hallTypes);
        return detail;
    }
}
