package com.j11a.dashboard.newsservice.controller;

import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@Slf4j
@RequestMapping("/news/v1")
public class NewsController {

    @GetMapping
    public String hello() {
        return "Hello World";
    }

}
