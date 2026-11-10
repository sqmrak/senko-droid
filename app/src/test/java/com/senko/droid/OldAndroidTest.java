package com.senko.droid;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import org.junit.Test;

/** the pieces android 2.3 to 3.x run on: the tween, the root helper line and the helper's client */
public class OldAndroidTest {
    @Test
    public void tweenMixesColorsChannelByChannel() {
        assertEquals(0xff000000, Tween.mix(0xff000000, 0xffffffff, 0f));
        assertEquals(0xffffffff, Tween.mix(0xff000000, 0xffffffff, 1f));
        assertEquals(0x7f7f7f7f, Tween.mix(0x00000000, 0xfefefefe, 0.5f));
        assertEquals(0x7fff0000, Tween.mix(0x00ff0000, 0xfeff0000, 0.5f));
    }

    @Test
    public void rootHelperSearchesSystemPathsOnlyAfterLaunchFailure() throws IOException {
        final List<String> tried = new ArrayList<String>();
        final Process found = new Process() {
            @Override public java.io.OutputStream getOutputStream() { return null; }
            @Override public java.io.InputStream getInputStream() { return null; }
            @Override public java.io.InputStream getErrorStream() { return null; }
            @Override public int waitFor() { return 0; }
            @Override public int exitValue() { return 0; }
            @Override public void destroy() {}
        };
        Process result = RootHelper.startSu(new RootHelper.SuLauncher() {
            @Override public Process launch(String path) throws IOException {
                tried.add(path);
                if (path.equals("/system/xbin/su")) return found;
                throw new IOException("not found");
            }
        });
        assertTrue(result == found);
        assertEquals(2, tried.size());
        assertEquals("su", tried.get(0));
        assertEquals("/system/xbin/su", tried.get(1));
    }

    @Test
    public void missingSuReportsFailure() {
        try {
            RootHelper.startSu(new RootHelper.SuLauncher() {
                @Override public Process launch(String path) throws IOException {
                    throw new IOException("cannot execute " + path);
                }
            });
            fail("started without su");
        } catch (IOException expected) {
            assertTrue(expected.getMessage().contains("su could not be started"));
            assertNotNull(expected.getCause());
        }
    }

    @Test
    public void rootHelperLineTakesOnlyPlainPathCharacters() throws IOException {
        assertEquals("/data/app-lib/com.senko.droid-1/libsenkorootd.so",
                RootHelper.shellWord("/data/app-lib/com.senko.droid-1/libsenkorootd.so"));
        for (String bad : new String[] { "/data/a b", "/data/a;reboot", "/data/$(id)", "/data/a\"b", "/data/a'b",
                "/data/a\nb", "/data/a`b`", "" + 'é' }) {
            try {
                RootHelper.shellWord(bad);
                fail("accepted " + bad);
            } catch (IOException expected) {
                assertTrue(expected.getMessage().contains("root helper"));
            }
        }
    }

    @Test
    public void theHelperClientSaysWhyWhenNothingListens() {
        String why = Engine.nativeRootPing("/tmp/senko-no-such-rootd.sock");
        assertNotNull(why);
        assertTrue(why, why.contains("not running"));
        assertNotNull(Engine.nativeRootProtect("/tmp/senko-no-such-rootd.sock", 0));
        try {
            Engine.nativeRootOpenTun("/tmp/senko-no-such-rootd.sock", "198.18.0.1", 32, "1.1.1.1", 1500);
            fail("a tun came from nowhere");
        } catch (IOException expected) {
            assertTrue(expected.getMessage(), expected.getMessage().contains("not running"));
        }
        try {
            Engine.nativeRootOpenTun("/tmp/senko-no-such-rootd.sock", "198.18.0.1", 99, "1.1.1.1", 1500);
            fail("a prefix of 99 was taken");
        } catch (IOException expected) {
            assertTrue(expected.getMessage().contains("out of range"));
        }
    }
}
