package com.senko.droid;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.List;

/**
 * one request on its own connection, as the ios app does: the engine counts
 * connections, and a request that waits on a download must not block the status
 * poll. every connection starts with AUTH, the token sits beside the socket in
 * the app's private directory
 */
final class ControlClient {
    private static final int CONNECT_TIMEOUT_MS = 3000;
    private static final int LINE_MAX = 4096;

    /** how a connection to the engine is made. android uses a local socket, a jvm test a unix one */
    interface Transport {
        Connection open() throws IOException;
    }

    interface Connection {
        InputStream input() throws IOException;

        OutputStream output() throws IOException;

        /** 0 waits forever */
        void setReadTimeout(int ms) throws IOException;

        void close() throws IOException;
    }

    private final Transport transport;
    private final File tokenFile;

    ControlClient(Transport transport, File tokenFile) {
        this.transport = transport;
        this.tokenFile = tokenFile;
    }

    /** a connection that already passed AUTH. the caller closes it */
    static final class Session {
        final Connection connection;
        final BufferedReader in;
        final OutputStream out;

        Session(Connection connection, BufferedReader in, OutputStream out) {
            this.connection = connection;
            this.in = in;
            this.out = out;
        }

        void send(String line) throws IOException {
            out.write((line + "\n").getBytes("UTF-8"));
            out.flush();
        }

        /** the next line, or null when the engine closed the connection */
        String next() throws IOException {
            String line = in.readLine();
            if (line != null && line.length() > LINE_MAX) throw new ControlException("reply line too long");
            return line;
        }

        void close() {
            try {
                connection.close();
            } catch (IOException ignored) {
                // nothing is left to release
            }
        }
    }

    Session open(int readTimeoutMs) throws IOException {
        String token = readToken();
        Connection connection = transport.open();
        try {
            connection.setReadTimeout(readTimeoutMs);
            BufferedReader in = new BufferedReader(
                    new InputStreamReader(connection.input(), "UTF-8"), 8192);
            Session session = new Session(connection, in, connection.output());
            session.send("AUTH " + token);
            String answer = session.next();
            if (!Protocol.isOk(answer)) {
                session.close();
                throw new ControlException("the engine refused the token: " + answer);
            }
            return session;
        } catch (IOException e) {
            try {
                connection.close();
            } catch (IOException ignored) {
                // the handshake already failed, there is nothing to release
            }
            throw e;
        }
    }

    private String readToken() throws IOException {
        if (!tokenFile.isFile()) throw new ControlException("the engine is not running");
        FileInputStream in = new FileInputStream(tokenFile);
        try {
            BufferedReader reader = new BufferedReader(new InputStreamReader(in, "UTF-8"));
            String token = reader.readLine();
            if (token == null || token.length() < 8) throw new ControlException("the engine token is empty");
            return token.trim();
        } finally {
            in.close();
        }
    }

    /**
     * sends one command and returns its lines up to and including the first one
     * that satisfies done. broadcast lines (STATE, STAT) that arrive in between
     * are part of the answer and the caller skips what it does not want
     */
    List<String> exchange(String command, Terminator done, int timeoutMs) throws IOException {
        Session session = open(timeoutMs);
        try {
            session.send(command);
            List<String> lines = new ArrayList<String>();
            for (;;) {
                String line = session.next();
                if (line == null) throw new ControlException("the engine closed the connection");
                lines.add(line);
                if (done.isLast(line)) return lines;
            }
        } finally {
            session.close();
        }
    }

    interface Terminator {
        boolean isLast(String line);
    }

    static final Terminator OK_OR_ERR = new Terminator() {
        @Override
        public boolean isLast(String line) {
            return Protocol.isOk(line) || Protocol.isError(line);
        }
    };

    static Terminator prefix(final String prefix) {
        return new Terminator() {
            @Override
            public boolean isLast(String line) {
                return line.startsWith(prefix) || Protocol.isError(line);
            }
        };
    }

    /**
     * one line typed by a developer and every line the engine answers with. the
     * reply has no known end, so it is read until OK or ERR, a block end line,
     * or until the engine is quiet for idleMs
     */
    List<String> raw(String command, int idleMs) throws IOException {
        Session session = open(idleMs);
        try {
            session.send(command);
            List<String> lines = new ArrayList<String>();
            for (;;) {
                String line;
                try {
                    line = session.next();
                } catch (IOException quiet) {
                    // the read timed out: what came so far is the answer, and nothing at all is an error
                    if (lines.isEmpty()) throw quiet;
                    return lines;
                }
                if (line == null) return lines;
                lines.add(line);
                if (Protocol.isOk(line) || Protocol.isError(line) || line.contains("END")) return lines;
            }
        } finally {
            session.close();
        }
    }

