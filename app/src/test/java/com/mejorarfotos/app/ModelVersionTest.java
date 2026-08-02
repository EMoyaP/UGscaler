package com.mejorarfotos.app;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public class ModelVersionTest {
    @Test public void detectsNewerModel() {
        assertTrue(ModelVersion.compare("1.1.0", "1.0.9") > 0);
    }

    @Test public void acceptsVPrefixAndMissingPatch() {
        assertEquals(0, ModelVersion.compare("v2.1", "2.1.0"));
    }

    @Test public void doesNotOfferOlderModel() {
        assertTrue(ModelVersion.compare("1.0.0", "1.2.0") < 0);
    }
}
