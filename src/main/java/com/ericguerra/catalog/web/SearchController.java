package com.ericguerra.catalog.web;

import com.ericguerra.catalog.search.SearchRequest;
import com.ericguerra.catalog.search.SearchResponseParser;
import com.ericguerra.catalog.search.SearchService;
import org.springframework.util.MultiValueMap;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * GET /api/search?q=tent&category=Outdoors/Camping&brand=Ridgeline&attr.color=green&min=5000&max=20000&sort=PRICE_ASC&page=0
 */
@RestController
@RequestMapping("/api")
public class SearchController {
    private final SearchService search;

    public SearchController(SearchService search) {
        this.search = search;
    }

    @GetMapping("/search")
    public SearchResponseParser.Result search(@RequestParam MultiValueMap<String, String> params) {
        Map<String, List<String>> attrs = new LinkedHashMap<>();
        params.forEach((k, v) -> {
            if (k.startsWith("attr.") && k.length() > 5) attrs.put(k.substring(5).toLowerCase(), v);
        });
        return search.search(new SearchRequest(
                params.getFirst("q"), params.getFirst("category"), params.getOrDefault("brand", List.of()), attrs,
                longOrNull(params.getFirst("min")), longOrNull(params.getFirst("max")),
                "true".equals(params.getFirst("inStock")), "true".equals(params.getFirst("onSale")),
                sort(params.getFirst("sort")), intOr(params.getFirst("page"), 0), intOr(params.getFirst("size"), 24)));
    }

    @GetMapping("/suggest")
    public List<Map<String, String>> suggest(@RequestParam("q") String prefix) {
        return search.suggest(prefix);
    }

    private static SearchRequest.Sort sort(String s) {
        if (s == null) return SearchRequest.Sort.RELEVANCE;
        try {
            return SearchRequest.Sort.valueOf(s.toUpperCase());
        } catch (IllegalArgumentException e) {
            return SearchRequest.Sort.RELEVANCE;
        }
    }

    private static Long longOrNull(String s) {
        try {
            return s == null || s.isBlank() ? null : Long.parseLong(s);
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private static int intOr(String s, int d) {
        try {
            return s == null ? d : Integer.parseInt(s);
        } catch (NumberFormatException e) {
            return d;
        }
    }
}
