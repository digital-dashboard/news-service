package com.j11a.dashboard.newsservice.model;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.ZonedDateTime;

@AllArgsConstructor
@NoArgsConstructor
@Data
public class NewsArticle {
    private String title;
    private String author;
    private String excerpt;
    private String summary;
    private ZonedDateTime publishedDate;
    private String url;
    private String country;
    private Topic topic;
}
