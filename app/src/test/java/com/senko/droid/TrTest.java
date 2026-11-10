package com.senko.droid;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import java.io.FileInputStream;
import java.io.IOException;

public final class TrTest {
    private static final String ASSETS = "src/main/assets/";

    @Before
    public void load() throws IOException {
        Tr.clear();
        FileInputStream table = new FileInputStream(ASSETS + "i18n.tsv");
        try {
            Tr.load(table);
        } finally {
            table.close();
        }
        FileInputStream extra = new FileInputStream(ASSETS + "i18n_extra.tsv");
        try {
            Tr.load(extra);
        } finally {
            extra.close();
        }
        FileInputStream errors = new FileInputStream(ASSETS + "errors.tsv");
        try {
            Tr.loadErrors(errors);
        } finally {
            errors.close();
        }
    }

    @After
    public void reset() {
        Tr.setLanguage(Tr.ENGLISH);
    }

    @Test
    public void englishIsTheKeyAndTheDefault() {
        assertEquals("Language", Tr.t("Language"));
        assertEquals("a sentence nobody translated", Tr.t("a sentence nobody translated"));
    }

    @Test
    public void russianAndChineseCome_fromTheTable() {
        Tr.setLanguage(Tr.RUSSIAN);
        assertEquals("Язык", Tr.t("Language"));
        Tr.setLanguage(Tr.CHINESE);
        assertEquals("语言", Tr.t("Language"));
    }

    @Test
    public void formatHolesSurviveTheConversionFromObjectiveC() {
        Tr.setLanguage(Tr.RUSSIAN);
        assertEquals("Не хватает списков: 3", Tr.f("%d lists missing", 3));
        Tr.setLanguage(Tr.ENGLISH);
        assertEquals("3 lists missing", Tr.f("%d lists missing", 3));
    }

    @Test
    public void multiLineSentencesKeepTheirBreaks() {
        Tr.setLanguage(Tr.RUSSIAN);
        String text = Tr.t("QR code not detected\nfill the frame with the code\nand hold the phone still");
        assertTrue(text, text.startsWith("QR-код не обнаружен\n"));
    }

    @Test
    public void engineErrorsAreTranslatedWithTheirHoles() {
        Tr.setLanguage(Tr.RUSSIAN);
        assertEquals("Сервер отклонил соединение на порту 443. Проверьте порт и запущен ли сервер.",
                Errors.human("ERR the server refused the connection on port 443: check the port and whether the server is running"));
        Tr.setLanguage(Tr.CHINESE);
        assertEquals("服务器拒绝了端口 443 的连接。请检查端口及服务器是否运行。",
                Errors.human("the server refused the connection on port 443: check the port and whether the server is running"));
        Tr.setLanguage(Tr.ENGLISH);
        assertEquals("the server refused the connection on port 443: check the port and whether the server is running",
                Errors.human("the server refused the connection on port 443: check the port and whether the server is running"));
    }

    @Test
    public void aServerPrefixIsDroppedBeforeTheLookup() {
        Tr.setLanguage(Tr.RUSSIAN);
        assertEquals("Не удалось найти сервер по имени. Проверьте адрес и доступ к интернету.",
                Errors.human("ERR server: the server name does not resolve: check the address and the internet connection"));
    }

    @Test
    public void reasonsOfTheEngineBecomeActionableSentences() {
        assertEquals("The server name could not be resolved. Check the internet connection and server address.",
                Errors.human("ERR server: dns resolution failed"));
        assertEquals("The tunnel could not be opened. The VPN permission may have been withdrawn: connect again.",
                Errors.human("ERR tunnel: cannot open the tunnel: the vpn permission was revoked"));
        Tr.setLanguage(Tr.RUSSIAN);
        assertEquals("Не удалось открыть туннель. Возможно, разрешение VPN отозвано: подключитесь снова.",
                Errors.human("ERR tunnel: cannot open the tunnel: the vpn permission was revoked"));
    }

    @Test
    public void aGatedSubscriptionKeepsThePanelsOwnWords() {
        assertEquals("The subscription refused this device. update your app",
                Errors.human("subscription refused this device: update your app"));
    }

    @Test
    public void unknownTextPassesThrough() {
        assertEquals("something new", Errors.human("something new"));
        assertEquals("", Errors.human(null));
    }

    @Test
    public void russianPluralsFollowTheRule() {
        assertEquals("1 час", ruHours(1));
        assertEquals("2 часа", ruHours(2));
        assertEquals("5 часов", ruHours(5));
        assertEquals("11 часов", ruHours(11));
        assertEquals("21 час", ruHours(21));
        assertEquals("22 часа", ruHours(22));
        Tr.setLanguage(Tr.ENGLISH);
        assertEquals("1 hour", Tr.hours(1));
        assertEquals("2 hours", Tr.hours(2));
        Tr.setLanguage(Tr.CHINESE);
        assertEquals("3 小时", Tr.hours(3));
    }

    private static String ruHours(int n) {
        Tr.setLanguage(Tr.RUSSIAN);
        return Tr.hours(n);
    }

    @Test
    public void russianTranslatesTheCountsTheEngineReports() {
        Tr.setLanguage(Tr.RUSSIAN);
        assertEquals("импортировано 3 сервера", Tr.t("imported 3 server(s)"));
        assertEquals("удалено 1 сервер", Tr.t("removed 1 server(s)"));
        assertEquals("12 серверов", Tr.t("12 servers"));
        assertEquals("не удалось загрузить подписку: panel.example ответил HTTP 503",
                Tr.t("fetch failed: panel.example answered HTTP 503"));
        assertEquals("42 мс", Tr.t("42 ms"));
        assertEquals("не удалось загрузить подписку: l.example не ответил на TLS-рукопожатие вовремя: "
                + "возможно, сеть блокирует соединение с этим адресом",
                Tr.t("fetch failed: l.example did not answer the tls handshake in time"));
        assertEquals("не удалось загрузить подписку: l.example принял запрос, но не ответил вовремя",
                Tr.t("fetch failed: l.example took the request but did not answer in time"));
        assertEquals("не удалось загрузить подписку: l.example не успел ответить: HTTP 200 (1024 body bytes)",
                Tr.t("fetch failed: l.example timed out after HTTP 200 (1024 body bytes)"));
        Tr.setLanguage(Tr.CHINESE);
        assertEquals("imported 3 server(s)", Tr.t("imported 3 server(s)"));
    }

    @Test
    public void secretsAreRedactedFromSharedText() {
        String text = Errors.redact("GET /x?uuid=abcd&pbk=KEY HTTP/1.1\nAuthorization: Bearer abc\nvless://id@host:443");
        assertTrue(text, !text.contains("abcd") && !text.contains("KEY") && !text.contains("Bearer"));
        assertTrue(text, text.contains("<redacted>@host:443"));
    }
}
