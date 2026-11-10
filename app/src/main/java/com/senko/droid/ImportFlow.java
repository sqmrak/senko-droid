package com.senko.droid;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.widget.Toast;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;

/**
 * adding things: a subscription address, a server link, a pasted feed, a file
 * or a qr code. one entry per source, not per format: the content tells which
 * path it takes (see Importer), and the engine owns the parsers
 */
final class ImportFlow {
    private ImportFlow() {}

    /** what the screen that hosts the flow can do for it */
    interface Host {
        Activity activity();

        void pickFile();

        void scanQr();

        /** a short line of progress or result */
        void say(String text);

        /** something went wrong and the user has to be told */
        void fail(String text);
    }

    static final int REQUEST_FILE = 41;
    static final int REQUEST_QR = 42;

    private static final Importer.Amnezia AMNEZIA = new Importer.Amnezia() {
        @Override
        public boolean looksLikeBundle(byte[] data) {
            return Engine.nativeAmneziaLooksLike(data);
        }
    };

    static void showAddMenu(final Host host) {
        final List<String> labels = new ArrayList<String>();
        final boolean camera = Wide.hasCamera(host.activity());
        labels.add(Tr.t("Add subscription"));
        labels.add(Tr.t("Paste from clipboard"));
        // a television or a tablet without a camera has nothing for the scanner to open
        if (camera) labels.add(Tr.t("QR code"));
        labels.add(Tr.t("Import from file"));
        Dialogs.menu(host.activity(), "Add", labels, new Dialogs.Pick() {
            @Override
            public void picked(int index) {
                if (index == 0) promptSubscription(host);
                else if (index == 1) paste(host);
                else if (camera && index == 2) host.scanQr();
                else host.pickFile();
            }
        });
    }

    static void promptSubscription(final Host host) {
        Dialogs.prompt(host.activity(), "Subscription", "paste a subscription URL or a server link", "",
                Dialogs.urlInput(), "Add", new Dialogs.Text() {
                    @Override
                    public void entered(String text) {
                        importText(host, text);
                    }
                });
    }

    static void paste(Host host) {
        String text = Dialogs.clipboard(host.activity());
        if (text.isEmpty()) host.fail("The clipboard is empty.");
        else importText(host, text);
    }

    static void importText(final Host host, String raw) {
        final String text = raw == null ? "" : raw.trim();
        Importer.Kind kind = Importer.classify(text, AMNEZIA);
        switch (kind) {
            case EMPTY:
                host.fail("Unknown content type. This is not a server link, a subscription, or a profile Senko can read.");
                break;
            case AWG_CONF:
                saveAwg(host, text);
                break;
            case AMNEZIA_BUNDLE:
                importAmnezia(host, text.getBytes(java.nio.charset.Charset.forName("UTF-8")));
                break;
            case SERVER_LINK:
                addServer(host, text);
                break;
            case SUBSCRIPTION:
                addSubscription(host, text);
                break;
            case INSECURE_SUBSCRIPTION:
                Dialogs.confirm(host.activity(), "Unencrypted subscription",
                        "This URL sends the subscription without TLS. Import it only if you trust this network and provider.",
                        "Import", new Runnable() {
                            @Override
                            public void run() {
                                addSubscription(host, text);
                            }
                        });
                break;
            default:
                importContent(host, text.getBytes(java.nio.charset.Charset.forName("UTF-8")));
                break;
        }
    }

    /** a file the user picked: its bytes decide, its extension helps for .vpn */
    static void importUri(final Host host, final Uri uri) {
        final Context context = host.activity();
        host.say("reading content...");
        new Thread(new Runnable() {
            @Override
            public void run() {
                try {
                    final byte[] body = read(context, uri);
                    final String name = uri.getLastPathSegment();
                    host.activity().runOnUiThread(new Runnable() {
                        @Override
                        public void run() {
                            importFile(host, name, body);
                        }
                    });
                } catch (IOException e) {
                    host.activity().runOnUiThread(new Runnable() {
                        @Override
                        public void run() {
                            host.fail("The file is empty or could not be read.");
                        }
                    });
                }
            }
        }, "senko-import-read").start();
    }

