package com.ericguerra.catalog.search;

import java.util.List;
import java.util.Map;

/**
 * Storefront search parameters.
 *
 * @param query      free text (may be blank for browse)
 * @param category   category path prefix, e.g. "Outdoors/Camping"
 * @param brands     OR within brands
 * @param attributes OR within each attribute, AND across attributes, e.g. {color: [red, blue], size: [M]}
 */
public record SearchRequest(String query, String category, List<String> brands, Map<String, List<String>> attributes,
                            Long minPriceCents, Long maxPriceCents, boolean inStockOnly, boolean onSaleOnly,
                            Sort sort, int page, int size) {
    public enum Sort { RELEVANCE, PRICE_ASC, PRICE_DESC, RATING, NEWEST }

    public static final int MAX_SIZE = 60;
    /** Deep pagination is expensive in Elasticsearch; storefronts rarely need it. */
    public static final int MAX_WINDOW = 10_000;

    public SearchRequest {
        brands = brands == null ? List.of() : List.copyOf(brands);
        attributes = attributes == null ? Map.of() : Map.copyOf(attributes);
        sort = sort == null ? Sort.RELEVANCE : sort;
        size = Math.max(1, Math.min(MAX_SIZE, size == 0 ? 24 : size));
        page = Math.max(0, page);
        if ((long) (page + 1) * size > MAX_WINDOW) page = MAX_WINDOW / size - 1;
        if (minPriceCents != null && maxPriceCents != null && minPriceCents > maxPriceCents) {
            Long t = minPriceCents;
            minPriceCents = maxPriceCents;
            maxPriceCents = t;
        }
    }

    public boolean hasQuery() {
        return query != null && !query.isBlank();
    }
}
