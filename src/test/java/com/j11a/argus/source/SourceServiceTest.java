package com.j11a.argus.source;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.j11a.argus.web.error.ApiException;
import com.j11a.argus.web.error.ErrorCode;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.simple.JdbcClient;

class SourceServiceTest {

    private final JdbcClient jdbc = mock(JdbcClient.class);
    private final SourceRepository sources = mock(SourceRepository.class);
    private final Clock clock = Clock.fixed(Instant.parse("2026-10-03T12:00:00Z"), ZoneOffset.UTC);
    private final SourceQueryService queryService = mock(SourceQueryService.class);
    private final JdbcClient.StatementSpec spec = mock(JdbcClient.StatementSpec.class);

    private SourceService service;

    @BeforeEach
    void setUp() {
        service = new SourceService(jdbc, sources, clock, queryService);
        when(jdbc.sql(any(String.class))).thenReturn(spec);
        when(spec.param(any(String.class), any())).thenReturn(spec);
    }

    @Test
    void findOrCreateFindsOrInserts() {
        Source source = mock(Source.class);
        when(sources.findByKey("example.test")).thenReturn(Optional.of(source));

        Source result = service.findOrCreate("example.test", "https://example.test/");
        assertThat(result).isSameAs(source);
        verify(spec).update();
    }

    @Test
    void findOrCreateStoresOnlyTheRedactedSiteLink() {
        when(sources.findByKey("example.test")).thenReturn(Optional.of(mock(Source.class)));

        service.findOrCreate("example.test", "https://u:p@Example.test/home?token=SECRET#x");

        verify(spec).param("homepage", "https://example.test/home");
    }

    @Test
    void patchRedactsTheHomepageQuery() {
        when(spec.update()).thenReturn(1);
        when(queryService.get(10L)).thenReturn(
                new SourceResponse(10L, "h.example.test", "H", "https://h.example.test/p", null, 0L, List.of()));

        service.patch(10L, new PatchSourceRequest(null, "https://h.example.test/p?token=SECRET", null));

        verify(spec).param("homepage", "https://h.example.test/p");
    }

    @Test
    void findByIdDelegatesToRepository() {
        Source source = mock(Source.class);
        when(sources.findById(10L)).thenReturn(Optional.of(source));

        assertThat(service.findById(10L)).contains(source);
        assertThat(service.findById(99L)).isEmpty();
    }

    @Test
    void patchUpdatesFieldsAndReturnsResponse() {
        when(spec.update()).thenReturn(1);
        SourceResponse expected = new SourceResponse(10L, "bbc.co.uk", "BBC News", "https://bbc.co.uk", "GB", 5L, List.of());
        when(queryService.get(10L)).thenReturn(expected);

        PatchSourceRequest request = new PatchSourceRequest("  BBC News  ", "https://BBC.co.uk/#frag", "gb");
        SourceResponse actual = service.patch(10L, request);

        assertThat(actual).isEqualTo(expected);
        verify(spec).param("name", "BBC News");
        verify(spec).param("homepage", "https://bbc.co.uk/");
        verify(spec).param("country", "GB");
    }

    @Test
    void patchWithNullFieldsKeepsValuesAsNullInParams() {
        when(spec.update()).thenReturn(1);
        SourceResponse expected = new SourceResponse(10L, "bbc.co.uk", "BBC", null, null, 0L, List.of());
        when(queryService.get(10L)).thenReturn(expected);

        PatchSourceRequest request = new PatchSourceRequest("BBC", null, null);
        SourceResponse actual = service.patch(10L, request);

        assertThat(actual).isEqualTo(expected);
        verify(spec).param("name", "BBC");
        verify(spec).param("homepage", null);
        verify(spec).param("country", null);
    }

    @Test
    void patchWithNoFieldsGives400() {
        PatchSourceRequest request = new PatchSourceRequest(null, null, null);
        assertThatThrownBy(() -> service.patch(10L, request))
                .isInstanceOfSatisfying(ApiException.class, e -> {
                    assertThat(e.code()).isEqualTo(ErrorCode.VALIDATION_FAILED);
                    assertThat(e.properties().get("errors").toString()).contains("request");
                });
    }

    @Test
    void patchBlankNameGives400OnTheNameField() {
        PatchSourceRequest request = new PatchSourceRequest("   ", null, null);
        assertThatThrownBy(() -> service.patch(10L, request))
                .isInstanceOfSatisfying(ApiException.class, e -> {
                    assertThat(e.code()).isEqualTo(ErrorCode.VALIDATION_FAILED);
                    assertThat(e.properties().get("errors").toString()).contains("name");
                });
    }

    @Test
    void patchInvalidCountryGives400OnTheCountryField() {
        PatchSourceRequest request = new PatchSourceRequest(null, null, "UK");
        assertThatThrownBy(() -> service.patch(10L, request))
                .isInstanceOfSatisfying(ApiException.class, e -> {
                    assertThat(e.code()).isEqualTo(ErrorCode.VALIDATION_FAILED);
                    assertThat(e.properties().get("errors").toString()).contains("country");
                });
    }

    @Test
    void patchUnknownSourceGives404() {
        when(spec.update()).thenReturn(0);
        PatchSourceRequest request = new PatchSourceRequest("BBC", null, null);

        assertThatThrownBy(() -> service.patch(99L, request))
                .isInstanceOfSatisfying(ApiException.class, e -> {
                    assertThat(e.code()).isEqualTo(ErrorCode.SOURCE_NOT_FOUND);
                });
    }
}