    /** a command with a plain OK or ERR answer. returns the OK text */
    String command(String command, int timeoutMs) throws IOException {
        List<String> lines = exchange(command, OK_OR_ERR, timeoutMs);
        String last = lines.get(lines.size() - 1);
        if (Protocol.isError(last)) throw new ControlException(Protocol.message(last));
        return Protocol.message(last);
    }

    /** the lines of a reply that ends with a line starting with end, ERR included */
    private List<String> block(String command, String end, int timeoutMs) throws IOException {
        List<String> lines = exchange(command, prefix(end), timeoutMs);
        String last = lines.get(lines.size() - 1);
        if (Protocol.isError(last)) throw new ControlException(Protocol.message(last));
        return lines;
    }

    static String clean(String text) {
        return text.replace('\r', ' ').replace('\n', ' ');
    }

    /** percent escapes for what travels in a command, as the ios app writes them */
    static String escape(String text) {
        StringBuilder out = new StringBuilder();
        byte[] bytes;
        try {
            bytes = text.getBytes("UTF-8");
        } catch (java.io.UnsupportedEncodingException e) {
            throw new IllegalStateException(e);
        }
        for (byte b : bytes) {
            int c = b & 0xff;
            boolean plain = (c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z') || (c >= '0' && c <= '9')
                    || "-._~:/?#[]@!$&'()*,;=".indexOf(c) >= 0;
            if (plain && c != '+') out.append((char) c);
            else out.append('%').append(HEX.charAt(c >> 4)).append(HEX.charAt(c & 15));
        }
        return out.toString();
    }

    private static final String HEX = "0123456789ABCDEF";


    /** the engine's state word, from a STATUS request */
    Protocol.State status() throws IOException {
        List<String> lines = exchange("STATUS", prefix("STATE "), 3000);
        Protocol.State state = Protocol.parseState(lines.get(lines.size() - 1));
        if (state == null) throw new ControlException("unreadable status: " + lines.get(lines.size() - 1));
        return state;
    }

    /** waits for the final state of a connect: connected, error or idle. ERR throws */
    Protocol.State connect(int index) throws IOException {
        List<String> lines = exchange("CONNECT " + index, new Terminator() {
            @Override
            public boolean isLast(String line) {
                return Protocol.isError(line) || line.startsWith("STATE connected")
                        || line.startsWith("STATE error") || line.startsWith("STATE idle");
            }
        }, 90000);
        String last = lines.get(lines.size() - 1);
        if (Protocol.isError(last)) throw new ControlException(Protocol.message(last));
        return Protocol.parseState(last);
    }

    /** the engine answers DISCONNECT with STATE idle and never with OK, so OK_OR_ERR waited out the whole timeout */
    void disconnect() throws IOException {
        List<String> lines = exchange("DISCONNECT", new Terminator() {
            @Override
            public boolean isLast(String line) {
                return Protocol.isError(line) || line.startsWith("STATE idle");
            }
        }, 10000);
        String last = lines.get(lines.size() - 1);
        if (Protocol.isError(last)) throw new ControlException(Protocol.message(last));
    }


    Model.Catalog list() throws IOException {
        return Protocol.parseCatalog(block("LIST", "LISTEND", 8000));
    }

    /** the share link of one server */
    String link(int index) throws IOException {
        List<String> lines = exchange("GETSRV " + index, new Terminator() {
            @Override
            public boolean isLast(String line) {
                return line.startsWith("LINK ") || Protocol.isError(line);
            }
        }, 5000);
        String last = lines.get(lines.size() - 1);
        if (Protocol.isError(last)) throw new ControlException(Protocol.message(last));
        String[] parts = last.split(" ", 3);
        if (parts.length < 3) throw new ControlException("unreadable link reply");
        return parts[2];
    }

    String addServer(String link) throws IOException {
        return command("ADDSRV " + clean(link), 10000);
    }

    String replaceServer(int index, String link) throws IOException {
        return command("REPLACESRV " + index + " " + clean(link), 10000);
    }

    String addSubscription(String url, String name) throws IOException {
        return command("ADDSUB " + escape(clean(url)) + " " + clean(name), 40000);
    }

    String replaceSubscription(int index, String name, String url, String header) throws IOException {
        String safeHeader = escape(clean(header == null ? "" : header));
        if (safeHeader.isEmpty()) safeHeader = "-";
        return command("REPLACESUB " + index + " " + escape(clean(url)) + " " + safeHeader + " "
                + clean(name), 40000);
    }

    String setSubscriptionHeader(int index, String header) throws IOException {
        String encoded = escape(clean(header == null ? "" : header));
        if (encoded.isEmpty()) encoded = "-";
        return command("SETSUBHDR " + index + " " + encoded, 5000);
    }

    String deleteServer(int index) throws IOException {
        return command("DELSRV " + index, 5000);
    }

    String deleteSubscription(int index) throws IOException {
        return command("DELSUB " + index, 5000);
    }

    String refreshSubscription(int index) throws IOException {
        return command("REFRESH " + index, 40000);
    }

    String moveSection(int sectionId, int position) throws IOException {
        return command("MOVESECTION " + sectionId + " " + position, 5000);
    }

    String moveManual(int serverIndex, int position) throws IOException {
        return command("MOVEMANUAL " + serverIndex + " " + position, 5000);
    }

    String clearManual() throws IOException {
        return command("CLEARMANUAL", 5000);
    }


    /** the same check the server rows run, with the stages the engine walked */
    Model.CheckResult check(int index, String mode) throws IOException {
        List<String> lines = exchange("CHECK " + mode + " " + index + " stages", new Terminator() {
            @Override
            public boolean isLast(String line) {
                return line.startsWith("PONG ") || Protocol.isError(line);
            }
        }, 15000);
        return Protocol.parseCheck(lines);
    }

    /** the plain list ping: milliseconds, or -1 when the server did not answer */
    int ping(int index) throws IOException {
        List<String> lines = exchange("PING " + index, prefix("PONG "), 15000);
        int[] pong = Protocol.parsePong(lines.get(lines.size() - 1));
        return pong == null ? -1 : pong[1];
    }


    /** hands raw pasted or picked content to the engine's parser. files is the app directory */
    String importContent(File files, byte[] data) throws IOException {
        if (data == null || data.length == 0) throw new ControlException("nothing to import");
        File stage = new File(files, "import.dat");
        java.io.FileOutputStream out = new java.io.FileOutputStream(stage);
        try {
            out.write(data);
            out.getFD().sync();
        } finally {
            out.close();
        }
        try {
            return command("IMPORT", 15000);
        } finally {
            // the engine removes it once read, this covers a request that never got there
            stage.delete();
        }
    }


    List<String[]> settings() throws IOException {
        return Protocol.parseSettings(block("SETTINGS", "SETEND", 3000));
    }

    String set(String key, String value) throws IOException {
        return command("SET " + key + " " + value, 5000);
    }

    List<Model.Rule> rules() throws IOException {
        List<Model.Rule> rules = new ArrayList<Model.Rule>();
        for (String line : block("RULES", "RULEEND ", 3000)) {
            Model.Rule rule = Protocol.parseRule(line);
            if (rule != null) rules.add(rule);
        }
        return rules;
    }

    String addRule(String action, String type, String value) throws IOException {
        if (action.isEmpty() || type.isEmpty() || value.isEmpty()) throw new ControlException("bad rule");
        return command("SET rule " + action + " " + type + " " + value, 5000);
    }

    String deleteRule(int index) throws IOException {
        return command("DELRULE " + index, 5000);
    }

    /** the profiles the subscriptions delivered. active is the switched on one, -1 for none */
    static final class RoutingList {
        final List<Model.RoutingProfile> profiles = new ArrayList<Model.RoutingProfile>();
        int active = -1;
    }

    RoutingList routing() throws IOException {
        RoutingList list = new RoutingList();
        for (String line : block("ROUTING", "ROUTINGEND", 3000)) {
            Model.RoutingProfile profile = Protocol.parseRouting(line);
            if (profile != null) list.profiles.add(profile);
            if (line.startsWith("ROUTINGEND ")) list.active = (int) parseLong(line.substring(11));
        }
        return list;
    }

    private static long parseLong(String text) {
        try {
            return Long.parseLong(text.trim());
        } catch (NumberFormatException e) {
            return -1;
        }
    }

    /** index -1 switches routing off */
    String setRouting(int index) throws IOException {
        return command("ROUTINGSET " + (index < 0 ? "off" : String.valueOf(index)), 5000);
    }

    static final class RoutingEntries {
        final List<Model.RoutingEntry> entries = new ArrayList<Model.RoutingEntry>();
        int total;
    }

    RoutingEntries routingEntries(int index) throws IOException {
        RoutingEntries result = new RoutingEntries();
        for (String line : block("ROUTINGENTRIES " + index, "RENTRYEND ", 3000)) {
            Model.RoutingEntry entry = Protocol.parseRoutingEntry(line);
            if (entry != null) result.entries.add(entry);
            if (line.startsWith("RENTRYEND ")) result.total = (int) parseLong(line.substring(10));
        }
        return result;
    }

    Model.GeoState geo() throws IOException {
        List<String> lines = exchange("GEO", new Terminator() {
            @Override
            public boolean isLast(String line) {
                return line.startsWith("GEO ") || Protocol.isError(line);
            }
        }, 3000);
        String last = lines.get(lines.size() - 1);
        if (Protocol.isError(last)) throw new ControlException(Protocol.message(last));
        Model.GeoState geo = Protocol.parseGeo(last);
        if (geo == null) throw new ControlException("unreadable geo reply");
        return geo;
    }

    String updateGeo() throws IOException {
        return command("GEOUPDATE", 5000);
    }


    List<Model.DiagFact> diagnostics() throws IOException {
        List<Model.DiagFact> facts = new ArrayList<Model.DiagFact>();
        for (String line : block("DIAG", "DIAGEND", 6000)) {
            Model.DiagFact fact = Protocol.parseDiag(line);
            if (fact != null) facts.add(fact);
        }
        return facts;
    }

    String flush(String what) throws IOException {
        return command("FLUSH " + what, 8000);
    }

    /** the device id the engine sends to subscription panels */
    String hwid() throws IOException {
        return command("HWID", 5000);
    }

    String resetHwid() throws IOException {
        return command("HWIDRESET", 8000);
    }

    String exportBackup() throws IOException {
        return command("EXPORT", 8000);
    }

    String restoreBackup() throws IOException {
        return command("RESTORE", 8000);
    }

    /** the last bytes of the engine log */
    String logs() throws IOException {
        List<String> lines = exchange("LOGS", new Terminator() {
            @Override
            public boolean isLast(String line) {
                return line.startsWith("FDEND") || Protocol.isError(line);
            }
        }, 8000);
        java.io.ByteArrayOutputStream bytes = new java.io.ByteArrayOutputStream();
        for (String line : lines) {
            if (Protocol.isError(line)) throw new ControlException(Protocol.message(line));
            if (!line.startsWith("FDATA ")) continue;
            byte[] chunk = Base64Decoder.decode(line.substring(6));
            bytes.write(chunk, 0, chunk.length);
        }
        return new String(bytes.toByteArray(), "UTF-8");
    }


    /** one AWG request. returns the lines the engine printed, without the AWG prefix */
    List<String> awg(String request, int timeoutMs) throws IOException {
        List<String> lines = exchange("AWG " + request, new Terminator() {
            @Override
            public boolean isLast(String line) {
                return line.startsWith("AWGEND ") || Protocol.isError(line);
            }
        }, timeoutMs);
        List<String> out = new ArrayList<String>();
        for (String line : lines) {
            if (Protocol.isError(line)) throw new ControlException(Protocol.message(line));
            if (line.startsWith("AWG ")) out.add(line.substring(4));
            else if (line.startsWith("AWGUP ")) out.add(line);
        }
        return out;
    }

    String awgStart(String path) throws IOException {
        return first(awg("START " + path, 30000));
    }

    String awgStop() throws IOException {
        return first(awg("STOP", 10000));
    }

    String awgStatus() throws IOException {
        return first(awg("STATUS", 5000));
    }

    /** the status word and the seconds the tunnel has been up (-1 when the engine sent none) */
    static final class AwgStatus {
        String state = "idle";
        long uptime = -1;
    }

    AwgStatus awgStatusWithUptime() throws IOException {
        AwgStatus status = new AwgStatus();
        for (String line : awg("STATUS", 5000)) {
            if (line.startsWith("AWGUP ")) {
                try {
                    status.uptime = Long.parseLong(line.substring(6).trim());
                } catch (NumberFormatException ignored) {
                    // an unreadable age is no age
                }
            } else if (status.state.equals("idle") || !line.isEmpty()) {
                if (!line.startsWith("AWGUP")) status.state = line.trim();
            }
        }
        return status;
    }

    /** a handshake with the server, nothing else. returns "PING ms" or an error line */
    String awgProbe(String path) throws IOException {
        return first(awg("PROBE " + path, 20000));
    }

    String awgValidate(String path) throws IOException {
        return first(awg("VALIDATE " + path, 5000));
    }

    private static String first(List<String> lines) {
        return lines.isEmpty() ? "" : lines.get(0);
    }

    /** the engine tells the app the network moved, a running tunnel is rebuilt */
    String networkChanged() throws IOException {
        return command("NETWORK", 5000);
    }
}
