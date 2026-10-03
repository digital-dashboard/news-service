package com.j11a.argus.source;

import com.j11a.argus.web.PageParams;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import org.springframework.data.web.PagedModel;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/sources")
public class SourceController {

    private final SourceQueryService queryService;
    private final SourceService sourceService;
    private final SourceMerger merger;

    public SourceController(SourceQueryService queryService, SourceService sourceService, SourceMerger merger) {
        this.queryService = queryService;
        this.sourceService = sourceService;
        this.merger = merger;
    }

    @GetMapping
    public PagedModel<SourceResponse> list(
            @RequestParam(defaultValue = "0") @Min(0) int page,
            @RequestParam(defaultValue = "20") @Min(1) @Max(PageParams.MAX_PAGE_SIZE) int size) {
        PageParams.requireReachable(page, size);
        return new PagedModel<>(queryService.list(page, size));
    }

    @GetMapping("/{id}")
    public SourceResponse get(@PathVariable long id) {
        return queryService.get(id);
    }

    @PatchMapping("/{id}")
    public SourceResponse patch(@PathVariable long id, @Valid @RequestBody PatchSourceRequest request) {
        return sourceService.patch(id, request);
    }

    @PostMapping("/{id}/merge")
    public SourceMergeResponse merge(@PathVariable long id, @Valid @RequestBody MergeSourceRequest request) {
        return merger.merge(id, request.targetSourceId());
    }
}
