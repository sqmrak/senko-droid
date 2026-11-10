package com.senko.droid;

import static org.junit.Assert.assertEquals;

import org.junit.Test;

public final class FormatTest {
    @Test
    public void bytesPickTheLargestWholeUnit() {
        assertEquals("0 B", Format.bytes(0, Tr.ENGLISH));
        assertEquals("1023 B", Format.bytes(1023, Tr.ENGLISH));
        assertEquals("1.0 KB", Format.bytes(1024, Tr.ENGLISH));
        assertEquals("1.5 KB", Format.bytes(1536, Tr.ENGLISH));
        assertEquals("10 KB", Format.bytes(10 * 1024, Tr.ENGLISH));
        assertEquals("1.0 MB", Format.bytes(1024L * 1024, Tr.ENGLISH));
        assertEquals("2.5 GB", Format.bytes(5L * 1024 * 1024 * 1024 / 2, Tr.ENGLISH));
        assertEquals("a negative count shows as zero", "0 B", Format.bytes(-5, Tr.ENGLISH));
        assertEquals("the largest unit does not run out", "2048 TB",
                Format.bytes(2048L * 1024 * 1024 * 1024 * 1024, Tr.ENGLISH));
    }

    @Test
    public void russianUnitsAndDecimalComma() {
        assertEquals("1,5 КБ", Format.bytes(1536, Tr.RUSSIAN));
        assertEquals("512 Б", Format.bytes(512, Tr.RUSSIAN));
        assertEquals("3,0 ГБ", Format.bytes(3L * 1024 * 1024 * 1024, Tr.RUSSIAN));
    }

    @Test
    public void ratesAreInBitsAndFollowTheAmount() {
        assertEquals("0 bit/s", Format.rate(0, Tr.ENGLISH));
        assertEquals("0 bit/s", Format.rate(-4, Tr.ENGLISH));
        assertEquals("80 bit/s", Format.rate(10, Tr.ENGLISH));
        assertEquals("1.0 Kbit/s", Format.rate(125, Tr.ENGLISH));
        assertEquals("12 Kbit/s", Format.rate(1500, Tr.ENGLISH));
        assertEquals("8.0 Mbit/s", Format.rate(1_000_000, Tr.ENGLISH));
        assertEquals("1.2 Gbit/s", Format.rate(150_000_000, Tr.ENGLISH));
    }

    @Test
    public void uptimeIsMinutesThenHours() {
        assertEquals("0:00", Format.uptime(0));
        assertEquals("1:05", Format.uptime(65));
        assertEquals("59:59", Format.uptime(3599));
        assertEquals("1:00:00", Format.uptime(3600));
        assertEquals("25:01:01", Format.uptime(25 * 3600 + 61));
        assertEquals("", Format.uptime(-1));
    }

    @Test
    public void subscriptionNameIsThePanelHost() {
        assertEquals("panel.example", Format.subscriptionName("https://panel.example/sub/abc?x=1"));
        assertEquals("1.2.3.4", Format.subscriptionName("http://1.2.3.4:8080/s"));
        assertEquals("subscription", Format.subscriptionName("not a url"));
        assertEquals("subscription", Format.subscriptionName("https://"));
    }
}
