package com.senko.droid;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * the text of the screens, the way the ios app does it: the english sentence is
 * the key, and a table gives its russian and chinese. a sentence without an
 * entry shows in english, which is also what an unknown reply of the engine
 * looks like. pure java, so a jvm test covers it
 */
final class Tr {
    static final int ENGLISH = 0;
    static final int RUSSIAN = 1;
    static final int CHINESE = 2;

    private Tr() {}

    private static final Map<String, String[]> TABLE = new HashMap<String, String[]>();

    /** an engine sentence with %s holes, matched as a whole */
    private static final class Template {
        final Pattern pattern;
        final String[] translated; // [russian, chinese]

        Template(Pattern pattern, String[] translated) {
            this.pattern = pattern;
            this.translated = translated;
        }
    }

    private static final List<Template> TEMPLATES = new ArrayList<Template>();
    private static final Map<String, String[]> SENTENCES = new HashMap<String, String[]>();
    private static volatile int language = ENGLISH;

    static void setLanguage(int value) {
        language = value < ENGLISH || value > CHINESE ? ENGLISH : value;
    }

    static int language() {
        return language;
    }

    /** tab separated: english, russian, chinese. \n and \t are escaped. repeated loads add to the table */
    static synchronized void load(InputStream in) throws IOException {
        BufferedReader reader = new BufferedReader(new InputStreamReader(in, "UTF-8"));
        for (String line = reader.readLine(); line != null; line = reader.readLine()) {
            String[] parts = line.split("\t", -1);
            if (parts.length != 3 || parts[0].isEmpty()) continue;
            TABLE.put(unescape(parts[0]), new String[] { unescape(parts[1]), unescape(parts[2]) });
        }
    }

    /** the engine's own error sentences, the same three columns */
    static synchronized void loadErrors(InputStream in) throws IOException {
        BufferedReader reader = new BufferedReader(new InputStreamReader(in, "UTF-8"));
        for (String line = reader.readLine(); line != null; line = reader.readLine()) {
            String[] parts = line.split("\t", -1);
            if (parts.length != 3 || parts[0].isEmpty()) continue;
            String english = unescape(parts[0]);
            String[] translated = { unescape(parts[1]), unescape(parts[2]) };
            if (english.contains("%s")) {
                TEMPLATES.add(new Template(Pattern.compile(
                        "^" + Pattern.quote(english).replace("%s", "\\E(.+?)\\Q") + "$", Pattern.DOTALL),
                        translated));
            } else {
                SENTENCES.put(english, translated);
            }
        }
    }

    static synchronized void clear() {
        TABLE.clear();
        TEMPLATES.clear();
        SENTENCES.clear();
    }

    private static String unescape(String text) {
        if (text.indexOf('\\') < 0) return text;
        StringBuilder out = new StringBuilder(text.length());
        for (int i = 0; i < text.length(); ++i) {
            char c = text.charAt(i);
            if (c == '\\' && i + 1 < text.length()) {
                char next = text.charAt(++i);
                out.append(next == 'n' ? '\n' : next == 't' ? '\t' : next);
            } else {
                out.append(c);
            }
        }
        return out.toString();
    }

    /** the sentence in the chosen language */
    static String t(String english) {
        return in(english, language);
    }

    static String in(String english, int lang) {
        if (english == null || english.isEmpty()) return english;
        String error = errorSentence(english, lang);
        if (error != null) return error;
        String[] row;
        synchronized (Tr.class) {
            row = TABLE.get(english);
        }
        if (lang == CHINESE) return row != null && !row[1].isEmpty() ? row[1] : english;
        if (lang != RUSSIAN) return english;
        if (row != null && !row[0].isEmpty()) return row[0];
        return Dynamic.russian(english);
    }

    /** a sentence with %s and %d holes, formatted after the lookup */
    static String f(String english, Object... args) {
        return String.format(java.util.Locale.US, t(english), args);
    }

    private static String errorSentence(String english, int lang) {
        if (lang == ENGLISH) return null;
        synchronized (Tr.class) {
            String[] fixed = SENTENCES.get(english);
            if (fixed != null) return pick(fixed, lang);
            for (Template template : TEMPLATES) {
                Matcher match = template.pattern.matcher(english);
                if (!match.matches()) continue;
                Object[] args = new Object[match.groupCount()];
                for (int i = 0; i < args.length; ++i) {
                    String part = match.group(i + 1);
                    String inner = errorSentence(part, lang);
                    args[i] = inner != null ? inner : part;
                }
                return String.format(java.util.Locale.US, pick(template.translated, lang), args);
            }
        }
        return null;
    }

