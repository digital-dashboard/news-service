package com.j11a.argus.article;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class ArticleQueryService {

    private static final Sort NEWEST_FIRST = Sort.by(Sort.Direction.DESC, "effectiveAt")
            .and(Sort.by(Sort.Direction.DESC, "id"));

    private final ArticleRepository articles;

    public ArticleQueryService(ArticleRepository articles) {
        this.articles = articles;
    }

    @Transactional(readOnly = true)
    public Page<ArticleResponse> list(ArticleFilter filter, int page, int size) {
        Specification<Article> spec = Specification.allOf(
                ArticleSpecifications.topicIn(filter.topics()),
                ArticleSpecifications.countryIn(filter.countries()));
        return articles.findAll(spec, PageRequest.of(page, size, NEWEST_FIRST)).map(ArticleResponse::of);
    }
}
