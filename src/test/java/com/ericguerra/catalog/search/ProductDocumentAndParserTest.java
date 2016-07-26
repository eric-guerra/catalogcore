package com.ericguerra.catalog.search;

import com.ericguerra.catalog.domain.Product;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class ProductDocumentAndParserTest {
    @Test
    @SuppressWarnings("unchecked")
    void documentDenormalizesCategoryPriceAndAttributes() {
        Product p = new Product("TENT-2P", "Ridgeline 2P Tent", " Outdoors / Camping /Tents ", 34900);
        p.setSalePriceCents(27900L);
        p.setStockQty(0);
        p.setBrand("Ridgeline");
        p.getAttributes().put("Color", "green");

        Map<String, Object> doc = ProductDocument.from(p);
        Map<String, Object> category = (Map<String, Object>) doc.get("category");
        assertEquals(List.of("Outdoors", "Outdoors/Camping", "Outdoors/Camping/Tents"), category.get("path"));
        assertEquals("Tents", category.get("leaf"));
        assertEquals(27900L, doc.get("price_cents"));
        assertEquals(34900L, doc.get("list_price_cents"));
        assertEquals(true, doc.get("on_sale"));
        assertEquals(20L, doc.get("discount_pct"));
        assertEquals(false, doc.get("in_stock"));
        assertEquals(List.of(Map.of("name", "color", "value", "green")), doc.get("attrs"));
    }

    @Test
    void parserBuildsHitsAndDrillDownFacets() throws Exception {
        String resp = """
            {"took": 7, "hits": {"total": {"value": 2}, "hits": [
              {"_source": {"sku": "TENT-2P", "name": "Ridgeline 2P Tent", "brand": "Ridgeline", "price_cents": 27900,
                "list_price_cents": 34900, "on_sale": true, "in_stock": true, "rating": 4.6, "review_count": 31,
                "category": {"path": ["Outdoors", "Outdoors/Camping", "Outdoors/Camping/Tents"]}},
               "highlight": {"name": ["<mark>Ridgeline</mark> 2P Tent"]}}]},
             "aggregations": {
               "brand": {"values": {"buckets": [{"key": "Ridgeline", "doc_count": 5}, {"key": "Summit", "doc_count": 2}]}},
               "category": {"values": {"buckets": [
                 {"key": "Outdoors", "doc_count": 7}, {"key": "Outdoors/Camping", "doc_count": 7},
                 {"key": "Outdoors/Camping/Tents", "doc_count": 5}, {"key": "Outdoors/Camping/Stoves", "doc_count": 2}]}},
               "price": {"values": {"buckets": [{"from": 0, "to": 2500, "doc_count": 0}, {"from": 25000, "doc_count": 3}]}},
               "attr:color": {"values": {"named": {"values": {"buckets": [{"key": "green", "doc_count": 4}]}}}},
               "attr:size": {"values": {"named": {"values": {"buckets": []}}}}
             }}
            """;
        var req = new SearchRequest("ridge", "Outdoors/Camping", List.of("Ridgeline"), Map.of("color", List.of("green")),
                25000L, null, false, false, null, 0, 24);
        var result = SearchResponseParser.parse(new ObjectMapper().readTree(resp), req);

        assertEquals(2, result.total());
        assertEquals("<mark>Ridgeline</mark> 2P Tent", result.hits().get(0).nameHighlighted());
        assertEquals("Outdoors/Camping/Tents", result.hits().get(0).category());

        var brand = result.facets().get(0);
        assertTrue(brand.buckets().get(0).selected());
        assertFalse(brand.buckets().get(1).selected());

        var category = result.facets().get(1);
        assertEquals(List.of("Tents", "Stoves"), category.buckets().stream().map(SearchResponseParser.Bucket::label).toList(),
                "only the next level below the current category");

        var price = result.facets().get(2);
        assertEquals(1, price.buckets().size(), "empty ranges hidden");
        assertEquals("$250+", price.buckets().get(0).label());
        assertTrue(price.buckets().get(0).selected());

        assertEquals(4, result.facets().size(), "empty attribute facets are dropped");
        assertEquals("Color", result.facets().get(3).label());
    }
}
