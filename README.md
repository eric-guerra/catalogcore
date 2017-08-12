# CatalogCore

A retail product catalog service with **faceted search**: PostgreSQL is the system of record, Elasticsearch powers the storefront search, and an **AngularJS** storefront has multi-select facets, autocomplete and shareable URLs. Built with **Spring Boot**.

> Portfolio reference implementation of a 2015 digital-commerce project (Spring Boot 1.x + Elasticsearch + AngularJS), built on Spring Boot 3, Java 21 and Elasticsearch 8.

## Architecture

```
admin API / CSV import ──▶ CatalogService ──(one transaction)──▶ products + index_outbox (PostgreSQL)
                                                                        │
                                              IndexSyncJob (every 1s) ◀─┘  coalesce · bulk upsert · retry
                                                                        ▼
storefront (AngularJS) ──▶ /api/search, /api/suggest ──▶ Elasticsearch alias "products"
```

- **Transactional outbox**: every catalog change writes an outbox row in the same transaction, so the index can lag but never miss an update. The sync job coalesces repeated changes per product (upsert then delete becomes delete), bulk-indexes, and records attempts and errors for retry.
- **External versioning**: documents are indexed with the entity's JPA `@Version` (`version_type=external_gte`), so an out-of-order retry can never overwrite newer data.
- **Zero-downtime reindex**: `POST /api/admin/reindex` builds `products-<timestamp>` from the database and atomically swaps the alias.

## Search features

- Relevance: fuzzy `multi_match` across name^4, brand^3, category^2, description and attributes; an exact-SKU term boost; a phrase boost; synonyms (tee/t-shirt, backpack/rucksack…) on the search analyzer; light English stemming
- Business ranking via `function_score`: in-stock ×1.5, on-sale ×1.1, log-scaled rating
- **Multi-select facets** using `post_filter`: each facet's counts are computed with every filter *except its own*. Facets cover brand, a drill-down category tree (indexed as ancestor paths), price ranges, and nested attribute facets (color, size…)
- Autocomplete with `search_as_you_type`; highlighted names; stable sort tie-break; deep-paging cap

## Run

```bash
docker compose up --build
curl -F file=@sample-catalog.csv localhost:8080/api/admin/import
open http://localhost:8080/?q=tent
```

## Test

```bash
mvn test
```

The tests cover query DSL generation (facet scoping, filters, boosts, paging limits), document denormalization, response parsing (drill-down categories, selected buckets), and Spring integration tests on H2 with a fake Elasticsearch: outbox coalescing, availability-only reindexing, delete propagation, retry on failure, and CSV validation per row.
