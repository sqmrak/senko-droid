package com.senko.droid;

import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

import android.content.Context;
import android.graphics.drawable.BitmapDrawable;
import android.graphics.drawable.Drawable;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.ConscryptMode;

@RunWith(RobolectricTestRunner.class)
@ConscryptMode(ConscryptMode.Mode.OFF)
@Config(sdk = {19, 28})
public class MaterialIconsTest {
    @Test
    public void everyThemeUsesMaterialSymbols() {
        Context context = RuntimeEnvironment.getApplication();
        for (String theme : new String[] { "senko-material", "senko-ios6", "senko-holo" }) {
            Theme.apply(theme, false, 0);
            Drawable gear = Icons.glyph(context, "gear", 0xff123456);
            Drawable expected = Icons.resource(context, "material_glyph_gear", 0xff123456);
            assertTrue(gear instanceof BitmapDrawable);
            assertNotNull(expected);
            assertSame(((BitmapDrawable) expected).getBitmap(), ((BitmapDrawable) gear).getBitmap());
            for (String name : new String[] { "gear", "chart", "cloud", "shield", "bolt", "globe",
                    "sort", "search", "logs", "palette", "info", "clock", "download", "arrow-down",
                    "arrow-up", "repeat", "reconnect", "shuffle", "split", "backup", "code", "port",
                    "hash", "rocket", "snow", "power" })
                assertNotNull(name, Icons.glyph(context, name, 0xff123456));
            for (Icons.Shape shape : Icons.Shape.values())
                assertNotNull(shape.name(), Icons.shape(context, shape, 0xff123456));
        }
    }
}
