package com.j11a.argus.article;

import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.j11a.argus.config.WebMvcConfig;
import com.j11a.argus.security.SecurityConfig;
import com.j11a.argus.source.SourceSummary;
import com.j11a.argus.testsupport.AdminKeys;
import com.j11a.argus.web.error.GlobalExceptionHandler;
import java.time.Instant;
import java.util.List;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

@WebMvcTest(ArticleController.class)
@Import({SecurityConfig.class, GlobalExceptionHandler.class, WebMvcConfig.class, AdminKeys.SliceProperties.class})
class ArticleControllerTest {

    private static final String ARTICLES = "/news/v2/articles";

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private ArticleQueryService articles;

    private static ArticleResponse article() {
        return new ArticleResponse(1, "Ferry service resumes", "Crossings restart.", null,
                "https://news.example.test/a", null, List.of("World"), Instant.parse("2026-10-02T10:00:00Z"),
                null, new SourceSummary(2, "example.test", "https://news.example.test", null));
    }

    @Test
    void pagedShapeCarriesContentAndAPageBlock() throws Exception {
        when(articles.list(0, 20)).thenReturn(new PageImpl<>(List.of(article()), PageRequest.of(0, 20), 41));

        mockMvc.perform(get(ARTICLES))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content[0].title").value("Ferry service resumes"))
                .andExpect(jsonPath("$.content[0].source.name").value("example.test"))
                .andExpect(jsonPath("$.page.size").value(20))
                .andExpect(jsonPath("$.page.number").value(0))
                .andExpect(jsonPath("$.page.totalElements").value(41))
                .andExpect(jsonPath("$.page.totalPages").value(3));
    }

    @Test
    void timestampsAreIso8601UtcStrings() throws Exception {
        when(articles.list(0, 20)).thenReturn(new PageImpl<>(List.of(article()), PageRequest.of(0, 20), 1));

        mockMvc.perform(get(ARTICLES))
                .andExpect(jsonPath("$.content[0].publishedAt").value("2026-10-02T10:00:00Z"));
    }

    @Test
    void defaultsArePageZeroSizeTwenty() throws Exception {
        when(articles.list(0, 20)).thenReturn(new PageImpl<>(List.of(), PageRequest.of(0, 20), 0));

        mockMvc.perform(get(ARTICLES)).andExpect(status().isOk());

        verify(articles).list(0, 20);
    }

    @Test
    void explicitPagingIsPassedThrough() throws Exception {
        when(articles.list(2, 100)).thenReturn(new PageImpl<>(List.of(), PageRequest.of(2, 100), 0));

        mockMvc.perform(get(ARTICLES).param("page", "2").param("size", "100")).andExpect(status().isOk());

        verify(articles).list(2, 100);
    }

    @Test
    void anOffsetBeyondTheIntRangeIsRejectedNamingThePage() throws Exception {
        mockMvc.perform(get(ARTICLES).param("page", "100000000").param("size", "100"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"))
                .andExpect(jsonPath("$.errors[0].field").value("page"))
                .andExpect(jsonPath("$.errors[0].message").isNotEmpty());

        verifyNoInteractions(articles);
    }

    @Test
    void anOffsetExactlyAtTheIntLimitIsStillAccepted() throws Exception {
        when(articles.list(21474836, 100)).thenReturn(new PageImpl<>(List.of(), PageRequest.of(0, 100), 0));

        mockMvc.perform(get(ARTICLES).param("page", "21474836").param("size", "100")).andExpect(status().isOk());
    }

    static Stream<Arguments> badPaging() {
        return Stream.of(
                Arguments.of("size", "101"),
                Arguments.of("size", "0"),
                Arguments.of("page", "-1"),
                Arguments.of("size", "abc"));
    }

    @ParameterizedTest(name = "{0}={1} is 400 VALIDATION_FAILED")
    @MethodSource("badPaging")
    void badPagingIsRejectedNamingTheParameter(String name, String value) throws Exception {
        mockMvc.perform(get(ARTICLES).param(name, value))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"))
                .andExpect(jsonPath("$.errors[0].field").value(name));

        verifyNoInteractions(articles);
    }
}
