package com.ericguerra.catalog.domain;

import jakarta.persistence.*;

import java.time.Instant;

/**
 * Transactional outbox: written in the same transaction as the product change, then drained
 * by the index sync job. The search index can lag but can never miss an update.
 */
@Entity
@Table(name = "index_outbox")
public class OutboxEvent {
    public enum Op { UPSERT, DELETE }

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "product_id", nullable = false)
    private Long productId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 10)
    private Op op;

    @Column(nullable = false)
    private int attempts;

    @Column(name = "last_error", length = 500)
    private String lastError;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt = Instant.now();

    protected OutboxEvent() {}

    public OutboxEvent(Long productId, Op op) {
        this.productId = productId;
        this.op = op;
    }

    public void failed(String error) {
        attempts++;
        lastError = error == null ? null : error.substring(0, Math.min(500, error.length()));
    }

    public Long getId() { return id; }
    public Long getProductId() { return productId; }
    public Op getOp() { return op; }
    public int getAttempts() { return attempts; }
    public String getLastError() { return lastError; }
    public Instant getCreatedAt() { return createdAt; }
}
