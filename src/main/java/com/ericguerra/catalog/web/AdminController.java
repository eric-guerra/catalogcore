package com.ericguerra.catalog.web;

import com.ericguerra.catalog.domain.OutboxRepository;
import com.ericguerra.catalog.domain.Product;
import com.ericguerra.catalog.search.IndexManager;
import com.ericguerra.catalog.service.CatalogService;
import com.ericguerra.catalog.service.CsvImporter;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.NoSuchElementException;

@RestController
@RequestMapping("/api/admin")
public class AdminController {
    private final CatalogService catalog;
    private final CsvImporter importer;
    private final IndexManager index;
    private final OutboxRepository outbox;

    public AdminController(CatalogService catalog, CsvImporter importer, IndexManager index, OutboxRepository outbox) {
        this.catalog = catalog;
        this.importer = importer;
        this.index = index;
        this.outbox = outbox;
    }

    @PutMapping("/products")
    public Product upsert(@RequestBody CatalogService.ProductInput input) {
        return catalog.upsert(input);
    }

    @PutMapping("/products/{sku}/stock")
    public Product stock(@PathVariable String sku, @RequestBody Map<String, Integer> body) {
        return catalog.adjustStock(sku, body.getOrDefault("qty", -1));
    }

    @DeleteMapping("/products/{sku}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void deactivate(@PathVariable String sku) {
        catalog.deactivate(sku);
    }

    @PostMapping("/import")
    public CsvImporter.Report importCsv(@RequestParam("file") MultipartFile file) throws IOException {
        return importer.importCsv(new InputStreamReader(file.getInputStream(), StandardCharsets.UTF_8));
    }

    @PostMapping("/reindex")
    public Map<String, Object> reindex() {
        return Map.of("indexed", index.reindex());
    }

    @GetMapping("/index-status")
    public Map<String, Object> status() {
        return Map.of("pending", outbox.count(), "stuck", outbox.countByAttemptsGreaterThanEqual(10));
    }

    @ExceptionHandler(IllegalArgumentException.class)
    ResponseEntity<Map<String, String>> invalid(IllegalArgumentException e) {
        return ResponseEntity.unprocessableEntity().body(Map.of("error", e.getMessage()));
    }

    @ExceptionHandler(NoSuchElementException.class)
    ResponseEntity<Map<String, String>> missing(NoSuchElementException e) {
        return ResponseEntity.status(HttpStatus.NOT_FOUND).body(Map.of("error", e.getMessage()));
    }
}
