package com.mejorarfotos.app;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public class ImageQualityGuardTest {
    @Test
    public void focusScoreSeparatesFlatAndDetailedImages() {
        int[] flat = new int[25];
        int[] checker = new int[25];
        for (int i = 0; i < 25; i++) {
            flat[i] = 120;
            checker[i] = ((i / 5 + i % 5) & 1) == 0 ? 20 : 230;
        }
        assertEquals(0f, ImageQualityGuard.focusScoreForLuma(flat, 5, 5), 0f);
        assertTrue(ImageQualityGuard.focusScoreForLuma(checker, 5, 5) > 100f);
    }

    @Test
    public void neuralReconstructionIsActuallyTransferred() {
        int baseline = argb(255, 160, 150, 140);
        int neural = argb(255, 245, 105, 65);
        int protectedPixel = ImageQualityGuard.blendNeuralCandidate(
                baseline, neural, .90f, 56);
        assertEquals(210, red(protectedPixel));
        assertEquals(110, green(protectedPixel));
        assertEquals(90, blue(protectedPixel));
    }

    @Test
    public void protectionDoesNotCreateNewClipping() {
        int baseline = argb(255, 245, 244, 243);
        int neural = argb(255, 255, 255, 255);
        int protectedPixel = ImageQualityGuard.blendNeuralCandidate(
                baseline, neural, .90f, 56);
        assertTrue(red(protectedPixel) <= 254);
        assertTrue(green(protectedPixel) <= 254);
        assertTrue(blue(protectedPixel) <= 254);
    }

    @Test
    public void identicalPixelsRemainIdentical() {
        int pixel = argb(255, 43, 127, 211);
        assertEquals(pixel, ImageQualityGuard.blendNeuralCandidate(
                pixel, pixel, .90f, 56));
    }

    private static int argb(int a, int r, int g, int b) {
        return (a << 24) | (r << 16) | (g << 8) | b;
    }

    private static int red(int color) { return (color >>> 16) & 0xff; }
    private static int green(int color) { return (color >>> 8) & 0xff; }
    private static int blue(int color) { return color & 0xff; }
}
