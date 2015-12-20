package com.ericguerra.catalog.search;

import com.ericguerra.catalog.domain.Product;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Maps a Product entity to its denormalized search document. */
public final class ProductDocument {
    private ProductDocument() {}

    public static Map<String, Object> from(Product p) {
        Map<String, Object> doc = new LinkedHashMap<>();
        doc.put("id", p.getId());
        doc.put("sku", p.getSku());
        doc.put("name", p.getName());
        doc.put("description", p.getDescription());
        doc.put("brand", p.getBrand());

        // "Outdoors/Camping/Tents" -> path tokens [Outdoors, Outdoors/Camping, Outdoors/Camping/Tents]
        Map<String, Object> category = new LinkedHashMap<>();
        category.put("path", ancestors(p.getCategoryPath()));
        category.put("leaf", leaf(p.getCategoryPath()));
        category.put("text", p.getCategoryPath().replace('/', ' '));
        doc.put("category", category);

        doc.put("price_cents", p.effectivePriceCents());
        doc.put("list_price_cents", p.getPriceCents());
        doc.put("on_sale", p.onSale());
        doc.put("discount_pct", p.onSale() ? Math.round(100.0 * (p.getPriceCents() - p.getSalePriceCents()) / p.getPriceCents()) : 0);
        doc.put("in_stock", p.getStockQty() > 0);
        doc.put("rating", p.getRating());
        doc.put("review_count", p.getReviewCount());
        doc.put("image_url", p.getImageUrl());
        doc.put("active", p.isActive());
        doc.put("updated_at", p.getUpdatedAt().toString());

        List<Map<String, String>> attrs = new ArrayList<>();
        StringBuilder attrText = new StringBuilder();
        p.getAttributes().forEach((k, v) -> {
            Map<String, String> attr = new LinkedHashMap<>();
            attr.put("name", k.toLowerCase());
            attr.put("value", v);
            attrs.add(attr);
            attrText.append(v).append(' ');
        });
        doc.put("attrs", attrs);
        doc.put("attributes", Map.of("text", attrText.toString().trim()));
        return doc;
    }

    static List<String> ancestors(String path) {
        List<String> out = new ArrayList<>();
        if (path == null || path.isBlank()) return out;
        StringBuilder sb = new StringBuilder();
        for (String part : path.split("/")) {
            if (!sb.isEmpty()) sb.append('/');
            sb.append(part);
            out.add(sb.toString());
        }
        return out;
    }

    static String leaf(String path) {
        int i = path.lastIndexOf('/');
        return i < 0 ? path : path.substring(i + 1);
    }
}
