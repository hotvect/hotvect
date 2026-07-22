package com.hotvect.algorithmserver;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

public final class JsonInStringSupport {
    private static final ObjectMapper OM = new ObjectMapper();
    private static final String VIRTUAL_SUFFIX = "__json";
    private static final int MAX_JSON_STRING_BYTES = 5 * 1024 * 1024;
    private static final int MAX_TOTAL_JSON_STRING_BYTES = 16 * 1024 * 1024;
    private static final int MAX_DEPTH = 40;

    private JsonInStringSupport() {
    }

    public static void injectVirtualJsonFields(ObjectNode root) {
        if (root == null) {
            return;
        }
        InjectionBudget budget = new InjectionBudget(MAX_TOTAL_JSON_STRING_BYTES);
        injectVirtualJsonFieldsRec(root, budget, 0);
    }

    public static void injectVirtualJsonFields(ObjectNode root, List<String> jsonInStringPaths) {
        if (root == null || jsonInStringPaths == null || jsonInStringPaths.isEmpty()) {
            return;
        }
        for (String path : jsonInStringPaths) {
            if (path == null || path.isBlank()) {
                continue;
            }
            Optional<ResolvedField> resolved = resolveField(root, path);
            if (resolved.isEmpty()) {
                continue;
            }

            ResolvedField f = resolved.get();
            String virtualKey = f.key() + VIRTUAL_SUFFIX;
            if (f.parent().has(virtualKey)) {
                continue;
            }

            JsonNode value = f.parent().get(f.key());
            if (value == null || !value.isTextual() || value.asText().isBlank()) {
                continue;
            }
            JsonNode parsed;
            try {
                parsed = OM.readTree(value.asText());
            } catch (Exception ignored) {
                continue;
            }
            if (!parsed.isObject() && !parsed.isArray()) {
                continue;
            }
            f.parent().set(virtualKey, parsed);
        }
    }

    public static void collapseVirtualJsonFields(ObjectNode root) {
        if (root == null) {
            return;
        }
        collapseVirtualJsonFieldsRec(root, 0);
    }

    public static void collapseVirtualJsonFields(ObjectNode root, List<String> jsonInStringPaths) {
        if (root == null || jsonInStringPaths == null || jsonInStringPaths.isEmpty()) {
            return;
        }
        for (String path : jsonInStringPaths) {
            if (path == null || path.isBlank()) {
                continue;
            }
            Optional<ResolvedField> resolved = resolveField(root, path);
            if (resolved.isEmpty()) {
                continue;
            }
            ResolvedField f = resolved.get();
            String virtualKey = f.key() + VIRTUAL_SUFFIX;
            JsonNode virtual = f.parent().get(virtualKey);
            if (virtual == null) {
                continue;
            }
            if (virtual.isObject() || virtual.isArray()) {
                try {
                    f.parent().put(f.key(), OM.writeValueAsString(virtual));
                } catch (Exception ignored) {
                    // If we cannot serialize, leave the original string field as-is.
                }
            }
            f.parent().remove(virtualKey);
        }
    }

    private static void injectVirtualJsonFieldsRec(JsonNode node, InjectionBudget budget, int depth) {
        if (node == null || depth > MAX_DEPTH || budget.remainingBytes <= 0) {
            return;
        }
        if (node instanceof ObjectNode obj) {
            List<String> fieldNames = new ArrayList<>();
            obj.fieldNames().forEachRemaining(fieldNames::add);
            for (String key : fieldNames) {
                JsonNode v = obj.get(key);
                if (v == null) {
                    continue;
                }
                if (v.isTextual()) {
                    maybeInjectOne(obj, key, v.asText(), budget);
                }
                injectVirtualJsonFieldsRec(v, budget, depth + 1);
            }
        } else if (node instanceof ArrayNode arr) {
            for (JsonNode child : arr) {
                injectVirtualJsonFieldsRec(child, budget, depth + 1);
            }
        }
    }

    private static void maybeInjectOne(ObjectNode parent, String key, String rawValue, InjectionBudget budget) {
        if (key == null || key.isBlank()) {
            return;
        }
        String virtualKey = key + VIRTUAL_SUFFIX;
        if (parent.has(virtualKey)) {
            return;
        }
        if (rawValue == null) {
            return;
        }
        String s = rawValue.trim();
        if (s.isEmpty()) {
            return;
        }
        if (!looksLikeJsonObjectOrArray(s)) {
            return;
        }
        int bytes = s.length(); // conservative; UTF-16 chars but fine as a rough guardrail
        if (bytes > MAX_JSON_STRING_BYTES || bytes > budget.remainingBytes) {
            return;
        }
        JsonNode parsed;
        try {
            parsed = OM.readTree(s);
        } catch (Exception ignored) {
            return;
        }
        if (!parsed.isObject() && !parsed.isArray()) {
            return;
        }
        budget.remainingBytes -= bytes;
        parent.set(virtualKey, parsed);
    }

