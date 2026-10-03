package com.j11a.argus.feed;

import java.util.List;
import java.util.Optional;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;

public interface FeedRepository extends JpaRepository<Feed, Long> {

    @EntityGraph(attributePaths = "source")
    Optional<Feed> findWithSourceById(Long id);

    @EntityGraph(attributePaths = "source")
    List<Feed> findByEnabledTrueOrderByIdAsc();

    @Override
    @EntityGraph(attributePaths = "source")
    Page<Feed> findAll(Pageable pageable);
}
