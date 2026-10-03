package com.j11a.argus.source;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.j11a.argus.config.WebMvcConfig;
import com.j11a.argus.feed.FeedSummary;
import com.j11a.argus.feed.Topic;
import com.j11a.argus.security.SecurityConfig;
import com.j11a.argus.testsupport.AdminKeys;
import com.j11a.argus.web.error.ApiException;
import com.j11a.argus.web.error.ErrorCode;
import com.j11a.argus.web.error.GlobalExceptionHandler;
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
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

@WebMvcTest(SourceController.class)
@Import({SecurityConfig.class, GlobalExceptionHandler.class, WebMvcConfig.class, AdminKeys.SliceProperties.class})
class SourceControllerTest {

    private static final String SOURCES = "/news/v2/sources";

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private SourceQueryService queryService;

    @MockitoBean
    private SourceService sourceService;

    private static MockHttpServletRequestBuilder adminPatch(String path, String body) {
        return patch(path).header(AdminKeys.HEADER, AdminKeys.VALID)
                .contentType(MediaType.APPLICATION_JSON)
                .content(body);
    }

    private static SourceResponse source(long id) {
        return new SourceResponse(
                id,
                "bbc.co.uk",
                "BBC",
                "https://www.bbc.co.uk/",
                "GB",
                12L,
                List.of(new FeedSummary(1L, "BBC News", Topic.NEWS)));
    }

    @Test
    void listReturnsPagedShape() throws Exception {
        when(queryService.list(0, 20)).thenReturn(
                new PageImpl<>(List.of(source(1L)), PageRequest.of(0, 20), 1));

        mockMvc.perform(get(SOURCES))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content[0].id").value(1L))
                .andExpect(jsonPath("$.content[0].key").value("bbc.co.uk"))
                .andExpect(jsonPath("$.content[0].name").value("BBC"))
                .andExpect(jsonPath("$.content[0].homepage").value("https://www.bbc.co.uk/"))
                .andExpect(jsonPath("$.content[0].country").value("GB"))
                .andExpect(jsonPath("$.content[0].articleCount").value(12L))
                .andExpect(jsonPath("$.content[0].feeds[0].id").value(1L))
                .andExpect(jsonPath("$.content[0].feeds[0].name").value("BBC News"))
                .andExpect(jsonPath("$.page.totalElements").value(1))
                .andExpect(jsonPath("$.page.size").value(20));
    }

    @Test
    void listOffsetBeyondIntRangeIsRejected() throws Exception {
        mockMvc.perform(get(SOURCES).param("page", "100000000").param("size", "100"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"))
                .andExpect(jsonPath("$.errors[0].field").value("page"));
        verifyNoInteractions(queryService);
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
    void listBadPagingIsRejected(String name, String value) throws Exception {
        mockMvc.perform(get(SOURCES).param(name, value))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"))
                .andExpect(jsonPath("$.errors[0].field").value(name));
        verifyNoInteractions(queryService);
    }

    @Test
    void getReturnsSource() throws Exception {
        when(queryService.get(1L)).thenReturn(source(1L));

        mockMvc.perform(get(SOURCES + "/1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(1L))
                .andExpect(jsonPath("$.name").value("BBC"));
    }

    @Test
    void getUnknownSourceIs404() throws Exception {
        when(queryService.get(99L)).thenThrow(
                new ApiException(ErrorCode.SOURCE_NOT_FOUND, "Source 99 does not exist."));

        mockMvc.perform(get(SOURCES + "/99"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("SOURCE_NOT_FOUND"));
    }

    @Test
    void patchWithoutKeyIs401() throws Exception {
        mockMvc.perform(patch(SOURCES + "/1")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"New BBC\"}"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("ADMIN_KEY_REQUIRED"));
        verifyNoInteractions(sourceService);
    }

    @Test
    void patchWithUnreadableBodyIs400() throws Exception {
        mockMvc.perform(adminPatch(SOURCES + "/1", ""))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("BAD_REQUEST"));
        verifyNoInteractions(sourceService);
    }

    @Test
    void patchValidUpdatesAndReturnsUpdatedSource() throws Exception {
        SourceResponse updated = source(1L);
        when(sourceService.patch(eq(1L), any())).thenReturn(updated);

        mockMvc.perform(adminPatch(SOURCES + "/1", "{\"name\":\"New BBC\",\"country\":\"ca\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(1L))
                .andExpect(jsonPath("$.name").value("BBC"));

        verify(sourceService).patch(eq(1L), any());
    }

    @Test
    void patchUnknownSourceIs404() throws Exception {
        when(sourceService.patch(eq(99L), any())).thenThrow(
                new ApiException(ErrorCode.SOURCE_NOT_FOUND, "Source 99 does not exist."));

        mockMvc.perform(adminPatch(SOURCES + "/99", "{\"name\":\"New BBC\"}"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("SOURCE_NOT_FOUND"));
    }
}
