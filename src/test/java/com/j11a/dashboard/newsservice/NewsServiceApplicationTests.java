package com.j11a.dashboard.newsservice;

import com.j11a.dashboard.newsservice.service.NewsService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.TestPropertySource;

import static org.junit.jupiter.api.Assertions.assertNotNull;

@SpringBootTest
@TestPropertySource(properties = {
        "newscatcher.apiKey=123"
})
class NewsServiceApplicationTests {

    @Autowired
    private NewsService newsService;

    @Test
    void contextLoads() {
        assertNotNull(newsService);
    }

}
