package com.mejorarfotos.app;

import android.graphics.Bitmap;
import android.graphics.Color;

/**
 * Local photo-restoration pipeline. It is intentionally independent from the UI so a
 * Real-ESRGAN/NCNN model can be plugged in without changing the editor workflow.
 */
public final class ImageEnhancer {
    private ImageEnhancer() {}

    public static Bitmap enhance(Bitmap input, int scaleFactor, int profile, int noise, int detail, int sharpen) {
        int maxInput = scaleFactor >= 4 ? 900 : 1400;
        Bitmap working = fit(input, maxInput);
        Bitmap scaled = Bitmap.createScaledBitmap(working, Math.max(1, working.getWidth() * scaleFactor),
                Math.max(1, working.getHeight() * scaleFactor), true);
        if (working != input) working.recycle();
        return restore(scaled, profile, noise / 100f, detail / 100f, sharpen / 100f);
    }

    private static Bitmap fit(Bitmap source, int longestSide) {
        int longest = Math.max(source.getWidth(), source.getHeight());
        if (longest <= longestSide) return source;
        float f = longestSide / (float) longest;
        return Bitmap.createScaledBitmap(source, Math.max(1, Math.round(source.getWidth() * f)),
                Math.max(1, Math.round(source.getHeight() * f)), true);
    }

    private static Bitmap restore(Bitmap source, int profile, float noise, float detail, float sharpen) {
        int width = source.getWidth(), height = source.getHeight();
        int[] src = new int[width * height], out = new int[width * height];
        source.getPixels(src, 0, width, 0, 0, width, height);
        // Profiles behave like practical restoration presets: portraits are protected
        // from hard halos, while text gets stronger edges and local contrast.
        if (profile == 1) { noise = Math.max(noise, .32f); sharpen *= .74f; detail *= .84f; }
        if (profile == 2) { detail *= 1.15f; sharpen *= 1.08f; }
        if (profile == 3) { detail *= 1.24f; sharpen *= 1.18f; noise = Math.max(noise, .22f); }
        float contrast = 1f + detail * .12f;
        for (int y = 0; y < height; y++) {
            int ym = Math.max(0, y - 1), yp = Math.min(height - 1, y + 1);
            for (int x = 0; x < width; x++) {
                int xm = Math.max(0, x - 1), xp = Math.min(width - 1, x + 1);
                int center = src[y * width + x];
                int left = src[y * width + xm], right = src[y * width + xp];
                int top = src[ym * width + x], bottom = src[yp * width + x];
                int averageR = (Color.red(left) + Color.red(right) + Color.red(top) + Color.red(bottom)) / 4;
                int averageG = (Color.green(left) + Color.green(right) + Color.green(top) + Color.green(bottom)) / 4;
                int averageB = (Color.blue(left) + Color.blue(right) + Color.blue(top) + Color.blue(bottom)) / 4;
                int r = restoreChannel(Color.red(center), averageR, noise, detail, sharpen, contrast);
                int g = restoreChannel(Color.green(center), averageG, noise, detail, sharpen, contrast);
                int b = restoreChannel(Color.blue(center), averageB, noise, detail, sharpen, contrast);
                out[y * width + x] = Color.argb(Color.alpha(center), r, g, b);
            }
        }
        Bitmap result = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888);
        result.setPixels(out, 0, width, 0, 0, width, height); source.recycle(); return result;
    }

    private static int restoreChannel(int original, int average, float noise, float detail, float sharpen, float contrast) {
        // Gentle denoise first, then unsharp masking around the local average.
        float clean = original + (average - original) * noise * .28f;
        float edge = clean - average;
        float restored = clean + edge * (sharpen * 1.65f + detail * .72f);
        return clamp(Math.round((restored - 128f) * contrast + 128f));
    }

    private static int clamp(int value) { return Math.max(0, Math.min(255, value)); }
}
