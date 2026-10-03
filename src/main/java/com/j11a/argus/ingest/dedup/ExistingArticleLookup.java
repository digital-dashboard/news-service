package com.j11a.argus.ingest.dedup;

import java.util.List;
import java.util.Set;

@FunctionalInterface
public interface ExistingArticleLookup {
    /**
     * One bulk read; the resolver calls it at most once per batch. linkKeys never contains the homepage, root or
     * batch-shared links. GUID keys are not filtered.
     */
    List<ExistingArticle> find(Set<String> guidKeys, Set<String> linkKeys);
}
