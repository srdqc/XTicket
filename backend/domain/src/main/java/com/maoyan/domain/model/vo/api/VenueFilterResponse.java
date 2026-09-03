package com.maoyan.domain.model.vo.api;

import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.Data;

import java.io.Serializable;
import java.util.Map;

@Data
@JsonInclude(JsonInclude.Include.NON_NULL)
public class VenueFilterResponse implements Serializable {

    private Object brand;
    private Object hallType;
    private Object service;
    private Object district;
    private Object subway;

    public static VenueFilterResponse from(Map<String, Object> source) {
        VenueFilterResponse response = new VenueFilterResponse();
        if (source != null) {
            response.setBrand(source.get("brand"));
            response.setHallType(source.get("hallType"));
            response.setService(source.get("service"));
            response.setDistrict(source.get("district"));
            response.setSubway(source.get("subway"));
        }
        return response;
    }
}
