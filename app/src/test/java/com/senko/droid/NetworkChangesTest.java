package com.senko.droid;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public class NetworkChangesTest {
    @Test
    public void reportsActualTransitionsButNotDuplicateBroadcasts() {
        NetworkChanges changes = new NetworkChanges();
        assertFalse(changes.update(1, "wifi", true));
        assertFalse(changes.update(1, "wifi", true));
        assertFalse(changes.update(0, "mobile", false));
        assertTrue(changes.update(0, "mobile", true));
        assertFalse(changes.update(0, "mobile", false));
        assertTrue(changes.update(0, "mobile", true));
        assertFalse(changes.update(0, "mobile", true));
        assertTrue(changes.update(1, "wifi", true));
    }
}
