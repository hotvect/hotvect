package com.hotvect.algorithmdemo;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.BufferedReader;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Statement;
import java.time.Instant;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.zip.GZIPInputStream;

final class DemoSqliteCache implements AutoCloseable {
    private static final Logger log = LoggerFactory.getLogger(DemoSqliteCache.class);
    private static final ObjectMapper OM = new ObjectMapper();
    private static final int SCHEMA_VERSION = 5;
    private static final int ACTION_METADATA_BATCH_SIZE = 10_000;

    private final Path dbPath;
    private final ActionMetadataRepository actionMetadata;
    private final boolean builtNow;

    private DemoSqliteCache(Path dbPath, ActionMetadataRepository actionMetadata, boolean builtNow) {
        this.dbPath = Objects.requireNonNull(dbPath);
        this.actionMetadata = Objects.requireNonNull(actionMetadata);
        this.builtNow = builtNow;
    }

    static DemoSqliteCache openOrBuild(Options opts) throws IOException {
        Objects.requireNonNull(opts);
        require(opts.sourcePath != null, "--source-path is required");
        require(opts.sourcePath.exists() && opts.sourcePath.isDirectory(), "--source-path must be a directory: %s", opts.sourcePath);

        Path dbPath = resolveDbPath(opts.sourcePath.toPath(), opts.actionMetadataPath == null ? null : opts.actionMetadataPath.toPath(), opts.demoSqlitePath);
        boolean builtNow = false;

        if (Files.exists(dbPath)) {
            if (!isValidSchema(dbPath)) {
                if (opts.demoSqlitePath != null) {
                    throw new IllegalArgumentException("SQLite cache exists but has an unexpected schema: " + dbPath);
                }
                log.warn("SQLite cache exists but has an unexpected schema; rebuilding: {}", dbPath);
                Files.delete(dbPath);
                builtNow = true;
                buildSqliteCache(dbPath, opts.sourcePath.toPath(), opts.actionMetadataPath == null ? null : opts.actionMetadataPath.toPath());
            }
            if (opts.demoSqlitePath != null && opts.actionMetadataPath != null) {
                log.warn("SQLite cache exists; ignoring --action-metadata-path={}", opts.actionMetadataPath.getAbsolutePath());
            }
        } else {
            builtNow = true;
            buildSqliteCache(dbPath, opts.sourcePath.toPath(), opts.actionMetadataPath == null ? null : opts.actionMetadataPath.toPath());
        }

        ActionMetadataRepository actionMetadata = ActionMetadataRepository.openSqliteDatabaseFile(dbPath);
        return new DemoSqliteCache(dbPath, actionMetadata, builtNow);
    }

    Path dbPath() {
        return dbPath;
    }

    boolean builtNow() {
        return builtNow;
    }

    ActionMetadataRepository actionMetadata() {
        return actionMetadata;
    }

    @Override
    public void close() {
        actionMetadata.close();
    }

    private static Path resolveDbPath(Path sourcePath, Path actionMetadataPathOrNull, File demoSqlitePathOrNull) {
        if (demoSqlitePathOrNull != null) {
            return demoSqlitePathOrNull.toPath().toAbsolutePath();
        }

        String sourceAbs = sourcePath.toAbsolutePath().normalize().toString();
        String metaAbs = actionMetadataPathOrNull == null ? "" : actionMetadataPathOrNull.toAbsolutePath().normalize().toString();
        String key = "source=" + sourceAbs + "\naction_metadata=" + metaAbs + "\n";

        String hash;
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            hash = HexFormat.of().formatHex(md.digest(key.getBytes(StandardCharsets.UTF_8))).substring(0, 16);
        } catch (Exception e) {
            throw new IllegalStateException("Failed to compute cache key hash", e);
        }

