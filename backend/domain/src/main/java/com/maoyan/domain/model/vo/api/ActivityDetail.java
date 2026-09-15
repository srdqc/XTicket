package com.maoyan.domain.model.vo.api;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.maoyan.domain.model.po.ActivityPO;
import lombok.Data;

import java.io.Serializable;
import java.util.List;

@Data
@JsonInclude(JsonInclude.Include.NON_NULL)
public class ActivityDetail implements Serializable {

    private Long id;
    private String name;
    private String englishName;
    private String coverUrl;
    private Object score;
    private String category;
    private String source;
    private Integer duration;
    private String publishDescription;
    private String description;
    private Integer followCount;
    private Boolean released;
    private Integer releaseYear;
    private String videoUrl;
    private List<String> photos;
    private Integer photoCount;

    public static ActivityDetail from(ActivityPO po, List<String> photos) {
        if (po == null) {
            return null;
        }
        ActivityDetail detail = new ActivityDetail();
        detail.setId(po.getId());
        detail.setName(po.getNm());
        detail.setEnglishName(po.getEnm());
        detail.setCoverUrl(po.getImg());
        detail.setScore(po.getSc());
        detail.setCategory(po.getCat());
        detail.setSource(po.getSrc());
        detail.setDuration(po.getDur());
        detail.setPublishDescription(po.getPubDesc());
        detail.setDescription(po.getDra());
        detail.setFollowCount(po.getWish());
        detail.setReleased(po.getGlobalReleased() != null && po.getGlobalReleased() == 1);
        detail.setReleaseYear(po.getReleaseYear());
        detail.setVideoUrl(po.getVd());
        detail.setPhotos(photos);
        detail.setPhotoCount(po.getPn());
        return detail;
    }
}
