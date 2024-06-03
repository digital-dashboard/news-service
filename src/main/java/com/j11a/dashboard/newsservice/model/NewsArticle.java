package com.j11a.dashboard.newsservice.model;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

@AllArgsConstructor
@NoArgsConstructor
@Data
public class NewsArticle {
    private String title;
    private String author;
    private String excerpt;
    private String summary;
    private String publishedDate;
    private String url;
    private String country;
    private Topic topic;
}
