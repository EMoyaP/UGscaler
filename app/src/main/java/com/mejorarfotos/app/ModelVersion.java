package com.mejorarfotos.app;

/** Small dependency-free semantic version comparator used by the model catalogue. */
public final class ModelVersion {
    private ModelVersion() {}

    public static int compare(String left, String right) {
        String[] a = normalize(left).split("\\.");
        String[] b = normalize(right).split("\\.");
        int count = Math.max(a.length, b.length);
        for (int index = 0; index < count; index++) {
            int av = index < a.length ? number(a[index]) : 0;
            int bv = index < b.length ? number(b[index]) : 0;
            if (av != bv) return Integer.compare(av, bv);
        }
        return 0;
    }

    private static String normalize(String value) {
        if (value == null || value.trim().isEmpty()) return "0";
        return value.trim().replaceFirst("^[vV]", "").split("[-+]", 2)[0];
    }

    private static int number(String value) {
        String digits = value.replaceAll("[^0-9].*$", "");
        if (digits.isEmpty()) return 0;
        try {
            return Integer.parseInt(digits);
        } catch (NumberFormatException ignored) {
            return Integer.MAX_VALUE;
        }
    }
}
