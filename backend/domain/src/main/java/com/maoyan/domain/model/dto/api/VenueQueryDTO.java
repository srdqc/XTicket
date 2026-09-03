package com.maoyan.domain.model.dto.api;

import com.maoyan.domain.model.dto.CinemaQueryDTO;
import lombok.Data;

import java.io.Serializable;

@Data
public class VenueQueryDTO implements Serializable {

    private Integer offset = 0;
    private String day;
    private Long cityId;
    private Long brandId;
    private Long serviceId;
    private Long hallType;
    private Long areaId;
    private Long districtId;

    public CinemaQueryDTO toCinemaQueryDTO() {
        CinemaQueryDTO query = new CinemaQueryDTO();
        query.setOffset(offset);
        query.setDay(day != null ? day : "");
        query.setCityId(cityId != null && cityId > 0 ? cityId : 1L);
        query.setBrandId(brandId);
        query.setServiceId(serviceId);
        query.setHallType(hallType);
        query.setAreaId(areaId);
        query.setDistrictId(districtId);
        query.setLimit(20);
        return query;
    }
}
