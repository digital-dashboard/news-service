package com.j11a.argus.article;

import com.j11a.argus.web.error.ApiException;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
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
            @RequestParam(defaultValue = "0") @Min(0) int page,
            @RequestParam(defaultValue = "20") @Min(1) @Max(MAX_PAGE_SIZE) int size) {
        if ((long) page * size > Integer.MAX_VALUE) {
            throw ApiException.validationFailed("page", PAGE_TOO_DEEP);
        }
        return new PagedModel<>(articles.list(page, size));
    }
}
