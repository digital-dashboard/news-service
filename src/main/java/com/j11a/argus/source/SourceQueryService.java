package com.j11a.argus.source;

import com.j11a.argus.feed.Feed;
import com.j11a.argus.feed.FeedRepository;
import com.j11a.argus.feed.FeedSummary;
import com.j11a.argus.web.error.ApiException;
import com.j11a.argus.web.error.ErrorCode;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class SourceQueryService {

    private static final String COUNT_ARTICLES = """
            SELECT source_id, count(*) AS count
            FROM article
            WHERE source_id = ANY(:ids)
            GROUP BY source_id
            """;

    private final SourceRepository sources;
    private final FeedRepository feeds;
    private final JdbcClient jdbc;

    public SourceQueryService(SourceRepository sources, FeedRepository feeds, JdbcClient jdbc) {
        this.sources = sources;
        this.feeds = feeds;
        this.jdbc = jdbc;
    }

    @Transactional(readOnly = true)
    public Page<SourceResponse> list(int page, int size) {
        PageRequest pageRequest = PageRequest.of(page, size, Sort.by("id").ascending());
        Page<Source> sourcePage = sources.findAll(pageRequest);
        List<SourceResponse> responses = toResponses(sourcePage.getContent());
        return new PageImpl<>(responses, pageRequest, sourcePage.getTotalElements());
    }

    @Transactional(readOnly = true)
    public SourceResponse get(long id) {
        Source source = sources.findById(id).orElseThrow(() -> notFound(id));
        return toResponses(List.of(source)).getFirst();
    }

    private List<SourceResponse> toResponses(List<Source> sourceList) {
        if (sourceList.isEmpty()) {
            return List.of();
        }
        List<Long> ids = sourceList.stream().map(Source::getId).toList();
        Map<Long, List<FeedSummary>> feedsBySource = feeds.findBySourceIdInOrderByIdAsc(ids).stream()
                .collect(Collectors.groupingBy(
                        f -> f.getSource().getId(),
                        LinkedHashMap::new,
                        Collectors.mapping(FeedSummary::of, Collectors.toList())));

        Map<Long, Long> countsBySource = jdbc.sql(COUNT_ARTICLES)
                .param("ids", ids.toArray(Long[]::new))
                .query((rs, rowNum) -> Map.entry(rs.getLong("source_id"), rs.getLong("count")))
                .stream()
                .collect(Collectors.toMap(Map.Entry::getKey, Map.Entry::getValue));

        return sourceList.stream()
                .map(source -> new SourceResponse(
                        source.getId(),
                        source.getKey(),
                        source.getName(),
                        source.getHomepageUrl(),
                        source.getCountry(),
                        countsBySource.getOrDefault(source.getId(), 0L),
                        feedsBySource.getOrDefault(source.getId(), List.of())))
                .toList();
    }

    private static ApiException notFound(long id) {
        return new ApiException(ErrorCode.SOURCE_NOT_FOUND, "Source " + id + " does not exist.");
    }
}
