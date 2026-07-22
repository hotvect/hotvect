package com.hotvect.example.product;

import com.hotvect.api.algodefinition.state.StateGenerator;

import java.io.BufferedWriter;
import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

final class ProductCatalogStateGenerator implements StateGenerator {
    static final String SOURCE_NAME = "product_catalog";

    @Override
    public Map<String, Object> apply(Map<String, List<File>> sourceFiles, File destinationDirectory) {
        List<File> roots = sourceFiles.get(SOURCE_NAME);
        if (roots == null || roots.isEmpty()) {
            throw new IllegalArgumentException("Missing source data: " + SOURCE_NAME);
        }

        List<Path> files = dataFiles(roots);
        if (files.isEmpty()) {
            throw new IllegalArgumentException("No catalog data files found in " + roots);
        }

        Map<String, ProductCatalogJson.Entry> entries = new LinkedHashMap<>();
        for (Path file : files) {
            List<String> lines;
            try {
                lines = Files.readAllLines(file, StandardCharsets.UTF_8);
            } catch (IOException error) {
                throw new IllegalArgumentException("Could not read catalog source: " + file, error);
            }
            for (int index = 0; index < lines.size(); index++) {
                String line = lines.get(index);
                if (line.isBlank()) {
                    throw new IllegalArgumentException(file + " contains a blank row at line " + (index + 1));
                }
                ProductCatalogJson.Entry entry = ProductCatalogJson.decode(line);
                if (entries.putIfAbsent(entry.actionId(), entry) != null) {
                    throw new IllegalArgumentException("Duplicate catalog action_id: " + entry.actionId());
                }
            }
        }

        Path destination = destinationDirectory.toPath();
        try {
            Files.createDirectories(destination);
            try (BufferedWriter writer = Files.newBufferedWriter(
                    destination.resolve(ProductCatalogStateFactory.CATALOG_PARAMETER),
                    StandardCharsets.UTF_8
            )) {
                entries.values().stream()
                        .sorted(Comparator.comparing(ProductCatalogJson.Entry::actionId))
                        .forEach(entry -> writeEntry(writer, entry));
            }
        } catch (IOException error) {
            throw new IllegalArgumentException("Could not write product catalog state to " + destination, error);
        }

        Map<String, Object> metadata = new LinkedHashMap<>();
        metadata.put("catalog_size", entries.size());
        metadata.put("source_files", files.size());
        return metadata;
    }

    private static List<Path> dataFiles(List<File> roots) {
        List<Path> files = new ArrayList<>();
        for (File root : roots) {
            Path path = root.toPath();
            try (var paths = Files.walk(path)) {
                paths.filter(Files::isRegularFile)
                        .filter(ProductCatalogStateGenerator::isDataFile)
                        .forEach(files::add);
            } catch (IOException error) {
                throw new IllegalArgumentException("Could not scan catalog source: " + path, error);
            }
        }
        files.sort(Comparator.naturalOrder());
        return List.copyOf(files);
    }

    private static boolean isDataFile(Path path) {
        String name = path.getFileName().toString();
        return name.endsWith(".jsonl") || name.endsWith(".json") || name.startsWith("part-");
    }

    private static void writeEntry(BufferedWriter writer, ProductCatalogJson.Entry entry) {
        try {
            writer.write(ProductCatalogJson.encode(entry));
            writer.newLine();
        } catch (IOException error) {
            throw new IllegalArgumentException("Could not write catalog entry: " + entry.actionId(), error);
        }
    }
}
