package com.ericguerra.catalog.search;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

@Service
public class SearchService {
    private final ElasticsearchClient es;
    private final IndexManager index;
    private final SearchQueryBuilder builder;

    public SearchService(ElasticsearchClient es, IndexManager index, ObjectMapper json,
                         @Value("${catalog.facet-attributes:color,size,material}") List<String> facetAttributes) {
        this.es = es;
        this.index = index;
        this.builder = new SearchQueryBuilder(json, facetAttributes);
    }

    public SearchResponseParser.Result search(SearchRequest request) {
        JsonNode resp = es.request("POST", "/" + index.alias() + "/_search", builder.build(request));
        return SearchResponseParser.parse(resp, request);
    }

    public List<Map<String, String>> suggest(String prefix) {
        if (prefix == null || prefix.isBlank()) return List.of();
        JsonNode resp = es.request("POST", "/" + index.alias() + "/_search", builder.suggest(prefix.trim(), 8));
        List<Map<String, String>> out = new ArrayList<>();
        for (JsonNode h : resp.path("hits").path("hits")) {
            JsonNode s = h.path("_source");
            out.add(Map.of("sku", s.path("sku").asText(), "name", s.path("name").asText(), "brand", s.path("brand").asText("")));
        }
        return out;
    }
}
