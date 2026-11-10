package com.senko.droid;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.json.JSONObject;
import org.junit.Test;

public class CustomThemesTest {
    private static String palette(String color) {
        StringBuilder out = new StringBuilder("{");
        for (int i = 0; i < CustomThemes.KEYS.length; ++i) {
            if (i > 0) out.append(',');
            out.append('"').append(CustomThemes.KEYS[i]).append("\":\"").append(color).append('"');
        }
        return out.append('}').toString();
    }

    private static JSONObject theme(String extra) throws Exception {
        return new JSONObject("{\"name\":\"Mine\",\"light\":" + palette("#102030") + extra + "}");
    }

    @Test
    public void hexAcceptsSixDigitsWithOrWithoutHash() {
        assertEquals(Integer.valueOf(0xff102030), CustomThemes.parseHex("#102030"));
        assertEquals(Integer.valueOf(0xffabcdef), CustomThemes.parseHex(" ABCDEF "));
    }

    @Test
    public void hexRejectsEverythingElse() {
        assertNull(CustomThemes.parseHex(null));
        assertNull(CustomThemes.parseHex(""));
        assertNull(CustomThemes.parseHex("#12345"));
        assertNull(CustomThemes.parseHex("#1234567"));
        assertNull(CustomThemes.parseHex("#12345g"));
        assertNull(CustomThemes.parseHex("#-12345"));
    }

    @Test
    public void hexRoundTrips() {
        assertEquals("#0A0B0C", CustomThemes.hex(0xff0a0b0c));
        assertEquals(Integer.valueOf(0xff0a0b0c), CustomThemes.parseHex(CustomThemes.hex(0xff0a0b0c)));
    }

    @Test
    public void aThemeNeedsAName() throws Exception {
        assertNull(CustomThemes.fromJson(new JSONObject("{\"light\":" + palette("#000000") + "}")));
        assertNull(CustomThemes.fromJson(new JSONObject("{\"name\":\"  \",\"light\":" + palette("#000000") + "}")));
    }

    @Test
    public void aThemeNeedsEverySlot() throws Exception {
        JSONObject broken = theme("");
        broken.getJSONObject("light").remove("well");
        assertNull(CustomThemes.fromJson(broken));
        JSONObject badColor = theme("");
        badColor.getJSONObject("light").put("ink", "red");
        assertNull(CustomThemes.fromJson(badColor));
    }

    @Test
    public void aBrokenDarkPaletteRejectsTheTheme() throws Exception {
        assertNull(CustomThemes.fromJson(theme(",\"dark\":{\"bg\":\"#000000\"}")));
    }

    @Test
    public void valuesAreClamped() throws Exception {
        CustomThemes.Draft draft = CustomThemes.fromJson(theme(",\"style\":9,\"radius\":500"));
        assertNotNull(draft);
        assertEquals(CustomThemes.STYLE_FLAT, draft.style);
        assertEquals(32f, draft.radius, 0f);
        CustomThemes.Draft negative = CustomThemes.fromJson(theme(",\"radius\":-4"));
        assertEquals(0f, negative.radius, 0f);
    }

    @Test
    public void aLongNameIsCut() throws Exception {
        StringBuilder name = new StringBuilder();
        for (int i = 0; i < 80; ++i) name.append('x');
        JSONObject raw = theme("");
        raw.put("name", name.toString());
        assertEquals(40, CustomThemes.fromJson(raw).name.length());
    }

    @Test
    public void onlyOurIdsSurvive() throws Exception {
        assertEquals("custom-1a", CustomThemes.fromJson(theme(",\"id\":\"custom-1a\"")).id);
        assertEquals("", CustomThemes.fromJson(theme(",\"id\":\"senko-ios6\"")).id);
    }

    @Test
    public void jsonRoundTripKeepsBothPalettes() throws Exception {
        CustomThemes.Draft draft = CustomThemes.fromJson(theme(",\"dark\":" + palette("#405060") + ",\"style\":2"));
        CustomThemes.Draft again = CustomThemes.fromJson(CustomThemes.toJson(draft));
        assertEquals(0xff102030, again.light[3]);
        assertEquals(0xff405060, again.dark[15]);
        assertEquals(CustomThemes.STYLE_GLASS, again.style);
    }

    @Test
    public void aLightOnlyThemeIsMarkedSo() throws Exception {
        Theme.Def def = CustomThemes.toDef(CustomThemes.fromJson(theme("")));
        assertTrue(def.lightOnly());
        assertEquals("custom", def.group);
    }

    @Test
    public void styleDecidesTheFlags() throws Exception {
        CustomThemes.Draft classic = CustomThemes.fromJson(theme(",\"style\":0,\"dark\":" + palette("#000000")));
        Theme.Def def = CustomThemes.toDef(classic);
        assertFalse(def.flat());
        assertFalse(def.glass());
        CustomThemes.Draft glass = CustomThemes.fromJson(theme(",\"style\":2,\"dark\":" + palette("#000000")));
        assertTrue(CustomThemes.toDef(glass).glass());
    }

    @Test
    public void customIdsAreRecognised() {
        assertTrue(CustomThemes.isCustom("custom-12ab"));
        assertFalse(CustomThemes.isCustom("senko-ios6"));
        assertFalse(CustomThemes.isCustom(null));
    }
}
