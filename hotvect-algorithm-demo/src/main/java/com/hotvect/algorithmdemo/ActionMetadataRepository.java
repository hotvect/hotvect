package com.hotvect.algorithmdemo;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.common.collect.Iterables;
import org.sqlite.SQLiteDataSource;

import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

public final class ActionMetadataRepository implements AutoCloseable {
    private static final int SQLITE_MAX_IN_CLAUSE = 900;
    private static final ObjectMapper OM = new ObjectMapper();

    private final boolean enabled;
    private final Backend backend;
    private final int size;

    private ActionMetadataRepository(boolean enabled, Backend backend, int size) {
        this.enabled = enabled;
        this.backend = backend;
        this.size = size;
    }

    public static ActionMetadataRepository empty() {
        return new ActionMetadataRepository(false, null, 0);
    }

    public static ActionMetadataRepository openSqliteDatabaseFile(java.nio.file.Path sqliteDbFile) {
        Objects.requireNonNull(sqliteDbFile);
        SQLiteDataSource ds = new SQLiteDataSource();
        ds.setUrl("jdbc:sqlite:" + sqliteDbFile.toAbsolutePath());
        SqliteBackend backend = new SqliteBackend(ds);
        int count = backend.count();
        boolean enabled = count > 0;
        return new ActionMetadataRepository(enabled, enabled ? backend : null, count);
    }

    public boolean isEnabled() {
        return enabled;
    }

    public int size() {
        return size;
    }

    public ActionMetadata requireIfEnabled(String actionId) {
        if (!enabled) {
            return null;
        }
        ActionMetadata meta = backend.get(actionId);
        if (meta == null) {
            throw new ContractViolationException("Missing action metadata for action_id: " + actionId, null);
        }
        return meta;
    }

    public JsonNode requireJsonIfEnabled(String actionId) {
        if (!enabled) {
            return null;
        }
        String json = backend.getJson(actionId);
        if (json == null || json.isBlank()) {
            throw new ContractViolationException("Missing action metadata for action_id: " + actionId, null);
        }
        try {
            JsonNode node = OM.readTree(json);
            if (node == null || node.isNull() || !node.isObject()) {
                throw new ContractViolationException("Corrupted SQLite action metadata JSON for action_id: " + actionId, null);
            }
            return node;
        } catch (ContractViolationException e) {
            throw e;
        } catch (Exception e) {
            throw new RuntimeException("Failed to parse action metadata JSON for action_id=" + actionId, e);
        }
    }

    public Map<String, ActionMetadata> requireAllIfEnabled(Collection<String> actionIds) {
        Objects.requireNonNull(actionIds);
        if (!enabled) {
            return Map.of();
        }
        Map<String, ActionMetadata> found = backend.getAll(actionIds);
        for (String actionId : actionIds) {
            if (actionId == null || actionId.isBlank()) {
                continue;
            }
            if (!found.containsKey(actionId)) {
                throw new ContractViolationException("Missing action metadata for action_id: " + actionId, null);
            }
        }
        return found;
    }

    public Map<String, ActionMetadata> getAllIfEnabled(Collection<String> actionIds) {
        Objects.requireNonNull(actionIds);
        if (!enabled) {
            return Map.of();
        }
        return backend.getAll(actionIds);
    }

    @Override
    public void close() {
        if (backend != null) {
            backend.close();
        }
    }

    public record ActionMetadata(String actionId, String actionName, String actionImageUrl) {
        public ActionMetadata {
            Objects.requireNonNull(actionId);
            Objects.requireNonNull(actionName);
            Objects.requireNonNull(actionImageUrl);
        }

        public byte[] toSqliteValueBytes() {
            // Compact encoding for potential future KV backends; keep stable.
            byte[] nameBytes = actionName.getBytes(StandardCharsets.UTF_8);
            byte[] urlBytes = actionImageUrl.getBytes(StandardCharsets.UTF_8);
            byte[] out = new byte[nameBytes.length + 1 + urlBytes.length];
            System.arraycopy(nameBytes, 0, out, 0, nameBytes.length);
            out[nameBytes.length] = 0;
            System.arraycopy(urlBytes, 0, out, nameBytes.length + 1, urlBytes.length);
            return out;
        }
    }

    interface Backend extends AutoCloseable {
        ActionMetadata get(String actionId);

        Map<String, ActionMetadata> getAll(Collection<String> actionIds);

        String getJson(String actionId);

        @Override
        void close();
    }

    private static final class SqliteBackend implements Backend {
        private final SQLiteDataSource ds;
        private final LruCache cache = new LruCache(50_000);

        private SqliteBackend(SQLiteDataSource ds) {
            this.ds = Objects.requireNonNull(ds);
        }

        int count() {
            try (Connection c = ds.getConnection();
                 PreparedStatement ps = c.prepareStatement("SELECT COUNT(*) FROM action_metadata");
                 ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) {
                    return 0;
                }
                long v = rs.getLong(1);
                if (v > Integer.MAX_VALUE) {
                    return Integer.MAX_VALUE;
                }
                return (int) v;
            } catch (Exception e) {
                throw new IllegalStateException("Failed to read action_metadata count from SQLite", e);
            }
        }

