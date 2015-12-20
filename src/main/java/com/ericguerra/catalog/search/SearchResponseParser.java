package com.ericguerra.catalog.search;

import com.fasterxml.jackson.databind.JsonNode;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Turns an Elasticsearch response into the storefront API shape. */
public final class SearchResponseParser {
    public record Hit(String sku, String name, String nameHighlighted, String brand, long priceCents, long listPriceCents,
                      boolean onSale, boolean inStock, Double rating, int reviewCount, String imageUrl, String category) {}

    public record Bucket(String value, String label, long count, boolean selected) {}

    public record Facet(String name, String label, List<Bucket> buckets) {}

    public record Result(long total, int page, int size, List<Hit> hits, List<Facet> facets, long tookMs) {}

    private SearchResponseParser() {}

    public static Result parse(JsonNode resp, SearchRequest r) {
        List<Hit> hits = new ArrayList<>();
        for (JsonNode h : resp.path("hits").path("hits")) {
            JsonNode s = h.path("_source");
            JsonNode hl = h.path("highlight").path("name");
            List<String> paths = new ArrayList<>();
            s.path("category").path("path").forEach(p -> paths.add(p.asText()));
            hits.add(new Hit(s.path("sku").asText(), s.path("name").asText(),
                    hl.isArray() && !hl.isEmpty() ? hl.get(0).asText() : null,
                    s.path("brand").asText(null), s.path("price_cents").asLong(), s.path("list_price_cents").asLong(),
                    s.path("on_sale").asBoolean(), s.path("in_stock").asBoolean(),
                    s.path("rating").isNumber() ? s.path("rating").asDouble() : null, s.path("review_count").asInt(),
                    s.path("image_url").asText(null), paths.isEmpty() ? null : paths.get(paths.size() - 1)));
        }

        JsonNode aggs = resp.path("aggregations");
        List<Facet> facets = new ArrayList<>();
        facets.add(new Facet("brand", "Brand", terms(aggs.path("brand").path("values"), r.brands())));
        facets.add(new Facet("category", "Category", categoryBuckets(aggs.path("category").path("values"), r.category())));
        facets.add(new Facet("price", "Price", priceBuckets(aggs.path("price").path("values"), r)));
        aggs.fieldNames().forEachRemaining(name -> {
            if (!name.startsWith("attr:")) return;
            String attr = name.substring(5);
            List<Bucket> buckets = terms(aggs.path(name).path("values").path("named").path("values"), r.attributes().getOrDefault(attr, List.of()));
            if (!buckets.isEmpty()) facets.add(new Facet(name, Character.toUpperCase(attr.charAt(0)) + attr.substring(1), buckets));
        });
        return new Result(resp.path("hits").path("total").path("value").asLong(), r.page(), r.size(), hits, facets, resp.path("took").asLong());
    }

    private static List<Bucket> terms(JsonNode agg, List<String> selected) {
        List<Bucket> out = new ArrayList<>();
        for (JsonNode b : agg.path("buckets")) {
            String key = b.path("key").asText();
            out.add(new Bucket(key, key, b.path("doc_count").asLong(), selected.contains(key)));
        }
        return out;
    }

    /** Shows only the next level below the current category, like a drill-down tree. */
    private static List<Bucket> categoryBuckets(JsonNode agg, String current) {
        String prefix = current == null || current.isBlank() ? "" : current.replaceAll("^/+|/+$", "") + "/";
        int depth = prefix.isEmpty() ? 0 : prefix.split("/").length;
        Map<String, Bucket> out = new LinkedHashMap<>();
        for (JsonNode b : agg.path("buckets")) {
            String key = b.path("key").asText();
            if (!key.startsWith(prefix) || key.split("/").length != depth + 1) continue;
            out.put(key, new Bucket(key, key.substring(key.lastIndexOf('/') + 1), b.path("doc_count").asLong(), false));
        }
        return new ArrayList<>(out.values());
    }

    private static List<Bucket> priceBuckets(JsonNode agg, SearchRequest r) {
        List<Bucket> out = new ArrayList<>();
        for (JsonNode b : agg.path("buckets")) {
            long count = b.path("doc_count").asLong();
            if (count == 0) continue;
            long from = b.path("from").asLong(0);
            Long to = b.has("to") ? b.path("to").asLong() : null;
            String label = to == null ? "$" + from / 100 + "+" : "$" + from / 100 + " – $" + to / 100;
            boolean selected = r.minPriceCents() != null && r.minPriceCents() == from && java.util.Objects.equals(r.maxPriceCents(), to);
            out.add(new Bucket(from + "-" + (to == null ? "" : to), label, count, selected));
        }
        return out;
    }
}
