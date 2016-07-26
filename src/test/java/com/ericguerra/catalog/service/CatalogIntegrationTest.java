package com.ericguerra.catalog.service;

import com.ericguerra.catalog.domain.OutboxEvent;
import com.ericguerra.catalog.domain.OutboxRepository;
import com.ericguerra.catalog.domain.ProductRepository;
import com.ericguerra.catalog.search.ElasticsearchClient;
import com.ericguerra.catalog.search.IndexSyncJob;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;

import java.io.StringReader;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest(properties = "catalog.index-sync.enabled=false")
class CatalogIntegrationTest {
    /** Records requests instead of talking to Elasticsearch; can be told to fail. */
    static class FakeEs extends ElasticsearchClient {
        final List<String> bulkBodies = new ArrayList<>();
        final List<String> deletes = new ArrayList<>();
        volatile boolean failNext;

        FakeEs(ObjectMapper json) {
            super("http://fake", json);
        }

        @Override
        public JsonNode bulk(String ndjson) {
            if (failNext) {
                failNext = false;
                throw new EsException(503, "cluster unavailable");
            }
            bulkBodies.add(ndjson);
            return new ObjectMapper().createObjectNode().put("errors", false);
        }

        @Override
        public JsonNode request(String method, String path, Object body) {
            if (method.equals("DELETE")) deletes.add(path);
            return new ObjectMapper().createObjectNode();
        }

        @Override
        public boolean exists(String path) {
            return true;
        }
    }

    @TestConfiguration
    static class Config {
        @Bean
        @Primary
        FakeEs fakeEs(ObjectMapper json) {
            return new FakeEs(json);
        }
    }

    @Autowired CatalogService catalog;
    @Autowired CsvImporter importer;
    @Autowired IndexSyncJob sync;
    @Autowired OutboxRepository outbox;
    @Autowired ProductRepository products;
    @Autowired FakeEs es;

    @BeforeEach
    void clean() {
        outbox.deleteAll();
        products.deleteAll();
        es.bulkBodies.clear();
        es.deletes.clear();
    }

    private static CatalogService.ProductInput input(String sku, long price, int stock) {
        return new CatalogService.ProductInput(sku, "Product " + sku, null, "Acme", "Home/Kitchen", price, null, stock, 4.0, 10, null, Map.of("Color", "red"));
    }

    @Test
    void writesGoThroughTheOutboxAndAreCoalesced() {
        catalog.upsert(input("MUG-1", 1200, 5));
        catalog.upsert(input("MUG-1", 1300, 5));
        catalog.upsert(input("PAN-1", 4500, 2));
        assertEquals(3, outbox.count());

        assertEquals(3, sync.drain());
        assertEquals(0, outbox.count());
        String body = es.bulkBodies.get(0);
        assertEquals(4, body.split("\n").length, "MUG-1 indexed once despite two updates");
        assertTrue(body.contains("\"price_cents\":1300"));
        assertTrue(body.contains("\"attrs\":[{\"name\":\"color\""), "attribute names are normalized");
    }

    @Test
    void stockChangesOnlyReindexWhenAvailabilityFlips() {
        catalog.upsert(input("PAN-2", 4500, 3));
        sync.drain();
        catalog.adjustStock("PAN-2", 2);
        assertEquals(0, outbox.count());
        catalog.adjustStock("PAN-2", 0);
        assertEquals(1, outbox.count());
    }

    @Test
    void deactivationDeletesFromIndex() {
        var p = catalog.upsert(input("OLD-1", 100, 1));
        catalog.deactivate("OLD-1");
        sync.drain();
        assertEquals(List.of("/products/_doc/" + p.getId()), es.deletes);
        assertTrue(es.bulkBodies.isEmpty(), "upsert followed by delete coalesces to delete");
    }

    @Test
    void failuresStayInOutboxWithAttempts() {
        catalog.upsert(input("FAIL-1", 100, 1));
        es.failNext = true;
        assertEquals(0, sync.drain());
        OutboxEvent e = outbox.findAll().get(0);
        assertEquals(1, e.getAttempts());
        assertTrue(e.getLastError().contains("cluster unavailable"));
        assertEquals(1, sync.drain(), "retried on next run");
    }

    @Test
    void csvImportValidatesRowsIndependently() throws Exception {
        String csv = """
            sku,name,category,price,sale_price,stock,brand,attr_color,attr_size
            tee-1,Logo Tee,Apparel/Shirts,19.99,,40,Acme,black,M
            TEE-2,Logo Tee Long Sleeve,Apparel/Shirts,"$1,024.50",900.00,3,Acme,white,L
            BAD SKU,Broken,Apparel,10,,1,,,
            TEE-3,Sale too high,Apparel,10,20,1,,,
            TEE-4,No price,Apparel,,,1,,,
            """;
        var report = importer.importCsv(new StringReader(csv));
        assertEquals(2, report.imported());
        assertEquals(3, report.errors().size());
        assertEquals(4, report.errors().get(0).line());
        assertTrue(report.errors().get(1).message().contains("salePriceCents"));
        assertTrue(report.errors().get(2).message().contains("price is required"));

        var tee2 = products.findBySku("TEE-2").orElseThrow();
        assertEquals(102450, tee2.getPriceCents());
        assertEquals(90000L, tee2.getSalePriceCents());
        assertEquals("L", tee2.getAttributes().get("size"));
        assertTrue(products.findBySku("TEE-1").isPresent(), "SKUs are upper-cased");
    }

    @Test
    void csvMissingColumnsIsRejected() {
        var e = assertThrows(IllegalArgumentException.class, () -> importer.importCsv(new StringReader("sku,name\nA,B\n")));
        assertEquals("missing required columns: category, price", e.getMessage());
    }
}
