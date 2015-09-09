package com.ericguerra.catalog.domain;

import jakarta.persistence.*;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;

/** Source of truth for catalog data. Elasticsearch holds a denormalized, searchable copy. */
@Entity
@Table(name = "products")
public class Product {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, unique = true, length = 40)
    private String sku;

    @Column(nullable = false, length = 200)
    private String name;

    @Column(columnDefinition = "text")
    private String description;

    @Column(length = 80)
    private String brand;

    /** Slash-delimited path, e.g. "Outdoors/Camping/Tents". Indexed as a hierarchy for drill-down facets. */
    @Column(name = "category_path", nullable = false, length = 300)
    private String categoryPath;

    @Column(name = "price_cents", nullable = false)
    private long priceCents;

    @Column(name = "sale_price_cents")
    private Long salePriceCents;

    @Column(name = "stock_qty", nullable = false)
    private int stockQty;

    @Column(precision = 2)
    private Double rating;

    @Column(name = "review_count", nullable = false)
    private int reviewCount;

    @Column(name = "image_url", length = 500)
    private String imageUrl;

    @Column(nullable = false)
    private boolean active = true;

    @ElementCollection(fetch = FetchType.EAGER)
    @CollectionTable(name = "product_attributes", joinColumns = @JoinColumn(name = "product_id"))
    @MapKeyColumn(name = "name", length = 60)
    @Column(name = "attr_value", length = 200)
    private Map<String, String> attributes = new LinkedHashMap<>();

    @Version
    private long version;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt = Instant.now();

    protected Product() {}

    public Product(String sku, String name, String categoryPath, long priceCents) {
        this.sku = sku;
        this.name = name;
        this.categoryPath = normalizePath(categoryPath);
        this.priceCents = priceCents;
    }

    public static String normalizePath(String path) {
        if (path == null) return "";
        return String.join("/", java.util.Arrays.stream(path.split("/")).map(String::trim).filter(s -> !s.isEmpty()).toList());
    }

    /** Price the customer pays right now. */
    public long effectivePriceCents() {
        return salePriceCents != null && salePriceCents < priceCents ? salePriceCents : priceCents;
    }

    public boolean onSale() {
        return salePriceCents != null && salePriceCents < priceCents;
    }

    public void touch() { updatedAt = Instant.now(); }

    public Long getId() { return id; }
    public String getSku() { return sku; }
    public void setSku(String sku) { this.sku = sku; }
    public String getName() { return name; }
    public void setName(String name) { this.name = name; }
    public String getDescription() { return description; }
    public void setDescription(String description) { this.description = description; }
    public String getBrand() { return brand; }
    public void setBrand(String brand) { this.brand = brand; }
    public String getCategoryPath() { return categoryPath; }
    public void setCategoryPath(String categoryPath) { this.categoryPath = normalizePath(categoryPath); }
    public long getPriceCents() { return priceCents; }
    public void setPriceCents(long priceCents) { this.priceCents = priceCents; }
    public Long getSalePriceCents() { return salePriceCents; }
    public void setSalePriceCents(Long salePriceCents) { this.salePriceCents = salePriceCents; }
    public int getStockQty() { return stockQty; }
    public void setStockQty(int stockQty) { this.stockQty = stockQty; }
    public Double getRating() { return rating; }
    public void setRating(Double rating) { this.rating = rating; }
    public int getReviewCount() { return reviewCount; }
    public void setReviewCount(int reviewCount) { this.reviewCount = reviewCount; }
    public String getImageUrl() { return imageUrl; }
    public void setImageUrl(String imageUrl) { this.imageUrl = imageUrl; }
    public boolean isActive() { return active; }
    public void setActive(boolean active) { this.active = active; }
    public Map<String, String> getAttributes() { return attributes; }
    public long getVersion() { return version; }
    public Instant getUpdatedAt() { return updatedAt; }
}
