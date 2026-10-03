package com.j11a.argus.article;

import com.j11a.argus.feed.Topic;
import com.j11a.argus.source.CountryCodes;
import com.j11a.argus.web.error.ApiException;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;
import org.jspecify.annotations.Nullable;
import org.springframework.data.web.PagedModel;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/articles")
public class ArticleController {

    static final int MAX_PAGE_SIZE = 100;
    // The row offset (page * size) must fit the int that the JPA query takes.
    private static final String PAGE_TOO_DEEP = "page is too large for this size";

    private final ArticleQueryService articles;

    public ArticleController(ArticleQueryService articles) {
        this.articles = articles;
    }

    // Spring Data's Pageable resolver would clamp an oversized size instead of rejecting it.
    @GetMapping
    public PagedModel<ArticleResponse> list(
            @RequestParam(required = false) @Nullable List<Topic> topic,
            @RequestParam(required = false) @Nullable List<String> country,
            @RequestParam(defaultValue = "0") @Min(0) int page,
            @RequestParam(defaultValue = "20") @Min(1) @Max(MAX_PAGE_SIZE) int size) {
        if ((long) page * size > Integer.MAX_VALUE) {
            throw ApiException.validationFailed("page", PAGE_TOO_DEEP);
        }
        Set<Topic> topics = topic == null ? Set.of() : Set.copyOf(topic);
        Set<String> countries = normaliseCountries(country);
        ArticleFilter filter = new ArticleFilter(topics, countries);
        return new PagedModel<>(articles.list(filter, page, size));
    }

    private static Set<String> normaliseCountries(@Nullable List<String> countries) {
        if (countries == null) {
            return Set.of();
        }
        return countries.stream().map(CountryCodes::normalise).collect(Collectors.toUnmodifiableSet());
    }
}
