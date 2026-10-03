package com.j11a.argus.ingest.dedup;

import java.util.List;
import java.util.Set;

@FunctionalInterface
public interface ExistingArticleLookup {
    /** One bulk read; never called with the homepage, root or batch-shared link keys. */
    List<ExistingArticle> find(Set<String> guidKeys, Set<String> linkKeys);
}
