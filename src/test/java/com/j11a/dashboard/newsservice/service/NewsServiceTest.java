package com.j11a.dashboard.newsservice.service;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.MapperFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.json.JsonMapper;
import com.j11a.dashboard.newsservice.model.ApiResponse;
import com.j11a.dashboard.newsservice.model.ApiResponseSubCodes;
import com.j11a.dashboard.newsservice.model.NewsResponse;
import com.j11a.dashboard.newsservice.util.NewsCatcherApiUtil;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.junit.jupiter.SpringExtension;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.client.RestTemplate;

import java.net.URI;
import java.util.Objects;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

@ExtendWith(SpringExtension.class)
class NewsServiceTest {

    @InjectMocks
    private NewsService newsService;

    @Mock
    private RestTemplate restTemplate;

    @BeforeEach
    void setUp() {
        NewsCatcherApiUtil newsCatcherApiUtil = mock(NewsCatcherApiUtil.class);

        when(newsCatcherApiUtil.getLatestHeadlinesUrl(any(), any(), any(), any(), any())).thenReturn(URI.create("http://localhost.com"));

        ReflectionTestUtils.setField(newsService, "newsCatcherApiUtil", newsCatcherApiUtil);
        ReflectionTestUtils.setField(newsService, "objectMapper", JsonMapper.builder().configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false).configure(MapperFeature.ACCEPT_CASE_INSENSITIVE_ENUMS, true).build());
    }

    @Test
    void getLatestHeadlines() throws Exception {
        final ObjectMapper objectMapper = new ObjectMapper();
        final String responseJson = new String(Objects.requireNonNull(getClass().getClassLoader().getResourceAsStream("mock-data/newscatcher-response.json")).readAllBytes());

        when(restTemplate.getForEntity(any(URI.class), eq(JsonNode.class))).thenReturn(ResponseEntity.ok(objectMapper.readTree(responseJson)));

        ResponseEntity<ApiResponse<NewsResponse>> responseEntity = newsService.getLatestHeadlines(null, null, null, null, null);

        ApiResponse<NewsResponse> headlines = responseEntity.getBody();
        assertNotNull(headlines);
        assertNotNull(headlines.getData());
        assertEquals(HttpStatusCode.valueOf(200), headlines.getStatus());
        assertEquals(ApiResponseSubCodes.WS_001, headlines.getSubCode());

        NewsResponse data = headlines.getData();

        assertEquals(78, data.getTotalHits());
        assertEquals(5, data.getTotalPages());
        assertEquals(44, data.getCurrentPage());
        assertEquals(1, data.getPageSize());
        assertEquals(1, data.getArticles().size());
    }
}