    private static byte[] read(Context context, Uri uri) throws IOException {
        InputStream in = context.getContentResolver().openInputStream(uri);
        if (in == null) throw new IOException("no stream");
        try {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] buffer = new byte[16 * 1024];
            int got;
            // a profile or a feed is small, a file that is not one is cut off
            while ((got = in.read(buffer)) > 0 && out.size() < 4 * 1024 * 1024) out.write(buffer, 0, got);
            return out.toByteArray();
        } finally {
            in.close();
        }
    }

    static void importFile(Host host, String name, byte[] body) {
        Importer.Kind kind = Importer.classifyFile(name, body, AMNEZIA);
        if (kind == Importer.Kind.EMPTY) {
            host.fail("The file is empty or could not be read.");
            return;
        }
        if (kind == Importer.Kind.AMNEZIA_BUNDLE) {
            importAmnezia(host, body);
            return;
        }
        if (kind == Importer.Kind.AWG_CONF) {
            saveAwg(host, new String(body, java.nio.charset.Charset.forName("UTF-8")));
            return;
        }
        if (kind == Importer.Kind.SUBSCRIPTION || kind == Importer.Kind.INSECURE_SUBSCRIPTION) {
            importText(host, new String(body, java.nio.charset.Charset.forName("UTF-8")).trim());
            return;
        }
        importContent(host, body);
    }


    private static void saveAwg(final Host host, String text) {
        host.say("validating native config...");
        Senko.get().saveAwgConfig(text, true, new Senko.Done<String>() {
            @Override
            public void done(String value, IOException error) {
                if (error != null) host.fail(Senko.describe(error));
                else host.say("native AmneziaWG config added");
            }
        });
    }

    private static void importAmnezia(final Host host, final byte[] body) {
        final Senko senko = Senko.get();
        senko.run(true, new Senko.Work<String>() {
            @Override
            public String run(ControlClient client) throws IOException {
                String conf = Engine.nativeAmneziaExtract(body);
                if (conf == null || conf.isEmpty()) throw new ControlException("amnezia bundle carries no readable config");
                return conf;
            }
        }, new Senko.Done<String>() {
            @Override
            public void done(String conf, IOException error) {
                if (error != null) host.fail(Senko.describe(error));
                else saveAwg(host, conf);
            }
        });
    }

    private static void addServer(final Host host, final String link) {
        final Senko senko = Senko.get();
        senko.run(true, new Senko.Work<String>() {
            @Override
            public String run(ControlClient client) throws IOException {
                return client.addServer(link);
            }
        }, new Senko.Done<String>() {
            @Override
            public void done(String value, IOException error) {
                if (error != null) {
                    host.fail(Senko.describe(error));
                    return;
                }
                senko.clearError();
                senko.loadCatalog(null);
                host.say(value == null || value.isEmpty() ? "server added" : value);
            }
        });
    }

    static void addSubscription(final Host host, final String url) {
        final Senko senko = Senko.get();
        host.say("checking engine...");
        senko.run(true, new Senko.Work<String>() {
            @Override
            public String run(ControlClient client) throws IOException {
                String reply = client.addSubscription(url, Format.subscriptionName(url));
                int index = Importer.trailingInt(reply);
                if (index >= 0) client.refreshSubscription(index);
                return reply;
            }
        }, new Senko.Done<String>() {
            @Override
            public void done(String value, IOException error) {
                senko.loadCatalog(null);
                if (error != null) {
                    host.fail(Senko.describe(error));
                    return;
                }
                senko.clearError();
                host.say("subscription added");
            }
        });
    }

    private static void importContent(final Host host, final byte[] body) {
        final Senko senko = Senko.get();
        host.say("reading content...");
        senko.run(true, new Senko.Work<String>() {
            @Override
            public String run(ControlClient client) throws IOException {
                return client.importContent(senko.context().getFilesDir(), body);
            }
        }, new Senko.Done<String>() {
            @Override
            public void done(String value, IOException error) {
                senko.loadCatalog(null);
                if (error != null) {
                    host.fail(Senko.describe(error));
                    return;
                }
                senko.clearError();
                host.say(value);
            }
        });
    }

    /** the intent that opens the system's file chooser */
    static Intent pickFileIntent() {
        Intent intent = new Intent(Intent.ACTION_GET_CONTENT);
        intent.setType("*/*");
        intent.addCategory(Intent.CATEGORY_OPENABLE);
        return Intent.createChooser(intent, Tr.t("Import from file"));
    }

    static void handleActivityResult(Host host, int request, int result, Intent data) {
        if (result != Activity.RESULT_OK || data == null) return;
        if (request == REQUEST_FILE && data.getData() != null) importUri(host, data.getData());
        else if (request == REQUEST_QR) {
            String text = data.getStringExtra(QrScanActivity.EXTRA_TEXT);
            if (text == null || text.isEmpty()) host.fail("QR code is empty or unreadable");
            else importText(host, text);
        }
    }

    static void toast(Context context, String english) {
        Toast.makeText(context, Tr.t(english), Toast.LENGTH_SHORT).show();
    }
}