        Path dir = Path.of("/tmp/hv-demo-ui-sqlite-cache");
        try {
            Files.createDirectories(dir);
        } catch (IOException e) {
            throw new IllegalArgumentException("Failed to create SQLite cache directory: " + dir, e);
        }
        return dir.resolve("demo-" + hash + ".db");
    }

    private static void buildSqliteCache(Path dbPath, Path sourcePath, Path actionMetadataPathOrNull) throws IOException {
        Objects.requireNonNull(dbPath);
        Objects.requireNonNull(sourcePath);
        require(!Files.exists(dbPath), "Refusing to overwrite existing SQLite cache: %s", dbPath);

        Files.createDirectories(dbPath.toAbsolutePath().getParent());

        Path tmp = dbPath.resolveSibling(dbPath.getFileName().toString() + ".building-" + System.nanoTime());
        if (Files.exists(tmp)) {
            Files.delete(tmp);
        }

        long startedAtMs = System.currentTimeMillis();
        log.info("Building SQLite cache at {} (this may take a while)...", dbPath);

        int actionMetadataCount;

        try (Connection c = java.sql.DriverManager.getConnection("jdbc:sqlite:" + tmp.toAbsolutePath())) {
            applyBuildPragmas(c);
            c.setAutoCommit(false);
            createSchema(c);
            actionMetadataCount = actionMetadataPathOrNull == null ? 0 : ingestActionMetadata(c, actionMetadataPathOrNull);
            writeMeta(c, sourcePath, actionMetadataPathOrNull, actionMetadataCount);
            c.commit();
        } catch (ContractViolationException e) {
            throw e;
        } catch (IOException e) {
            throw e;
        } catch (Exception e) {
            throw new IOException("Failed to build SQLite cache: " + dbPath, e);
        }

        try {
            Files.move(tmp, dbPath);
        } catch (IOException moveErr) {
            try {
                Files.move(tmp, dbPath, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
            } catch (IOException moveErr2) {
                throw new IOException("Failed to move SQLite cache into place: " + tmp + " -> " + dbPath, moveErr2);
            }
        }

        long elapsedMs = System.currentTimeMillis() - startedAtMs;
        log.info("SQLite cache built: action_metadata={}, took {}ms, path={}", actionMetadataCount, elapsedMs, dbPath);
    }

    private static void applyBuildPragmas(Connection c) throws Exception {
        try (Statement s = c.createStatement()) {
            s.execute("PRAGMA journal_mode=OFF");
            s.execute("PRAGMA synchronous=OFF");
            // MEMORY temp store and very large cache sizes can OOM when ingesting big JSON blobs.
            s.execute("PRAGMA temp_store=FILE");
            s.execute("PRAGMA cache_size=-20000"); // ~20k pages in memory (~80MB at 4KB pages)
        }
    }

    private static void createSchema(Connection c) throws Exception {
        try (Statement s = c.createStatement()) {
            s.execute("""
                    CREATE TABLE IF NOT EXISTS meta (
                      key TEXT PRIMARY KEY,
                      value TEXT NOT NULL
                    )
                    """);
            s.execute("""
                    CREATE TABLE IF NOT EXISTS action_metadata (
                      action_id TEXT PRIMARY KEY,
                      action_name TEXT NOT NULL,
                      action_image_url TEXT NOT NULL,
                      action_metadata_json TEXT NOT NULL
                    ) WITHOUT ROWID
                    """);
        }
    }

    private static void writeMeta(Connection c, Path sourcePath, Path actionMetadataPathOrNull, int actionMetadataCount) throws Exception {
        try (PreparedStatement ps = c.prepareStatement("INSERT OR REPLACE INTO meta(key,value) VALUES(?,?)")) {
            putMeta(ps, "schema_version", String.valueOf(SCHEMA_VERSION));
            putMeta(ps, "created_at", Instant.now().toString());
            putMeta(ps, "source_path", sourcePath.toAbsolutePath().normalize().toString());
            putMeta(ps, "action_metadata_path", actionMetadataPathOrNull == null ? "" : actionMetadataPathOrNull.toAbsolutePath().normalize().toString());
            putMeta(ps, "action_metadata_count", String.valueOf(actionMetadataCount));
        }
    }

    private static void putMeta(PreparedStatement ps, String key, String value) throws Exception {
        ps.setString(1, key);
        ps.setString(2, value);
        ps.executeUpdate();
    }

    private static int ingestActionMetadata(Connection c, Path dir) throws Exception {
        Path root = dir.toAbsolutePath().normalize();
        int count = 0;
        String lastActionId = null;
        Path lastFile = null;
        int lastLineNumber = -1;
        long startedAt = System.currentTimeMillis();

        try (PreparedStatement ps = c.prepareStatement(
                "INSERT INTO action_metadata(action_id, action_name, action_image_url, action_metadata_json) VALUES(?,?,?,?)"
        )) {
            List<Path> files;
            try (var stream = Files.walk(root)) {
                files = stream.filter(Files::isRegularFile).filter(DemoSqliteCache::isCandidateActionMetadataFile).sorted().toList();
            }

            for (Path file : files) {
                try (BufferedReader reader = openPossiblyGzippedUtf8Reader(file)) {
                    String line;
                    int lineNumber = 0;
                    while ((line = reader.readLine()) != null) {
                        lineNumber++;
                        if (line.isBlank()) continue;
                        ObjectNode obj = parseObjectOrThrow(file, lineNumber, line);
                        String actionId = requireNonEmptyString(obj, "action_id", file, lineNumber);
                        String actionName = requireNonEmptyString(obj, "action_name", file, lineNumber);
                        String actionImageUrl = requireNonEmptyString(obj, "action_image_url", file, lineNumber);
                        String actionMetadataJson = obj.toString();

                        lastActionId = actionId;
                        lastFile = file;
                        lastLineNumber = lineNumber;

                        try {
                            ps.setString(1, actionId);
                            ps.setString(2, actionName);
                            ps.setString(3, actionImageUrl);
                            ps.setString(4, actionMetadataJson);
                            ps.addBatch();
                        } catch (Exception e) {
                            throw new ContractViolationException("Failed to bind action metadata row: " + file + ":" + lineNumber, e.getMessage());
                        }

                        count++;
                        if (count % ACTION_METADATA_BATCH_SIZE == 0) {
                            tryExecuteBatchOrThrowDuplicate(ps, lastActionId, lastFile, lastLineNumber);
                            c.commit();
                        }
                    }
                }
            }

            tryExecuteBatchOrThrowDuplicate(ps, lastActionId, lastFile, lastLineNumber);
            long elapsedMs = System.currentTimeMillis() - startedAt;
            log.info("SQLite cache: ingested action metadata: {} rows in {}ms", count, elapsedMs);
            return count;
        }
    }

    private static void tryExecuteBatchOrThrowDuplicate(PreparedStatement ps, String lastActionId, Path file, int lineNumber) throws Exception {
        try {
            ps.executeBatch();
        } catch (java.sql.SQLException e) {
            String msg = e.getMessage() == null ? "" : e.getMessage().toLowerCase(Locale.ROOT);
            if (msg.contains("unique") && msg.contains("action_metadata.action_id")) {
                String loc = (file == null) ? "" : (" at " + file + ":" + lineNumber);
                throw new ContractViolationException("Duplicate action_id in action metadata: " + lastActionId + loc, null);
            }
            throw e;
        }
    }

    private static boolean isValidSchema(Path dbPath) {
        try (Connection c = java.sql.DriverManager.getConnection("jdbc:sqlite:" + dbPath.toAbsolutePath())) {
            Integer version = readIntMeta(c, "schema_version");
            if (version == null || version != SCHEMA_VERSION) {
                return false;
            }
            return hasTable(c, "action_metadata") && hasTable(c, "meta");
        } catch (Exception e) {
            return false;
        }
    }

    private static boolean hasTable(Connection c, String table) throws Exception {
        try (PreparedStatement ps = c.prepareStatement("SELECT 1 FROM sqlite_master WHERE type='table' AND name=?")) {
            ps.setString(1, table);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next();
            }
        }
    }

    private static Integer readIntMeta(Connection c, String key) throws Exception {
        try (PreparedStatement ps = c.prepareStatement("SELECT value FROM meta WHERE key=?")) {
            ps.setString(1, key);
            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) return null;
                String v = rs.getString(1);
                if (v == null || v.isBlank()) return null;
                return Integer.parseInt(v.trim());
            }
        }
    }

    private static boolean isCandidateActionMetadataFile(Path path) {
        String name = path.getFileName().toString();
        if (name.isBlank()) {
            return false;
        }
        if (name.startsWith(".") || name.endsWith(".crc")) {
            return false;
        }
        if (name.startsWith("_")) {
            return false;
        }

        String uncompressed = name.endsWith(".gz") ? name.substring(0, name.length() - 3) : name;
        return uncompressed.startsWith("part-")
                || uncompressed.endsWith(".jsonl")
                || uncompressed.endsWith(".json")
                || uncompressed.endsWith(".ndjson");
    }

    private static BufferedReader openPossiblyGzippedUtf8Reader(Path file) throws IOException {
        InputStream in = Files.newInputStream(file);
        if (file.getFileName().toString().endsWith(".gz")) {
            in = new GZIPInputStream(in);
        }
        return new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8));
    }

    private static ObjectNode parseObjectOrThrow(Path file, int lineNumber, String json) {
        JsonNode parsed;
        try {
            parsed = OM.readTree(json);
        } catch (Exception e) {
            throw new ContractViolationException("Invalid JSON: " + file + ":" + lineNumber, e.getMessage());
        }
        if (!(parsed instanceof ObjectNode obj)) {
            throw new ContractViolationException("JSON must be an object: " + file + ":" + lineNumber, null);
        }
        return obj;
    }

    private static String requireNonEmptyString(ObjectNode obj, String field, Path file, int lineNumber) {
        JsonNode n = obj.get(field);
        if (n == null || !n.isTextual() || n.asText().isBlank()) {
            throw new ContractViolationException("Missing required field: " + field, file + ":" + lineNumber);
        }
        return n.asText();
    }

    private static void require(boolean condition, String messageTemplate, Object... args) {
        if (!condition) {
            throw new IllegalArgumentException(String.format(messageTemplate, args));
        }
    }
}