    private static String pick(String[] translated, int lang) {
        return lang == CHINESE ? translated[1] : translated[0];
    }

    /** "3 hours", "3 часа", "3 小时" */
    static String hours(int hours) {
        if (language == CHINESE) return hours + " 小时";
        if (language == RUSSIAN)
            return hours + " " + Dynamic.plural(hours, "час", "часа", "часов");
        return hours + (hours == 1 ? " hour" : " hours");
    }

    /**
     * the engine's replies are english sentences with numbers in them. only the
     * russian side translates them, as on ios, chinese shows the english
     */
    static final class Dynamic {
        private Dynamic() {}

        static String plural(long number, String one, String few, String many) {
            long n = Math.abs(number);
            long last = n % 10;
            long lastTwo = n % 100;
            if (last == 1 && lastTwo != 11) return one;
            if (last >= 2 && last <= 4 && (lastTwo < 12 || lastTwo > 14)) return few;
            return many;
        }

        private static final String[][] SKIP = {
            { "skipped ", "пропущено " },
            { " already saved", " уже есть" },
            { "malformed link", "битая ссылка" },
            { "tcp with an http header", "tcp с http-заголовком" },
            { "httpupgrade transport", "транспорт httpupgrade" },
            { "kcp transport", "транспорт kcp" },
            { "quic transport", "транспорт quic" },
            { "trojan over reality", "trojan через reality" },
            { "vless encryption", "шифрование vless" },
            { "invalid vless id", "неверный id vless" },
            { "invalid vmess id", "неверный id vmess" },
            { "vmess does not use flow", "у vmess нет flow" },
            { "shadowsocks plugin", "плагин shadowsocks" },
            { "shadowsocks userinfo that does not decode", "нечитаемые данные shadowsocks" },
            { "shadowsocks cipher that is not a name", "нечитаемый шифр shadowsocks" },
            { "happ link that does not open", "нераскрываемая ссылка happ" },
            { "malformed profile entry", "битая запись профиля" },
            { "hysteria v1", "hysteria 1" },
            { "unsupported trojan transport", "trojan через неподдерживаемый транспорт" },
            { "shadowsocks over another transport", "shadowsocks не через tcp" },
            { "socks over tls", "socks через tls" },
            { "link too long", "слишком длинная ссылка" },
            { ", other ", ", прочее " },
        };

        private static String skipTail(String tail) {
            String out = tail;
            for (String[] pair : SKIP) out = out.replace(pair[0], pair[1]);
            return out;
        }

        private static final String[] VERBS = { "imported ", "removed ", "refreshed " };
        private static final String[] VERBS_RU = { "импортировано", "удалено", "обновлено" };
        private static final Pattern COUNT = Pattern.compile("^(\\d+)( server.*)$", Pattern.DOTALL);

        private static String serverCount(String text) {
            for (int i = 0; i < VERBS.length; ++i) {
                if (!text.startsWith(VERBS[i])) continue;
                Matcher match = COUNT.matcher(text.substring(VERBS[i].length()));
                if (!match.matches()) return null;
                int count = Integer.parseInt(match.group(1));
                String tail = match.group(2).substring(" server".length());
                tail = tail.replace("(s)", "").replace(" from the first 512 KB", " из первых 512 КБ")
                        .replace(" (list full)", " (список заполнен)");
                tail = skipTail(tail);
                return VERBS_RU[i] + " " + count + " "
                        + plural(count, "сервер", "сервера", "серверов") + tail;
            }
            return null;
        }

