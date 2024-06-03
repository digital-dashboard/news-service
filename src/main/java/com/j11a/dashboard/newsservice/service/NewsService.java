package com.j11a.dashboard.newsservice.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.j11a.dashboard.newsservice.model.ApiResponse;
import com.j11a.dashboard.newsservice.model.ApiResponseSubCodes;
import com.j11a.dashboard.newsservice.model.NewsResponse;
import com.j11a.dashboard.newsservice.model.Topic;
import com.j11a.dashboard.newsservice.util.NewsCatcherApiUtil;
import lombok.AllArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;

import java.net.URI;
import java.util.Optional;

@Service
@AllArgsConstructor
@Slf4j
public class NewsService {

    private RestTemplate restTemplate;
    private NewsCatcherApiUtil newsCatcherApiUtil;
    private ObjectMapper objectMapper;

    public ResponseEntity<ApiResponse<NewsResponse>> getLatestHeadlines(final String period, final String[] countries, final Topic topic, final Integer pageSize, final Integer page) {
        log.info("START :: getLatestHeadlines");

        final URI newscatcherUrl = newsCatcherApiUtil.getLatestHeadlinesUrl(period, countries, topic, pageSize, page);
        log.info("Calling news service api - {}", newscatcherUrl.toString());

        final ResponseEntity<JsonNode> response = restTemplate.getForEntity(newscatcherUrl, JsonNode.class);
        JsonNode responseBody = response.getBody();
        assert responseBody != null;
        log.info("Api response : {} with status {}", response.getStatusCode(), responseBody.get("status").asText("NO STATUS FIELD"));

        final NewsResponse newsResponse = objectMapper.convertValue(responseBody, NewsResponse.class);

        Optional.ofNullable(responseBody.get("user_input")).ifPresent(userInput ->
                newsResponse.setFrom(userInput.get("from").asText("1900-01-01 00:00:00")));

        Optional.ofNullable(responseBody.get("total_hits")).ifPresent(totalHits ->
                newsResponse.setTotalHits(totalHits.asInt(-1)));

        Optional.ofNullable(responseBody.get("total_pages")).ifPresent(totalPages ->
                newsResponse.setTotalPages(totalPages.asInt(-1)));

        Optional.ofNullable(responseBody.get("page")).ifPresent(curPage ->
                newsResponse.setCurrentPage(curPage.asInt(-1)));

        Optional.ofNullable(responseBody.get("page_size")).ifPresent(curPageSize ->
                newsResponse.setPageSize(curPageSize.asInt(-1)));

        final ApiResponse<NewsResponse> retVal = new ApiResponse<>();

        retVal.setStatus(response.getStatusCode());
        retVal.setSubCode(ApiResponseSubCodes.WS_001);
        retVal.setMessage(ApiResponseSubCodes.WS_001.getDescription());
        retVal.setData(newsResponse);

        log.info("END :: getLatestHeadlines with status code {}", retVal.getSubCode());
        return ResponseEntity.ok(retVal);
    }

}
