package com.j11a.dashboard.newsservice.controller;

import com.j11a.dashboard.newsservice.model.ApiResponse;
import com.j11a.dashboard.newsservice.service.NewsService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.http.ResponseEntity;
import org.springframework.test.web.servlet.MockMvc;

import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultHandlers.print;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(NewsController.class)
class NewsControllerTest {
    @Autowired
    private MockMvc mockMvc;

    @MockBean
    private NewsService newsService;

    @Test
    void testHello() throws Exception {
        this.mockMvc.perform(get("/news/v1"))
                .andDo(print())
                .andExpect(status().isOk())
                .andExpect(content().string("Hello World"));
    }

    @Test
    void headlines() throws Exception {
        when(newsService.getLatestHeadlines(null, null, null, null, null)).thenReturn(ResponseEntity.ok(new ApiResponse<>()));

        this.mockMvc.perform(get("/news/v1/headlines"))
                .andDo(print())
                .andExpect(status().isOk());
    }
}