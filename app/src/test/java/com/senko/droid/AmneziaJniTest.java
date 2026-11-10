package com.senko.droid;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assume.assumeTrue;

import org.junit.Before;
import org.junit.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Paths;

/** the amnezia decoder of the engine, reached through the jni entry points. needs the host libsenko */
public final class AmneziaJniTest {
    @Before
    public void needsTheHostLibrary() {
        assumeTrue("host libsenko not built", !System.getProperty("senko.hostLibDir", "").isEmpty());
    }

    private static byte[] resource(String name) throws IOException {
        return Files.readAllBytes(Paths.get("src/test/resources/" + name));
    }

    @Test
    public void aShareLinkIsRecognisedAndDecoded() throws Exception {
        byte[] link = resource("amnezia-link.txt");
        assertTrue(Engine.nativeAmneziaLooksLike(link));
        String conf = Engine.nativeAmneziaExtract(link);
        assertNotNull(conf);
        assertTrue(conf, conf.contains("[Interface]") && conf.contains("[Peer]"));
        assertTrue(conf.endsWith("\n"));
    }

    @Test
    public void thePlainJsonBundleIsDecodedToo() throws Exception {
        byte[] json = resource("amnezia-plain.json");
        assertTrue(Engine.nativeAmneziaLooksLike(json));
        String conf = Engine.nativeAmneziaExtract(json);
        assertTrue(conf, conf.contains("PrivateKey = AAECAwQFBgcICQoLDA0ODxAREhMUFRYXGBkaGxwdHh8="));
    }

    @Test
    public void textThatIsNoBundleIsLeftToTheOtherPaths() throws Exception {
        byte[] other = "vless://id@host:443?security=tls#name".getBytes("UTF-8");
        assertFalse(Engine.nativeAmneziaLooksLike(other));
        assertNull(Engine.nativeAmneziaExtract(other));
        assertFalse(Engine.nativeAmneziaLooksLike(new byte[0]));
    }

    @Test
    public void aBrokenBundleSaysWhy() {
        try {
            String conf = Engine.nativeAmneziaExtract("vpn://AAAA".getBytes("UTF-8"));
            assertNull("a bundle that does not decode is refused or ignored: " + conf, conf);
        } catch (IOException expected) {
            assertFalse(expected.getMessage().isEmpty());
        }
    }

    @Test
    public void qrFramesAreCollectedByTheEngine() {
        long series = Engine.nativeQrNew();
        assertTrue(series != 0);
        try {
            assertEquals("not an amnezia frame", 0, Engine.nativeQrAdd(series, "https://example.com"));
            assertNull(Engine.nativeQrResult(series));
        } finally {
            Engine.nativeQrFree(series);
        }
        Engine.nativeQrFree(0);
    }
}