        private static String fetchReason(String why) {
            if (why.startsWith("cannot resolve or connect to "))
                return "не удалось найти или подключиться к " + why.substring(29);
            if (why.startsWith("tls or connection to ") && why.endsWith(" failed"))
                return "ошибка TLS или соединения с " + why.substring(21, why.length() - 7);
            if (why.endsWith(" did not answer the tls handshake in time"))
                return why.substring(0, why.length() - 41)
                        + " не ответил на TLS-рукопожатие вовремя: возможно, сеть блокирует соединение с этим адресом";
            if (why.endsWith(" took the request but did not answer in time"))
                return why.substring(0, why.length() - 44) + " принял запрос, но не ответил вовремя";
            int late = why.indexOf(" timed out after HTTP ");
            if (late >= 0)
                return why.substring(0, late) + " не успел ответить: HTTP " + why.substring(late + 22);
            int http = why.indexOf(" answered HTTP ");
            if (http >= 0)
                return why.substring(0, http) + " ответил HTTP " + why.substring(http + 15);
            if (why.endsWith(" sent a response senko cannot read"))
                return why.substring(0, why.length() - 34)
                        + " прислал ответ, который senko не может прочитать";
            if (why.startsWith("the subscription from ") && why.endsWith(" is too large"))
                return "подписка с " + why.substring(22, why.length() - 13) + " слишком большая";
            if (why.endsWith(" redirected too often or to an unusable address"))
                return why.substring(0, why.length() - 47)
                        + " перенаправляет слишком много раз или на неподходящий адрес";
            if (why.equals("the subscription url is invalid")) return "неверная ссылка на подписку";
            if (why.equals("no reason given")) return "причина неизвестна";
            return why;
        }

        private static boolean allDigits(String text) {
            if (text.isEmpty()) return false;
            for (int i = 0; i < text.length(); ++i)
                if (text.charAt(i) < '0' || text.charAt(i) > '9') return false;
            return true;
        }

        static String russian(String text) {
            if (text.startsWith("fetch failed: "))
                return "не удалось загрузить подписку: " + fetchReason(text.substring(14));
            if (text.startsWith("subscription added, refresh failed: "))
                return "подписка добавлена, но не загрузилась: " + fetchReason(text.substring(36));
            if (text.startsWith("subscription added, no server senko can run in the first 512 KB ("))
                return "подписка добавлена, но в первых 512 КБ нет серверов, которые senko может запустить ("
                        + skipTail(text.substring(65));
            if (text.startsWith("subscription added, no server senko can run ("))
                return "подписка добавлена, но в ней нет серверов, которые senko может запустить ("
                        + skipTail(text.substring(45));
            if (text.startsWith("subscription added, ") && text.contains(" server")) {
                String count = serverCount("imported " + text.substring(20));
                if (count != null)
                    return "подписка добавлена, " + count.replace("импортировано ", "");
            }
            if (text.startsWith("no server senko can run in the first 512 KB ("))
                return "в первых 512 КБ подписки нет серверов, которые senko может запустить ("
                        + skipTail(text.substring(45));
            if (text.startsWith("no server senko can run ("))
                return "нет серверов, которые senko может запустить (" + skipTail(text.substring(25));
            if (text.startsWith("no server senko can run in this file ("))
                return "в этом файле нет серверов, которые senko может запустить ("
                        + skipTail(text.substring(38));
            String count = serverCount(text);
            if (count != null) return count;
            int at = text.indexOf(" server");
            if (at >= 0) {
                String number = text.substring(0, at);
                if (allDigits(number)) {
                    long value = Long.parseLong(number);
                    String tail = text.substring(at + 7);
                    if (tail.startsWith("s")) tail = tail.substring(1);
                    tail = tail.replace("until ", "до ");
                    if (tail.endsWith("expired")) tail = tail.replace("expired", "истёк");
                    return value + " " + plural(value, "сервер", "сервера", "серверов") + tail;
                }
            }
            at = text.indexOf(" single config");
            if (at >= 0) {
                String number = text.substring(0, at);
                if (allDigits(number)) {
                    long value = Long.parseLong(number);
                    String tail = text.substring(at + 14);
                    if (tail.startsWith("s")) tail = tail.substring(1);
                    return value + " " + plural(value, "отдельная конфигурация",
                            "отдельные конфигурации", "отдельных конфигураций") + tail;
                }
            }
            if (text.endsWith(" ms")) {
                String number = text.substring(0, text.length() - 3);
                if (allDigits(number)) return number + " мс";
            }
            if (text.startsWith("Version ")) return "Версия " + text.substring(8);
            if (text.startsWith("until ")) return "до " + text.substring(6);
            return text;
        }
    }
}
