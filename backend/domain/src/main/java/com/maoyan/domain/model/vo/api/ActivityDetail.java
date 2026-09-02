package com.maoyan.domain.model.vo.api;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.maoyan.domain.model.vo.MovieVO;
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

    public static ActivityDetail from(MovieVO vo) {
        if (vo == null) {
            return null;
        }
        ActivityDetail detail = new ActivityDetail();
        detail.setId(vo.getId());
        detail.setName(vo.getNm());
        detail.setEnglishName(vo.getEnm());
        detail.setCoverUrl(vo.getImg());
        detail.setScore(vo.getSc());
        detail.setCategory(vo.getCat());
        detail.setSource(vo.getSrc());
        detail.setDuration(vo.getDur());
        detail.setPublishDescription(vo.getPubDesc());
        detail.setDescription(vo.getDra());
        detail.setFollowCount(vo.getWish());
        detail.setReleased(vo.getGlobalReleased());
        detail.setReleaseYear(vo.getReleaseYear());
        detail.setVideoUrl(vo.getVd());
        detail.setPhotos(vo.getPhotos());
        detail.setPhotoCount(vo.getPn());
        return detail;
    }
}
