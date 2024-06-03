package com.j11a.dashboard.newsservice.model;

import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

@AllArgsConstructor
@NoArgsConstructor
@Data
public class NewsArticle {
    private String title;
    private String author;

    @JsonProperty("clean_url")
    private String cleanUrl;
    private String excerpt;
    private String summary;

    @JsonProperty("published_date")
    private String publishedDate;
    private String link;
    private String country;
    private Topic topic;
}
