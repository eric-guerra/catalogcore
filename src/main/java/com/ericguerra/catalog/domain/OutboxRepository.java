package com.ericguerra.catalog.domain;

import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;

public interface OutboxRepository extends JpaRepository<OutboxEvent, Long> {
    @Query("select e from OutboxEvent e where e.attempts < :maxAttempts order by e.id")
    List<OutboxEvent> nextBatch(@Param("maxAttempts") int maxAttempts, Pageable page);

    long countByAttemptsGreaterThanEqual(int attempts);
}
