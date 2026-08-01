package com.mejorarfotos.app;

import static org.junit.Assert.assertEquals;

import org.junit.Test;

public class ProcessingMemoryTest {
    @Test public void smallCropUsesMaximumFourTimesScale() {
        assertEquals(4, ProcessingMemory.recommendedUpscaleFor(256, 640, 480));
    }

    @Test public void largeOutputFallsBackToTwoTimesOnLimitedDevice() {
        assertEquals(2, ProcessingMemory.recommendedUpscaleFor(256, 1000, 1000));
    }

    @Test public void highMemoryDeviceCanUseFourTimesForOneMegapixel() {
        assertEquals(4, ProcessingMemory.recommendedUpscaleFor(512, 1000, 1000));
    }

    @Test public void longImageAvoidsUnhelpfulFourTimesScale() {
        assertEquals(2, ProcessingMemory.recommendedUpscaleFor(512, 1001, 600));
    }

    @Test public void deblurContextScalesWithHeapClass() {
        assertEquals(960, ProcessingMemory.deblurInputMaxSideFor(256));
        assertEquals(1280, ProcessingMemory.deblurInputMaxSideFor(384));
        assertEquals(1600, ProcessingMemory.deblurInputMaxSideFor(512));
    }

    @Test public void generativeAspectRatioChoosesNearestSupportedFormat() {
        assertEquals("9:16", GeminiImageRestorer.closestAspectRatio(1080, 1920));
        assertEquals("3:2", GeminiImageRestorer.closestAspectRatio(1500, 1000));
        assertEquals("1:1", GeminiImageRestorer.closestAspectRatio(1000, 980));
    }
}
