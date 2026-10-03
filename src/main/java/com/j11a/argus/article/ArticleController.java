package com.j11a.argus.article;

import com.j11a.argus.feed.Topic;
import com.j11a.argus.source.CountryCodes;
import com.j11a.argus.web.PageParams;
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
            @RequestParam(defaultValue = "20") @Min(1) @Max(PageParams.MAX_PAGE_SIZE) int size) {
        PageParams.requireReachable(page, size);
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
