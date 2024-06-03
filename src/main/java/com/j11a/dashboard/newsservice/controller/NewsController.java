package com.j11a.dashboard.newsservice.controller;

import com.j11a.dashboard.newsservice.model.ApiResponse;
import com.j11a.dashboard.newsservice.model.NewsResponse;
import com.j11a.dashboard.newsservice.model.Topic;
import com.j11a.dashboard.newsservice.service.NewsService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@Slf4j
@RequestMapping("/news/v1")
public class NewsController {

    private final NewsService newsService;

    public NewsController(NewsService newsService) {
        this.newsService = newsService;
    }

    @GetMapping
    public String hello() {
        return "Hello World";
    }

    @GetMapping("/headlines")
    public ResponseEntity<ApiResponse<NewsResponse>> headlines(
            @RequestParam(required = false) final String period,
            @RequestParam(required = false) final String[] countries,
            @RequestParam(required = false) final Topic topic,
            @RequestParam(required = false, defaultValue = "50") final Integer pageSize,
            @RequestParam(required = false, defaultValue = "1") final Integer page) {

        return this.newsService.getLatestHeadlines(period, countries, topic, pageSize, page);

    }
}
