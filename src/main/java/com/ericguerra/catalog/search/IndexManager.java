package com.ericguerra.catalog.search;

import com.ericguerra.catalog.domain.Product;
import com.ericguerra.catalog.domain.ProductRepository;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.ClassPathResource;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Owns the products index lifecycle. The storefront always queries the alias `products`;
 * a full reindex builds `products-<timestamp>` from the database, then atomically swaps the alias,
 * giving zero-downtime mapping changes.
 */
@Component
public class IndexManager {
    private static final Logger log = LoggerFactory.getLogger(IndexManager.class);
    private static final DateTimeFormatter STAMP = DateTimeFormatter.ofPattern("yyyyMMddHHmmss").withZone(ZoneOffset.UTC);

    private final ElasticsearchClient es;
    private final ProductRepository products;
    private final ObjectMapper json;
    private final String alias;

    public IndexManager(ElasticsearchClient es, ProductRepository products, ObjectMapper json,
                        @Value("${catalog.elasticsearch.alias:products}") String alias) {
        this.es = es;
        this.products = products;
        this.json = json;
        this.alias = alias;
    }

    public String alias() {
        return alias;
    }

    /** Creates the first index + alias if missing (called on startup). */
    public void ensureIndex() {
        if (es.exists("/_alias/" + alias)) return;
        String index = createIndex();
        es.request("POST", "/_aliases", Map.of("actions", List.of(Map.of("add", Map.of("index", index, "alias", alias)))));
        log.info("created {} -> {}", alias, index);
    }

    private String createIndex() {
        String index = alias + "-" + STAMP.format(Instant.now());
        try {
            JsonNode settings = json.readTree(new ClassPathResource("es/products-index.json").getInputStream());
            es.request("PUT", "/" + index, settings);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        return index;
    }

    /** Full rebuild from the database with an atomic alias swap. Returns the number of documents indexed. */
    @Transactional(readOnly = true)
    public long reindex() {
        String index = createIndex();
        long count = 0;
        int page = 0;
        List<Product> batch;
        do {
            batch = products.findAll(PageRequest.of(page++, 500, Sort.by("id"))).getContent();
            if (!batch.isEmpty()) {
                bulkUpsert(index, batch);
                count += batch.size();
            }
        } while (batch.size() == 500);

        es.request("POST", "/" + index + "/_refresh", null);
        List<Map<String, Object>> actions = new ArrayList<>();
        JsonNode current = es.exists("/_alias/" + alias) ? es.request("GET", "/_alias/" + alias, null) : json.createObjectNode();
        current.fieldNames().forEachRemaining(old -> actions.add(Map.of("remove", Map.of("index", old, "alias", alias))));
        actions.add(Map.of("add", Map.of("index", index, "alias", alias)));
        es.request("POST", "/_aliases", Map.of("actions", actions));
        current.fieldNames().forEachRemaining(old -> es.request("DELETE", "/" + old, null));
        log.info("reindexed {} products into {}", count, index);
        return count;
    }

    /** Bulk upsert; returns ids that failed. Uses the entity version as an external version so stale writes lose. */
    public List<Long> bulkUpsert(String index, List<Product> batch) {
        StringBuilder nd = new StringBuilder();
        for (Product p : batch) {
            nd.append(es.write(Map.of("index", Map.of("_index", index, "_id", String.valueOf(p.getId()),
                    "version", p.getVersion() + 1, "version_type", "external_gte")))).append('\n');
            nd.append(es.write(ProductDocument.from(p))).append('\n');
        }
        JsonNode resp = es.bulk(nd.toString());
        List<Long> failed = new ArrayList<>();
        if (resp.path("errors").asBoolean()) {
            for (int i = 0; i < batch.size(); i++) {
                JsonNode item = resp.path("items").path(i).path("index");
                int status = item.path("status").asInt();
                // 409 = an equal-or-newer version is already indexed: fine.
                if (status >= 300 && status != 409) failed.add(batch.get(i).getId());
            }
        }
        return failed;
    }

    public void delete(Long id) {
        try {
            es.request("DELETE", "/" + alias + "/_doc/" + id, null);
        } catch (ElasticsearchClient.EsException e) {
            if (e.status() != 404) throw e;
        }
    }
}
