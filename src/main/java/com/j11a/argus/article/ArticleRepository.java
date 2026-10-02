package com.j11a.argus.article;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

public interface ArticleRepository extends JpaRepository<Article, Long> {

    /** The source is fetched in the same query; the count query is written out so it joins nothing. */
    @EntityGraph(attributePaths = "source")
    @Query(value = "select a from Article a", countQuery = "select count(a) from Article a")
    Page<Article> findPage(Pageable pageable);
}
