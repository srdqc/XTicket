package com.maoyan.domain.model.vo.api;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.maoyan.domain.model.vo.MovieVO;
import lombok.Data;

import java.io.Serializable;

@Data
@JsonInclude(JsonInclude.Include.NON_NULL)
public class ActivitySummary implements Serializable {

    private Long id;
    private String name;
    private String coverUrl;
    private Object score;
    private String category;
    private String source;
    private Integer duration;
    private String publishDescription;
    private Integer followCount;
    private Boolean released;
    private Integer releaseYear;
    private String showInfo;
    private String comingTitle;

    public static ActivitySummary from(MovieVO vo) {
        if (vo == null) {
            return null;
        }
        ActivitySummary summary = new ActivitySummary();
        summary.setId(vo.getId());
        summary.setName(vo.getNm());
        summary.setCoverUrl(vo.getImg());
        summary.setScore(vo.getSc());
        summary.setCategory(vo.getCat());
        summary.setSource(vo.getSrc());
        summary.setDuration(vo.getDur());
        summary.setPublishDescription(vo.getPubDesc());
        summary.setFollowCount(vo.getWish());
        summary.setReleased(vo.getGlobalReleased());
        summary.setReleaseYear(vo.getReleaseYear());
        summary.setShowInfo(vo.getShowInfo());
        summary.setComingTitle(vo.getComingTitle());
        return summary;
    }
}
