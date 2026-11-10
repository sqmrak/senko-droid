package com.senko.droid;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public class BackupSniffTest {
    private static byte[] bytes(String text) throws Exception {
        return text.getBytes("UTF-8");
    }

    @Test
    public void aConfigFileIsABackup() throws Exception {
        assertTrue(SettingsActivity.looksLikeBackup(bytes("SET dns_upstream 1.1.1.1\nSRV -1 vless://x\n")));
    }

    @Test
    public void aVersionLineBeforeTheKeywordsIsAllowed() throws Exception {
        assertTrue(SettingsActivity.looksLikeBackup(bytes("# senko 4\nORDER -1 0\n")));
    }

    @Test
    public void otherTextIsNot() throws Exception {
        assertFalse(SettingsActivity.looksLikeBackup(bytes("[Interface]\nPrivateKey = x\n")));
        assertFalse(SettingsActivity.looksLikeBackup(bytes("")));
        assertFalse(SettingsActivity.looksLikeBackup(bytes("SETTINGS everything\n")));
    }

    @Test
    public void onlyTheHeadIsRead() throws Exception {
        StringBuilder text = new StringBuilder();
        for (int i = 0; i < 5000; ++i) text.append("x");
        text.append("\nSET a 1\n");
        assertFalse(SettingsActivity.looksLikeBackup(bytes(text.toString())));
    }
}
