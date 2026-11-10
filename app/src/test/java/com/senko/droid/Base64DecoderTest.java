package com.senko.droid;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.fail;

import org.junit.Test;

import java.io.IOException;

public final class Base64DecoderTest {
    private static byte[] ascii(String text) throws Exception {
        return text.getBytes("US-ASCII");
    }

    @Test
    public void rfc4648Vectors() throws Exception {
        assertArrayEquals(ascii(""), Base64Decoder.decode(""));
        assertArrayEquals(ascii("f"), Base64Decoder.decode("Zg=="));
        assertArrayEquals(ascii("fo"), Base64Decoder.decode("Zm8="));
        assertArrayEquals(ascii("foo"), Base64Decoder.decode("Zm9v"));
        assertArrayEquals(ascii("foob"), Base64Decoder.decode("Zm9vYg=="));
        assertArrayEquals(ascii("fooba"), Base64Decoder.decode("Zm9vYmE="));
        assertArrayEquals(ascii("foobar"), Base64Decoder.decode("Zm9vYmFy"));
    }

    @Test
    public void everyByteValueSurvives() throws Exception {
        byte[] all = new byte[256];
        for (int i = 0; i < all.length; ++i) all[i] = (byte) i;
        String encoded = java.util.Base64.getEncoder().encodeToString(all);
        assertArrayEquals(all, Base64Decoder.decode(encoded));
    }

    @Test
    public void badInputIsRefused() throws Exception {
        String[] bad = { "Zm9v!", "Z", "Zg=x", "=Zg=", "Zm9vYg===" };
        for (String text : bad) {
            try {
                Base64Decoder.decode(text);
                fail("accepted: " + text);
            } catch (IOException expected) {
                // the message names the fault
            }
        }
    }
}
