package com.j11a.argus.article;

import org.jspecify.annotations.Nullable;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;

public interface ArticleRepository extends JpaRepository<Article, Long>, JpaSpecificationExecutor<Article> {

    @Override
    @EntityGraph(attributePaths = "source")
    Page<Article> findAll(@Nullable Specification<Article> spec, Pageable pageable);

    default Page<Article> findPage(Pageable pageable) {
        return findAll((Specification<Article>) null, pageable);
    }
}