    private static boolean looksLikeJsonObjectOrArray(String s) {
        if (s == null) {
            return false;
        }
        int n = s.length();
        if (n < 2) {
            return false;
        }
        char first = s.charAt(0);
        char last = s.charAt(n - 1);
        return (first == '{' && last == '}') || (first == '[' && last == ']');
    }

    private static void collapseVirtualJsonFieldsRec(JsonNode node, int depth) {
        if (node == null || depth > MAX_DEPTH) {
            return;
        }
        if (node instanceof ObjectNode obj) {
            List<String> keys = new ArrayList<>();
            obj.fieldNames().forEachRemaining(keys::add);

            for (String key : keys) {
                JsonNode child = obj.get(key);
                if (child == null) {
                    continue;
                }
                if (key.endsWith(VIRTUAL_SUFFIX) && (child.isObject() || child.isArray())) {
                    String baseKey = key.substring(0, key.length() - VIRTUAL_SUFFIX.length());
                    try {
                        obj.put(baseKey, OM.writeValueAsString(child));
                    } catch (Exception ignored) {
                        // If we cannot serialize, leave as-is.
                    }
                    obj.remove(key);
                    continue;
                }
                collapseVirtualJsonFieldsRec(child, depth + 1);
            }
        } else if (node instanceof ArrayNode arr) {
            for (JsonNode child : arr) {
                collapseVirtualJsonFieldsRec(child, depth + 1);
            }
        }
    }

    private static Optional<ResolvedField> resolveField(ObjectNode root, String pathText) {
        List<Token> tokens;
        try {
            tokens = parsePath(pathText);
        } catch (IllegalArgumentException e) {
            return Optional.empty();
        }
        if (tokens.isEmpty()) {
            return Optional.empty();
        }
        Token last = tokens.getLast();
        if (!(last instanceof KeyToken keyToken)) {
            return Optional.empty();
        }

        JsonNode cur = root;
        for (int i = 0; i < tokens.size() - 1; i++) {
            Token t = tokens.get(i);
            if (t instanceof KeyToken kt) {
                if (cur == null || !cur.isObject()) {
                    return Optional.empty();
                }
                cur = cur.get(kt.key);
            } else if (t instanceof IndexToken it) {
                if (cur == null || !cur.isArray()) {
                    return Optional.empty();
                }
                if (it.index < 0 || it.index >= cur.size()) {
                    return Optional.empty();
                }
                cur = cur.get(it.index);
            } else {
                return Optional.empty();
            }
            if (cur == null) {
                return Optional.empty();
            }
        }

        if (!(cur instanceof ObjectNode parent)) {
            return Optional.empty();
        }
        return Optional.of(new ResolvedField(parent, keyToken.key));
    }

    private interface Token {
    }

    private static final class KeyToken implements Token {
        final String key;

        private KeyToken(String key) {
            this.key = key;
        }
    }

    private static final class IndexToken implements Token {
        final int index;

        private IndexToken(int index) {
            this.index = index;
        }
    }

    private record ResolvedField(ObjectNode parent, String key) {
    }

    private static final class InjectionBudget {
        int remainingBytes;

        private InjectionBudget(int remainingBytes) {
            this.remainingBytes = remainingBytes;
        }
    }

    private static List<Token> parsePath(String pathText) {
        String s = pathText == null ? "" : pathText.trim();
        if (s.isEmpty()) {
            throw new IllegalArgumentException("Path is empty");
        }
        if (s.startsWith("$.")) {
            s = s.substring(1);
        } else if (s.startsWith("$")) {
            s = s.substring(1);
        }
        if (s.startsWith(".")) {
            s = s.substring(1);
        }
        if (s.isEmpty()) {
            throw new IllegalArgumentException("Path is empty");
        }

        List<Token> tokens = new ArrayList<>();
        int i = 0;
        while (i < s.length()) {
            if (s.charAt(i) == '.') {
                i++;
                continue;
            }
            StringBuilder key = new StringBuilder();
            while (i < s.length()) {
                char ch = s.charAt(i);
                if (ch == '.' || ch == '[') {
                    break;
                }
                key.append(ch);
                i++;
            }
            if (key.isEmpty()) {
                throw new IllegalArgumentException("Invalid path near position " + (i + 1));
            }
            tokens.add(new KeyToken(key.toString()));

            while (i < s.length() && s.charAt(i) == '[') {
                i++; // [
                int start = i;
                while (i < s.length() && Character.isDigit(s.charAt(i))) {
                    i++;
                }
                if (i == start) {
                    throw new IllegalArgumentException("Array index must be an integer");
                }
                if (i >= s.length() || s.charAt(i) != ']') {
                    throw new IllegalArgumentException("Unclosed array index");
                }
                int idx = Integer.parseInt(s.substring(start, i));
                i++; // ]
                tokens.add(new IndexToken(idx));
            }
        }
        return tokens;
    }
}
