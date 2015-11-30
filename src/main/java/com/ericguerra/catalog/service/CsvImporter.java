package com.ericguerra.catalog.service;

import org.apache.commons.csv.CSVFormat;
import org.apache.commons.csv.CSVParser;
import org.apache.commons.csv.CSVRecord;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.Reader;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Bulk catalog import from merchandising spreadsheets.
 * Required columns: sku, name, category, price. Optional: description, brand, sale_price, stock, rating,
 * reviews, image_url. Any column named attr_<name> becomes an attribute (attr_color -> color).
 * Rows are validated independently; one bad row doesn't abort the file.
 */
@Component
public class CsvImporter {
    public record RowError(long line, String sku, String message) {}

    public record Report(int imported, List<RowError> errors) {}

    private static final Set<String> REQUIRED = Set.of("sku", "name", "category", "price");

    private final CatalogService catalog;

    public CsvImporter(CatalogService catalog) {
        this.catalog = catalog;
    }

    public Report importCsv(Reader reader) throws IOException {
        CSVFormat format = CSVFormat.DEFAULT.builder().setHeader().setSkipHeaderRecord(true).setIgnoreEmptyLines(true).setTrim(true).build();
        try (CSVParser parser = format.parse(reader)) {
            List<String> headers = parser.getHeaderNames().stream().map(h -> h.trim().toLowerCase()).toList();
            List<String> missing = REQUIRED.stream().filter(r -> !headers.contains(r)).sorted().toList();
            if (!missing.isEmpty()) throw new IllegalArgumentException("missing required columns: " + String.join(", ", missing));

            int imported = 0;
            List<RowError> errors = new ArrayList<>();
            for (CSVRecord row : parser) {
                Map<String, String> r = new LinkedHashMap<>();
                for (int i = 0; i < headers.size() && i < row.size(); i++) r.put(headers.get(i), row.get(i));
                String sku = r.getOrDefault("sku", "").toUpperCase();
                try {
                    Map<String, String> attrs = new LinkedHashMap<>();
                    r.forEach((k, v) -> { if (k.startsWith("attr_") && !v.isBlank()) attrs.put(k.substring(5), v); });
                    catalog.upsert(new CatalogService.ProductInput(sku, r.get("name"), blankToNull(r.get("description")),
                            blankToNull(r.get("brand")), r.get("category"), cents(r.get("price"), "price"),
                            r.containsKey("sale_price") && !r.get("sale_price").isBlank() ? cents(r.get("sale_price"), "sale_price") : null,
                            intOr(r.get("stock"), 0), r.containsKey("rating") && !r.get("rating").isBlank() ? Double.parseDouble(r.get("rating")) : null,
                            intOr(r.get("reviews"), 0), blankToNull(r.get("image_url")), attrs));
                    imported++;
                } catch (RuntimeException e) {
                    errors.add(new RowError(row.getRecordNumber() + 1, sku, e.getMessage()));
                }
            }
            return new Report(imported, errors);
        }
    }

    static long cents(String value, String field) {
        if (value == null || value.isBlank()) throw new IllegalArgumentException(field + " is required");
        try {
            return new BigDecimal(value.replace("$", "").replace(",", "")).movePointRight(2).longValueExact();
        } catch (ArithmeticException | NumberFormatException e) {
            throw new IllegalArgumentException(field + " must be a price like 19.99");
        }
    }

    private static int intOr(String v, int dflt) {
        return v == null || v.isBlank() ? dflt : Integer.parseInt(v);
    }

    private static String blankToNull(String v) {
        return v == null || v.isBlank() ? null : v;
    }
}
