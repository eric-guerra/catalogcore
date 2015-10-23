package com.ericguerra.catalog.search;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.util.List;
import java.util.Map;

/**
 * Builds the Elasticsearch query DSL for a storefront search.
 *
 * Faceting uses the standard "post_filter" technique: each facet's aggregation is computed with
 * every filter <em>except its own</em>, so selecting "Brand A" still shows counts for Brand B
 * (multi-select facets) while the hits respect all filters.
 */
public final class SearchQueryBuilder {
    static final List<long[]> PRICE_BUCKETS = List.of(
            new long[]{0, 2500}, new long[]{2500, 5000}, new long[]{5000, 10000}, new long[]{10000, 25000}, new long[]{25000, Long.MAX_VALUE});

    private final ObjectMapper json;
    private final List<String> facetAttributes;

    public SearchQueryBuilder(ObjectMapper json, List<String> facetAttributes) {
        this.json = json;
        this.facetAttributes = List.copyOf(facetAttributes);
    }

    public ObjectNode build(SearchRequest r) {
        ObjectNode root = json.createObjectNode();
        root.put("from", r.page() * r.size());
        root.put("size", r.size());
        root.put("track_total_hits", true);
        root.set("query", scoredQuery(r));
        root.set("post_filter", bool(filtersExcept(r, null)));
        root.set("aggs", aggregations(r));
        root.set("sort", sort(r));
        ObjectNode highlight = root.putObject("highlight");
        highlight.putArray("pre_tags").add("<mark>");
        highlight.putArray("post_tags").add("</mark>");
        highlight.putObject("fields").putObject("name").put("number_of_fragments", 0);
        return root;
    }

    /** Text relevance plus business signals: in-stock, ratings and sale items get a gentle boost. */
    private ObjectNode scoredQuery(SearchRequest r) {
        ObjectNode base;
        if (r.hasQuery()) {
            base = json.createObjectNode();
            ObjectNode b = base.putObject("bool");
            ArrayNode should = b.putArray("should");
            ObjectNode mm = should.addObject().putObject("multi_match");
            mm.put("query", r.query());
            mm.put("type", "best_fields");
            mm.put("fuzziness", "AUTO");
            mm.put("prefix_length", 1);
            mm.putArray("fields").add("name^4").add("brand^3").add("category.text^2").add("description").add("attributes.text");
            // Exact SKU or phrase matches should dominate.
            should.addObject().putObject("term").putObject("sku").put("value", r.query().trim().toUpperCase()).put("boost", 20);
            ObjectNode phrase = should.addObject().putObject("match_phrase").putObject("name");
            phrase.put("query", r.query());
            phrase.put("boost", 6);
            b.put("minimum_should_match", 1);
        } else {
            base = json.createObjectNode();
            base.putObject("match_all");
        }

        ObjectNode fs = json.createObjectNode();
        ObjectNode functionScore = fs.putObject("function_score");
        functionScore.set("query", base);
        ArrayNode functions = functionScore.putArray("functions");
        functions.addObject().put("weight", 1.5).putObject("filter").putObject("term").put("in_stock", true);
        functions.addObject().put("weight", 1.1).putObject("filter").putObject("term").put("on_sale", true);
        ObjectNode rating = functions.addObject().putObject("field_value_factor");
        rating.put("field", "rating");
        rating.put("modifier", "log1p");
        rating.put("missing", 3);
        functionScore.put("score_mode", "multiply");
        functionScore.put("boost_mode", "multiply");
        return fs;
    }

