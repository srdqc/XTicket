package com.maoyan.domain.model.vo.api;

import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.Data;

import java.io.Serializable;
import java.util.List;

@Data
@JsonInclude(JsonInclude.Include.NON_NULL)
public class VenueListResponse implements Serializable {

    private List<VenueSummary> venues;

    public static VenueListResponse from(List<VenueSummary> source) {
        VenueListResponse response = new VenueListResponse();
        response.setVenues(source == null ? List.of() : source);
        return response;
    }
}
