package com.ericguerra.catalog.search;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class SearchQueryBuilderTest {
    private final ObjectMapper json = new ObjectMapper();
    private final SearchQueryBuilder builder = new SearchQueryBuilder(json, List.of("color", "size"));

    private static SearchRequest req(String q, List<String> brands, Map<String, List<String>> attrs, Long min, Long max) {
        return new SearchRequest(q, "Outdoors/Camping", brands, attrs, min, max, true, false, SearchRequest.Sort.PRICE_ASC, 2, 20);
    }

    @Test
    void pagingAndSort() {
        JsonNode q = builder.build(req("tent", List.of(), Map.of(), null, null));
        assertEquals(40, q.get("from").asInt());
        assertEquals(20, q.get("size").asInt());
        assertEquals("asc", q.at("/sort/0/price_cents/order").asText());
        assertEquals("sku", q.at("/sort/1").fieldNames().next(), "stable tie-break");
    }

    @Test
    void textQueryUsesFuzzyMultiMatchSkuAndPhraseBoosts() {
        JsonNode should = builder.build(req("ridge tent", List.of(), Map.of(), null, null)).at("/query/function_score/query/bool/should");
        assertEquals("AUTO", should.at("/0/multi_match/fuzziness").asText());
        assertEquals("name^4", should.at("/0/multi_match/fields/0").asText());
        assertEquals("RIDGE TENT", should.at("/1/term/sku/value").asText());
        assertEquals("ridge tent", should.at("/2/match_phrase/name/query").asText());
    }

    @Test
    void browseWithoutQueryIsMatchAll() {
        var r = new SearchRequest(" ", null, null, null, null, null, false, false, null, 0, 0);
        JsonNode q = builder.build(r);
        assertTrue(q.at("/query/function_score/query/match_all").isObject());
        assertEquals(24, q.get("size").asInt(), "default page size");
    }

    @Test
    void postFilterContainsAllFilters() {
        JsonNode filters = builder.build(req("tent", List.of("Ridgeline", "Summit"), Map.of("color", List.of("green")), 5000L, 20000L))
                .at("/post_filter/bool/filter");
        String s = filters.toString();
        assertTrue(s.contains("\"active\":true"));
        assertTrue(s.contains("\"category.path\":\"Outdoors/Camping\""));
        assertTrue(s.contains("\"brand.keyword\":[\"Ridgeline\",\"Summit\"]"));
        assertTrue(s.contains("\"gte\":5000") && s.contains("\"lte\":20000"));
        assertTrue(s.contains("\"in_stock\":true"));
        assertTrue(s.contains("\"attrs.name\":\"color\"") && s.contains("\"attrs.value\":[\"green\"]"));
    }

    @Test
    void eachFacetExcludesItsOwnFilterForMultiSelect() {
        JsonNode aggs = builder.build(req("tent", List.of("Ridgeline"), Map.of("color", List.of("green")), 5000L, null)).get("aggs");
        String brandScope = aggs.at("/brand/filter").toString();
        assertFalse(brandScope.contains("brand.keyword"), "brand counts ignore the brand filter");
        assertTrue(brandScope.contains("attrs.value"), "but respect other filters");

        String colorScope = aggs.at("/attr:color/filter").toString();
        assertFalse(colorScope.contains("\"attrs.name\":\"color\""));
        assertTrue(colorScope.contains("brand.keyword"));

        assertFalse(aggs.at("/price/filter").toString().contains("price_cents"));
        assertEquals(5, aggs.at("/price/aggs/values/range/ranges").size());
        assertTrue(aggs.has("attr:size"));
    }

    @Test
    void requestNormalization() {
        var r = new SearchRequest("x", null, null, null, 9000L, 1000L, false, false, null, 9999, 500);
        assertEquals(1000L, r.minPriceCents());
        assertEquals(9000L, r.maxPriceCents());
        assertEquals(SearchRequest.MAX_SIZE, r.size());
        assertTrue((long) (r.page() + 1) * r.size() <= SearchRequest.MAX_WINDOW, "deep paging is capped");
    }

    @Test
    void suggestUsesSearchAsYouType() {
        JsonNode s = builder.suggest("rid", 50);
        assertEquals(10, s.get("size").asInt());
        assertEquals("bool_prefix", s.at("/query/bool/must/0/multi_match/type").asText());
    }
}
