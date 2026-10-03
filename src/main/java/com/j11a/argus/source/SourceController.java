package com.j11a.argus.source;

import com.j11a.argus.web.error.ApiException;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import org.springframework.data.web.PagedModel;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/sources")
public class SourceController {

    static final int MAX_PAGE_SIZE = 100;
    private static final String PAGE_TOO_DEEP = "page is too large for this size";

    private final SourceQueryService queryService;
    private final SourceService sourceService;

    public SourceController(SourceQueryService queryService, SourceService sourceService) {
        this.queryService = queryService;
        this.sourceService = sourceService;
    }

    @GetMapping
    public PagedModel<SourceResponse> list(
            @RequestParam(defaultValue = "0") @Min(0) int page,
            @RequestParam(defaultValue = "20") @Min(1) @Max(MAX_PAGE_SIZE) int size) {
        if ((long) page * size > Integer.MAX_VALUE) {
            throw ApiException.validationFailed("page", PAGE_TOO_DEEP);
        }
        return new PagedModel<>(queryService.list(page, size));
    }

    @GetMapping("/{id}")
    public SourceResponse get(@PathVariable long id) {
        return queryService.get(id);
    }

    @PatchMapping("/{id}")
    public SourceResponse patch(@PathVariable long id, @Valid @RequestBody PatchSourceRequest request) {
        if (request.isEmpty()) {
            throw ApiException.validationFailed("request", "at least one field must be provided");
        }
        if (request.name() != null && request.name().strip().isEmpty()) {
            throw ApiException.validationFailed("name", "must not be blank");
        }
        if (request.country() != null) {
            CountryCodes.normalise(request.country());
        }
        return sourceService.patch(id, request);
    }
}
