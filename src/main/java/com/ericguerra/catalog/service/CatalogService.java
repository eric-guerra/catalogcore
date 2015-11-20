package com.ericguerra.catalog.service;

import com.ericguerra.catalog.domain.OutboxEvent;
import com.ericguerra.catalog.domain.OutboxRepository;
import com.ericguerra.catalog.domain.Product;
import com.ericguerra.catalog.domain.ProductRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Map;
import java.util.NoSuchElementException;

/** All writes go through here so every change also lands in the index outbox, atomically. */
@Service
public class CatalogService {
    public record ProductInput(String sku, String name, String description, String brand, String categoryPath,
                               long priceCents, Long salePriceCents, int stockQty, Double rating, int reviewCount,
                               String imageUrl, Map<String, String> attributes) {
        public ProductInput {
            if (sku == null || !sku.matches("[A-Z0-9][A-Z0-9-]{1,39}")) throw new IllegalArgumentException("sku must be 2-40 uppercase letters, digits or dashes");
            if (name == null || name.isBlank()) throw new IllegalArgumentException("name is required");
            if (categoryPath == null || Product.normalizePath(categoryPath).isEmpty()) throw new IllegalArgumentException("categoryPath is required");
            if (priceCents < 0) throw new IllegalArgumentException("priceCents must be >= 0");
            if (salePriceCents != null && (salePriceCents < 0 || salePriceCents > priceCents)) throw new IllegalArgumentException("salePriceCents must be between 0 and priceCents");
            if (stockQty < 0) throw new IllegalArgumentException("stockQty must be >= 0");
            if (rating != null && (rating < 0 || rating > 5)) throw new IllegalArgumentException("rating must be 0-5");
            attributes = attributes == null ? Map.of() : attributes;
        }
    }

    private final ProductRepository products;
    private final OutboxRepository outbox;

    public CatalogService(ProductRepository products, OutboxRepository outbox) {
        this.products = products;
        this.outbox = outbox;
    }

    /** Insert or update by SKU. */
    @Transactional
    public Product upsert(ProductInput in) {
        Product p = products.findBySku(in.sku()).orElseGet(() -> new Product(in.sku(), in.name(), in.categoryPath(), in.priceCents()));
        apply(p, in);
        products.save(p);
        outbox.save(new OutboxEvent(p.getId(), OutboxEvent.Op.UPSERT));
        return p;
    }

    @Transactional
    public Product adjustStock(String sku, int qty) {
        Product p = products.findBySku(sku).orElseThrow(() -> new NoSuchElementException("product " + sku));
        if (qty < 0) throw new IllegalArgumentException("stock cannot be negative");
        boolean availabilityChanged = (p.getStockQty() > 0) != (qty > 0);
        p.setStockQty(qty);
        p.touch();
        // Only availability affects the index; skip reindexing for 5 -> 4 style changes.
        if (availabilityChanged) outbox.save(new OutboxEvent(p.getId(), OutboxEvent.Op.UPSERT));
        return p;
    }

    @Transactional
    public void deactivate(String sku) {
        Product p = products.findBySku(sku).orElseThrow(() -> new NoSuchElementException("product " + sku));
        p.setActive(false);
        p.touch();
        outbox.save(new OutboxEvent(p.getId(), OutboxEvent.Op.DELETE));
    }

    private static void apply(Product p, ProductInput in) {
        p.setName(in.name().trim());
        p.setDescription(in.description());
        p.setBrand(in.brand() == null ? null : in.brand().trim());
        p.setCategoryPath(in.categoryPath());
        p.setPriceCents(in.priceCents());
        p.setSalePriceCents(in.salePriceCents());
        p.setStockQty(in.stockQty());
        p.setRating(in.rating());
        p.setReviewCount(in.reviewCount());
        p.setImageUrl(in.imageUrl());
        p.setActive(true);
        p.getAttributes().clear();
        in.attributes().forEach((k, v) -> {
            if (k != null && v != null && !v.isBlank()) p.getAttributes().put(k.trim().toLowerCase(), v.trim());
        });
        p.touch();
    }
}
