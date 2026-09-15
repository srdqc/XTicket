package com.maoyan.domain.model.vo.api;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.maoyan.domain.model.po.ActivityPO;
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

    public static ActivitySummary from(ActivityPO po) {
        if (po == null) {
            return null;
        }
        ActivitySummary summary = new ActivitySummary();
        summary.setId(po.getId());
        summary.setName(po.getNm());
        summary.setCoverUrl(po.getImg());
        if (po.getGlobalReleased() != null && po.getGlobalReleased() == 1 && po.getSc() != null) {
            summary.setScore(po.getSc());
        } else {
            summary.setScore("暂无评分");
        }
        summary.setCategory(po.getCat());
        summary.setSource(po.getSrc());
        summary.setDuration(po.getDur());
        summary.setPublishDescription(po.getPubDesc());
        summary.setFollowCount(po.getWish());
        summary.setReleased(po.getGlobalReleased() != null && po.getGlobalReleased() == 1);
        summary.setReleaseYear(po.getReleaseYear());
        summary.setShowInfo(po.getShowInfo());
        summary.setComingTitle(po.getComingTitle());
        return summary;
    }
}
