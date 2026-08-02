package com.mejorarfotos.app;

import org.json.JSONObject;

/** Immutable entry in the signed-by-hash UGscaler model catalogue. */
final class ModelSpec {
    final String id;
    final String name;
    final String description;
    final String version;
    final String url;
    final String sha256;
    final String binSha256;
    final String paramSha256;
    final long bytes;

    ModelSpec(JSONObject json) throws Exception {
        id = required(json, "id");
        name = required(json, "name");
        description = required(json, "description");
        version = required(json, "version");
        url = required(json, "url");
        sha256 = hash(required(json, "sha256"));
        binSha256 = hash(required(json, "binSha256"));
        paramSha256 = hash(required(json, "paramSha256"));
        bytes = json.getLong("bytes");
        if (!"bsrgan-general".equals(id) || bytes <= 0L) {
            throw new IllegalArgumentException("Modelo no compatible");
        }
    }

    private static String required(JSONObject json, String key) throws Exception {
        String value = json.getString(key).trim();
        if (value.isEmpty()) throw new IllegalArgumentException("Campo vacío: " + key);
        return value;
    }

    private static String hash(String value) {
        String normalized = value.toLowerCase();
        if (!normalized.matches("[0-9a-f]{64}")) {
            throw new IllegalArgumentException("SHA-256 inválido");
        }
        return normalized;
    }
}
