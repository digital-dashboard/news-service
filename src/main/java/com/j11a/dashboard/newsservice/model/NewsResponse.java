package com.j11a.dashboard.newsservice.model;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

@AllArgsConstructor
@NoArgsConstructor
@Data
public class NewsResponse {
    private Integer totalHits;
    private Integer totalPages;
    private Integer currentPage;
    private Integer pageSize;
    private String from;
    private List<NewsArticle> articles;
}
