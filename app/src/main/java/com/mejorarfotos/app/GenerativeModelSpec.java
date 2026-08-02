package com.mejorarfotos.app;

import org.json.JSONObject;

final class GenerativeModelSpec {
    final String version;
    final String name;
    final String url;
    final String sha256;
    final long bytes;

    GenerativeModelSpec(JSONObject value) throws Exception {
        version = required(value, "version");
        name = required(value, "name");
        url = required(value, "url");
        sha256 = required(value, "sha256").toLowerCase();
        bytes = value.getLong("bytes");
        if (!sha256.matches("[0-9a-f]{64}") || bytes < 500_000_000L) {
            throw new IllegalArgumentException("Catálogo generativo inválido");
        }
    }

    private static String required(JSONObject value, String key) throws Exception {
        String result = value.getString(key).trim();
        if (result.isEmpty()) throw new IllegalArgumentException("Campo vacío: " + key);
        return result;
    }
}
