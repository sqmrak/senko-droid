package com.senko.droid;

import java.io.ByteArrayOutputStream;
import java.io.IOException;

/**
 * standard base64 as the engine writes it (b64_encode in core/b64.c). android's
 * own decoder is not available to a jvm test, and java.util.Base64 needs api 26
 */
final class Base64Decoder {
    private Base64Decoder() {}

    static byte[] decode(String text) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream(text.length() * 3 / 4);
        int buffer = 0;
        int bits = 0;
        int padding = 0;
        for (int i = 0; i < text.length(); ++i) {
            char c = text.charAt(i);
            if (c == '=') {
                padding++;
                continue;
            }
            if (padding > 0) throw new IOException("base64 data after padding");
            int value = value(c);
            if (value < 0) throw new IOException("not base64: " + c);
            buffer = (buffer << 6) | value;
            bits += 6;
            if (bits >= 8) {
                bits -= 8;
                out.write((buffer >> bits) & 0xff);
                buffer &= (1 << bits) - 1;
            }
        }
        if (padding > 2 || (bits >= 6)) throw new IOException("truncated base64");
        return out.toByteArray();
    }

    private static int value(char c) {
        if (c >= 'A' && c <= 'Z') return c - 'A';
        if (c >= 'a' && c <= 'z') return c - 'a' + 26;
        if (c >= '0' && c <= '9') return c - '0' + 52;
        if (c == '+') return 62;
        if (c == '/') return 63;
        return -1;
    }
}
