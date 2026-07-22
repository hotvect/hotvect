package com.hotvect.algorithmdemo;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.hotvect.algorithmserver.ActionMetadataLookup;
import com.hotvect.algorithmserver.ActionMetadataLookup.ActionMetadata;
import com.hotvect.algorithmserver.ContractViolationException;
import com.hotvect.algorithmserver.JsonFieldSupport;
import com.google.common.collect.Iterables;
import org.sqlite.SQLiteDataSource;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

public final class ActionMetadataRepository implements ActionMetadataLookup {
    private static final int SQLITE_MAX_IN_CLAUSE = 900;
    private static final ObjectMapper OM = new ObjectMapper();
    private static final String FALLBACK_ACTION_IMAGE_URL =
            "data:image/svg+xml;utf8," +
                    "<svg xmlns='http://www.w3.org/2000/svg' viewBox='0 0 320 240'>" +
                    "<rect width='320' height='240' fill='%23f3f4f6'/>" +
                    "<rect x='20' y='20' width='280' height='200' rx='16' fill='%23e5e7eb' stroke='%23cbd5e1' stroke-width='4'/>" +
                    "<path d='M86 160l38-42 32 34 46-55 50 63H86z' fill='%2394a3b8'/>" +
                    "<circle cx='124' cy='92' r='16' fill='%2394a3b8'/>" +
                    "<text x='160' y='206' text-anchor='middle' font-family='Arial,sans-serif' font-size='22' fill='%23475569'>No image</text>" +
                    "</svg>";

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

    public Map<String, ActionMetadata> getAllIfEnabled(Collection<String> actionIds) {
        Objects.requireNonNull(actionIds);
        if (!enabled) {
            return Map.of();
        }
        return backend.getAll(actionIds);
    }

    public JsonNode getJsonOrFallbackIfEnabled(String actionId) {
        if (!enabled) {
            return null;
        }
        String json = backend.getJson(actionId);
        if (json == null || json.isBlank()) {
            return fallbackJson(actionId);
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

    public static ActionMetadata fallback(String actionId) {
        String normalizedActionId = actionId == null || actionId.isBlank() ? "unknown-action" : actionId;
        return new ActionMetadata(normalizedActionId, normalizedActionId, FALLBACK_ACTION_IMAGE_URL);
    }

    public static JsonNode fallbackJson(String actionId) {
        ActionMetadata meta = fallback(actionId);
        var node = OM.createObjectNode();
        node.put("action_id", meta.actionId());
        node.put("action_name", meta.actionName());
        node.put("action_image_url", meta.actionImageUrl());
        node.put("missing_action_metadata", true);
        return node;
    }

    @Override
    public void close() {
        if (backend != null) {
            backend.close();
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
                    ActionMetadata meta = new ActionMetadata(
                            actionId,
                            JsonFieldSupport.blankToNull(rs.getString(1)),
                            JsonFieldSupport.blankToNull(rs.getString(2)));
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
                        if (actionId == null || actionId.isBlank()) {
                            throw new ContractViolationException("Corrupted SQLite action metadata record", null);
                        }
                        ActionMetadata meta = new ActionMetadata(
                                actionId,
                                JsonFieldSupport.blankToNull(rs.getString(2)),
                                JsonFieldSupport.blankToNull(rs.getString(3)));
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