        @Override
        public ActionMetadata get(String actionId) {
            if (actionId == null || actionId.isBlank()) {
                return null;
            }
            ActionMetadata cached = cache.get(actionId);
            if (cached != null) {
                return cached;
            }

            try (Connection c = ds.getConnection();
                 PreparedStatement ps = c.prepareStatement(
                         "SELECT action_name, action_image_url FROM action_metadata WHERE action_id = ?"
                 )) {
                ps.setString(1, actionId);
                try (ResultSet rs = ps.executeQuery()) {
                    if (!rs.next()) {
                        return null;
                    }
                    String name = rs.getString(1);
                    String url = rs.getString(2);
                    if (name == null || name.isBlank() || url == null || url.isBlank()) {
                        throw new ContractViolationException("Corrupted SQLite action metadata for action_id: " + actionId, null);
                    }
                    ActionMetadata meta = new ActionMetadata(actionId, name, url);
                    cache.put(meta);
                    return meta;
                }
            } catch (ContractViolationException e) {
                throw e;
            } catch (Exception e) {
                throw new RuntimeException("SQLite read failed for action_id=" + actionId, e);
            }
        }

        @Override
        public Map<String, ActionMetadata> getAll(Collection<String> actionIds) {
            List<String> ids = new ArrayList<>();
            for (String actionId : actionIds) {
                if (actionId != null && !actionId.isBlank()) {
                    ids.add(actionId);
                }
            }
            if (ids.isEmpty()) {
                return Map.of();
            }

            Map<String, ActionMetadata> out = new LinkedHashMap<>();
            for (List<String> batch : Iterables.partition(ids, SQLITE_MAX_IN_CLAUSE)) {
                out.putAll(getAllBatch(batch));
            }
            return out;
        }

        private Map<String, ActionMetadata> getAllBatch(List<String> ids) {
            StringBuilder sql = new StringBuilder();
            sql.append("SELECT action_id, action_name, action_image_url FROM action_metadata WHERE action_id IN (");
            for (int i = 0; i < ids.size(); i++) {
                if (i > 0) sql.append(",");
                sql.append("?");
            }
            sql.append(")");

            Map<String, ActionMetadata> out = new LinkedHashMap<>();
            try (Connection c = ds.getConnection();
                 PreparedStatement ps = c.prepareStatement(sql.toString())) {
                for (int i = 0; i < ids.size(); i++) {
                    ps.setString(i + 1, ids.get(i));
                }
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) {
                        String actionId = rs.getString(1);
                        String name = rs.getString(2);
                        String url = rs.getString(3);
                        if (actionId == null || actionId.isBlank() || name == null || name.isBlank() || url == null || url.isBlank()) {
                            throw new ContractViolationException("Corrupted SQLite action metadata record", null);
                        }
                        ActionMetadata meta = new ActionMetadata(actionId, name, url);
                        cache.put(meta);
                        out.put(actionId, meta);
                    }
                }
                return out;
            } catch (ContractViolationException e) {
                throw e;
            } catch (Exception e) {
                throw new RuntimeException("SQLite batch read failed (n=" + ids.size() + ")", e);
            }
        }

        @Override
        public String getJson(String actionId) {
            if (actionId == null || actionId.isBlank()) {
                return null;
            }
            try (Connection c = ds.getConnection();
                 PreparedStatement ps = c.prepareStatement(
                         "SELECT action_metadata_json FROM action_metadata WHERE action_id = ?"
                 )) {
                ps.setString(1, actionId);
                try (ResultSet rs = ps.executeQuery()) {
                    if (!rs.next()) {
                        return null;
                    }
                    String json = rs.getString(1);
                    if (json == null || json.isBlank()) {
                        throw new ContractViolationException("Corrupted SQLite action metadata JSON for action_id: " + actionId, null);
                    }
                    return json;
                }
            } catch (ContractViolationException e) {
                throw e;
            } catch (Exception e) {
                throw new RuntimeException("SQLite read failed for action_id=" + actionId, e);
            }
        }

        @Override
        public void close() {
            // SQLiteDataSource does not require explicit close.
        }

        private static final class LruCache {
            private final int maxSize;
            private final LinkedHashMap<String, ActionMetadata> map;

            private LruCache(int maxSize) {
                this.maxSize = maxSize;
                this.map = new LinkedHashMap<>(Math.min(maxSize, 1024), 0.75f, true) {
                    @Override
                    protected boolean removeEldestEntry(Map.Entry<String, ActionMetadata> eldest) {
                        return size() > LruCache.this.maxSize;
                    }
                };
            }

            ActionMetadata get(String actionId) {
                synchronized (map) {
                    return map.get(actionId);
                }
            }

            void put(ActionMetadata meta) {
                synchronized (map) {
                    map.put(meta.actionId(), meta);
                }
            }
        }
    }
}
