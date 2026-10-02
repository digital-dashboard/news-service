package com.j11a.argus.feed;

import java.util.Optional;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;

public interface FeedRepository extends JpaRepository<Feed, Long> {

    @EntityGraph(attributePaths = "source")
    Optional<Feed> findWithSourceById(Long id);
}