    /** All active filters, optionally leaving one facet out (for that facet's own counts). */
    ArrayNode filtersExcept(SearchRequest r, String excluded) {
        ArrayNode filters = json.createArrayNode();
        filters.addObject().putObject("term").put("active", true);
        if (r.category() != null && !r.category().isBlank() && !"category".equals(excluded)) {
            filters.addObject().putObject("term").put("category.path", r.category().replaceAll("^/+|/+$", ""));
        }
        if (!r.brands().isEmpty() && !"brand".equals(excluded)) {
            ArrayNode terms = filters.addObject().putObject("terms").putArray("brand.keyword");
            r.brands().forEach(terms::add);
        }
        if ((r.minPriceCents() != null || r.maxPriceCents() != null) && !"price".equals(excluded)) {
            ObjectNode range = filters.addObject().putObject("range").putObject("price_cents");
            if (r.minPriceCents() != null) range.put("gte", r.minPriceCents());
            if (r.maxPriceCents() != null) range.put("lte", r.maxPriceCents());
        }
        if (r.inStockOnly()) filters.addObject().putObject("term").put("in_stock", true);
        if (r.onSaleOnly()) filters.addObject().putObject("term").put("on_sale", true);
        for (Map.Entry<String, List<String>> attr : r.attributes().entrySet()) {
            if (attr.getValue().isEmpty() || ("attr:" + attr.getKey()).equals(excluded)) continue;
            ObjectNode nested = filters.addObject().putObject("nested");
            nested.put("path", "attrs");
            ArrayNode must = nested.putObject("query").putObject("bool").putArray("filter");
            must.addObject().putObject("term").put("attrs.name", attr.getKey());
            ArrayNode values = must.addObject().putObject("terms").putArray("attrs.value");
            attr.getValue().forEach(values::add);
        }
        return filters;
    }

    private ObjectNode bool(ArrayNode filters) {
        ObjectNode q = json.createObjectNode();
        q.putObject("bool").set("filter", filters);
        return q;
    }

    private ObjectNode aggregations(SearchRequest r) {
        ObjectNode aggs = json.createObjectNode();

        ObjectNode brand = facet(aggs, "brand", r);
        brand.putObject("aggs").putObject("values").putObject("terms").put("field", "brand.keyword").put("size", 30);

        ObjectNode category = facet(aggs, "category", r);
        category.putObject("aggs").putObject("values").putObject("terms").put("field", "category.path").put("size", 50);

        ObjectNode price = facet(aggs, "price", r);
        ArrayNode ranges = price.putObject("aggs").putObject("values").putObject("range").put("field", "price_cents").putArray("ranges");
        for (long[] b : PRICE_BUCKETS) {
            ObjectNode range = ranges.addObject().put("from", b[0]);
            if (b[1] != Long.MAX_VALUE) range.put("to", b[1]);
        }

        for (String attr : facetAttributes) {
            ObjectNode f = facet(aggs, "attr:" + attr, r);
            ObjectNode nested = f.putObject("aggs").putObject("values");
            nested.putObject("nested").put("path", "attrs");
            ObjectNode named = nested.putObject("aggs").putObject("named");
            named.putObject("filter").putObject("term").put("attrs.name", attr);
            named.putObject("aggs").putObject("values").putObject("terms").put("field", "attrs.value").put("size", 30);
        }
        return aggs;
    }

    /** A filter aggregation wrapping the facet, scoped by all filters except its own. */
    private ObjectNode facet(ObjectNode aggs, String name, SearchRequest r) {
        ObjectNode facet = aggs.putObject(name);
        facet.set("filter", bool(filtersExcept(r, name)));
        return facet;
    }

    private ArrayNode sort(SearchRequest r) {
        ArrayNode sort = json.createArrayNode();
        switch (r.sort()) {
            case PRICE_ASC -> sort.addObject().putObject("price_cents").put("order", "asc");
            case PRICE_DESC -> sort.addObject().putObject("price_cents").put("order", "desc");
            case RATING -> sort.addObject().putObject("rating").put("order", "desc").put("missing", "_last");
            case NEWEST -> sort.addObject().putObject("updated_at").put("order", "desc");
            case RELEVANCE -> sort.add("_score");
        }
        sort.addObject().putObject("sku").put("order", "asc"); // deterministic tie-break for stable paging
        return sort;
    }

    /** Autocomplete: edge-ngram field for prefix matches across names and brands. */
    public ObjectNode suggest(String prefix, int size) {
        ObjectNode root = json.createObjectNode();
        root.put("size", Math.min(10, Math.max(1, size)));
        root.putArray("_source").add("sku").add("name").add("brand").add("category.path");
        ObjectNode b = root.putObject("query").putObject("bool");
        b.putArray("filter").addObject().putObject("term").put("active", true);
        ObjectNode mm = b.putArray("must").addObject().putObject("multi_match");
        mm.put("query", prefix);
        mm.put("type", "bool_prefix");
        mm.putArray("fields").add("name.autocomplete").add("name.autocomplete._2gram").add("brand.autocomplete");
        return root;
    }
}
