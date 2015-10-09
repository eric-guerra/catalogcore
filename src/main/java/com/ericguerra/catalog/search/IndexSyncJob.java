package com.ericguerra.catalog.search;

import com.ericguerra.catalog.domain.OutboxEvent;
import com.ericguerra.catalog.domain.OutboxRepository;
import com.ericguerra.catalog.domain.Product;
import com.ericguerra.catalog.domain.ProductRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.data.domain.PageRequest;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/** Drains the outbox into Elasticsearch. Coalesces repeated events for the same product. */
@Component
public class IndexSyncJob {
    private static final Logger log = LoggerFactory.getLogger(IndexSyncJob.class);
    static final int MAX_ATTEMPTS = 10;

    private final OutboxRepository outbox;
    private final ProductRepository products;
    private final IndexManager index;
    private final boolean enabled;

    public IndexSyncJob(OutboxRepository outbox, ProductRepository products, IndexManager index,
                        @Value("${catalog.index-sync.enabled:true}") boolean enabled) {
        this.outbox = outbox;
        this.products = products;
        this.index = index;
        this.enabled = enabled;
    }

    @EventListener(ApplicationReadyEvent.class)
    public void init() {
        if (enabled) index.ensureIndex();
    }

    @Scheduled(fixedDelayString = "${catalog.index-sync.delay-ms:1000}")
    public void scheduled() {
        if (enabled) drain();
    }

    /** Processes one batch; returns how many outbox rows were completed. */
    @Transactional
    public int drain() {
        List<OutboxEvent> events = outbox.nextBatch(MAX_ATTEMPTS, PageRequest.of(0, 500));
        if (events.isEmpty()) return 0;

        // Latest op per product wins (UPSERT then DELETE => DELETE).
        Map<Long, List<OutboxEvent>> byProduct = new LinkedHashMap<>();
        events.forEach(e -> byProduct.computeIfAbsent(e.getProductId(), k -> new ArrayList<>()).add(e));

        Set<Long> upsertIds = byProduct.entrySet().stream()
                .filter(en -> en.getValue().get(en.getValue().size() - 1).getOp() == OutboxEvent.Op.UPSERT)
                .map(Map.Entry::getKey).collect(Collectors.toSet());
        List<Product> toIndex = products.findAllById(upsertIds);
        Set<Long> missing = upsertIds.stream().filter(id -> toIndex.stream().noneMatch(p -> p.getId().equals(id))).collect(Collectors.toSet());

        Set<Long> failed = new java.util.HashSet<>();
        String err = null;
        try {
            if (!toIndex.isEmpty()) failed.addAll(index.bulkUpsert(index.alias(), toIndex));
            for (Map.Entry<Long, List<OutboxEvent>> en : byProduct.entrySet()) {
                OutboxEvent last = en.getValue().get(en.getValue().size() - 1);
                if (last.getOp() == OutboxEvent.Op.DELETE || missing.contains(en.getKey())) index.delete(en.getKey());
            }
        } catch (RuntimeException e) {
            err = e.getMessage();
            failed.addAll(byProduct.keySet());
            log.warn("index sync failed for {} products: {}", byProduct.size(), err);
        }

        int done = 0;
        for (Map.Entry<Long, List<OutboxEvent>> en : byProduct.entrySet()) {
            if (failed.contains(en.getKey())) {
                String reason = err == null ? "bulk item rejected" : err;
                en.getValue().forEach(e -> e.failed(reason));
            } else {
                outbox.deleteAll(en.getValue());
                done += en.getValue().size();
            }
        }
        return done;
    }
}
