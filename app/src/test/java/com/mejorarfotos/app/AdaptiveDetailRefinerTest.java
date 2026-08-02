package com.mejorarfotos.app;

import static org.junit.Assert.assertEquals;

import org.junit.Test;

public class AdaptiveDetailRefinerTest {
    @Test public void ignoresTinyDifferences() {
        assertEquals(100, AdaptiveDetailRefiner.refineChannel(100, 99, 101, 100, 100));
    }

    @Test public void limitsPositiveDetail() {
        assertEquals(112, AdaptiveDetailRefiner.refineChannel(100, 20, 20, 20, 20));
    }

    @Test public void limitsNegativeDetail() {
        assertEquals(88, AdaptiveDetailRefiner.refineChannel(100, 220, 220, 220, 220));
    }

    @Test public void clampsChannels() {
        assertEquals(255, AdaptiveDetailRefiner.refineChannel(250, 0, 0, 0, 0));
        assertEquals(0, AdaptiveDetailRefiner.refineChannel(5, 255, 255, 255, 255));
    }
}
