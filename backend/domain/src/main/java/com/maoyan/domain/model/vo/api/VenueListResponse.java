package com.maoyan.domain.model.vo.api;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.maoyan.domain.model.vo.CinemaVO;
import lombok.Data;

import java.io.Serializable;
import java.util.List;

@Data
@JsonInclude(JsonInclude.Include.NON_NULL)
public class VenueListResponse implements Serializable {

    private List<VenueSummary> venues;

    public static VenueListResponse from(List<CinemaVO> source) {
        VenueListResponse response = new VenueListResponse();
        response.setVenues(source == null ? List.of() : source.stream()
                .map(VenueSummary::from)
                .toList());
        return response;
    }
}
