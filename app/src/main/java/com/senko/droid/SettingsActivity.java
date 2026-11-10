package com.senko.droid;

import android.content.Intent;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.view.View;
import android.widget.TextView;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * the settings list. the engine owns the automation values, so the rows show
 * what it answered and read-only until it does; quick connect, theme, snow and
 * language belong to the app
 */
public final class SettingsActivity extends BaseActivity {
    private static final int REQUEST_EXPORT = 51;
    private static final int REQUEST_RESTORE = 52;
    private static final String BACKUP_NAME = "senko-backup.senko";

    private Rows rows;
    private int hideTaps;
    /** null while the engine has not answered */
    private Map<String, String> values;

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        setScreenTitle("Settings");
        rows = new Rows(this);
        setBody(rows.view());
        render();
    }

    @Override
    protected void onStart() {
        super.onStart();
        load();
    }

    @Override
    public void onChange(int what) {
        if ((what & Senko.ENGINE) != 0) load();
    }

    private void load() {
        senko.run(false, new Senko.Work<List<String[]>>() {
            @Override
            public List<String[]> run(ControlClient client) throws IOException {
                return client.settings();
            }
        }, new Senko.Done<List<String[]>>() {
            @Override
            public void done(List<String[]> list, IOException error) {
                if (error != null || list == null) {
                    values = null;
                } else {
                    values = new HashMap<String, String>();
                    for (String[] pair : list) values.put(pair[0], pair[1]);
                }
                render();
            }
        });
    }

    private boolean flag(String key) {
        return values != null && "1".equals(values.get(key));
    }

    private String value(String key) {
        return values == null ? Tr.t("unreachable") : values.get(key);
    }

    private void render() {
        rows.clear();
        final boolean engine = values != null;

        rows.section("Main");
        rows.toggle("rocket", "Connect at startup", flag("auto_connect"), engine, switchFor("auto_connect"));
        rows.toggle("bolt", "Quick connect", Prefs.autoServer(), true, new Toggle.Listener() {
            @Override
            public void changed(Toggle toggle, boolean on) {
                Prefs.setAutoServer(on);
            }
        });
        rows.toggle("reconnect", "Reconnect automatically", flag("auto_reconnect"), engine,
                switchFor("auto_reconnect"));
        rows.toggle("shuffle", "Try another server", flag("failover"), engine, switchFor("failover"));
        rows.action("repeat", "Reconnect attempts", engine ? attemptsName(value("reconnect_max_attempts"))
                : Tr.t("unreachable"), engine, new View.OnClickListener() {
            @Override
            public void onClick(View view) {
                if (engine) chooseAttempts();
            }
        });
        rows.action("cloud", "Update subscriptions", engine ? refreshName(value("sub_refresh_hours"))
                : Tr.t("unreachable"), engine, new View.OnClickListener() {
            @Override
            public void onClick(View view) {
                if (engine) chooseRefresh();
            }
        });
        if (!engine) rows.footer(Tr.t("The engine is not answering, so these cannot be read or changed."));

        rows.section("App");
        rows.action("palette", "Theme", Theme.statusLine(), open(ThemesActivity.class));
        rows.toggle("snow", "Falling snow", Prefs.snow(), true, new Toggle.Listener() {
            @Override
            public void changed(Toggle toggle, boolean on) {
                Prefs.setSnow(on);
            }
        });
        rows.action("globe", "Language", languageName(), new View.OnClickListener() {
            @Override
            public void onClick(View view) {
                chooseLanguage();
            }
        });
        rows.action("chart", "Statistics", "", open(StatsActivity.class));
        rows.action("logs", "System Logs", "", open(LogsActivity.class));
        rows.action("backup", "Backup", "", new View.OnClickListener() {
            @Override
            public void onClick(View view) {
                backupMenu();
            }
        });

        rows.section("Advanced");
        rows.action("shield", "DNS", engine ? value("dns_upstream") : Tr.t("unreachable"), engine,
                new View.OnClickListener() {
                    @Override
                    public void onClick(View view) {
                        if (engine) editDns();
                    }
                });
        rows.action("split", "Routing", "", open(RoutingActivity.class));
        rows.action("info", "About", "", open(AboutActivity.class));

        if (Prefs.flag("dev_menu", false)) {
            TextView heading = rows.section("Developer settings");
            heading.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View view) {
                    if (++hideTaps < 5) return;
                    hideTaps = 0;
                    Prefs.putFlag("dev_menu", false);
                    render();
                }
            });
            rows.action("code", "Developer settings", "", open(DevMenuActivity.class));
        }
    }

    private View.OnClickListener open(final Class<?> screen) {
        return new View.OnClickListener() {
            @Override
            public void onClick(View view) {
                startActivity(new Intent(SettingsActivity.this, screen));
            }
        };
    }


    private Toggle.Listener switchFor(final String key) {
        return new Toggle.Listener() {
            @Override
            public void changed(final Toggle toggle, boolean on) {
                apply(key, on ? "1" : "0", toggle);
            }
        };
    }

    /** the switch slides back when the engine refuses the value, since it alone knows what it took */
    private void apply(final String key, final String value, final Toggle toggle) {
        if (values == null) {
            if (toggle != null) toggle.setChecked(!toggle.isChecked());
            return;
        }
        senko.run(false, new Senko.Work<String>() {
            @Override
            public String run(ControlClient client) throws IOException {
                return client.set(key, value);
            }
        }, new Senko.Done<String>() {
            @Override
            public void done(String reply, IOException error) {
                if (error == null) {
                    values.put(key, value);
                    if (toggle == null) render();
                    return;
                }
                toastError(error);
                if (toggle != null) toggle.setChecked("1".equals(values.get(key)));
                else render();
            }
        });
    }

    private String attemptsName(String attempts) {
        int count = parse(attempts);
        if (count <= 0) return Tr.t("Until it works");
        return Tr.f("%d attempts", count);
    }

    private String refreshName(String hours) {
        int count = parse(hours);
        if (count <= 0) return Tr.t("Off");
        return Tr.f("Every %s", Tr.hours(count));
    }

    private static int parse(String text) {
        try {
            return Integer.parseInt(text == null ? "" : text.trim());
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    private void chooseAttempts() {
        final String[] limits = { "0", "3", "5", "10" };
        List<String> labels = new ArrayList<String>();
        labels.add(Tr.t("Until it works"));
        labels.add("3");
        labels.add("5");
        labels.add("10");
        Dialogs.menu(this, "Reconnect attempts", labels, new Dialogs.Pick() {
            @Override
            public void picked(int index) {
                apply("reconnect_max_attempts", limits[index], null);
            }
        });
    }

    private void chooseRefresh() {
        final String[] hours = { "0", "6", "12", "24" };
        List<String> labels = new ArrayList<String>();
        labels.add(Tr.t("Off"));
        labels.add(Tr.hours(6));
        labels.add(Tr.hours(12));
        labels.add(Tr.hours(24));
        Dialogs.menu(this, "Update subscriptions", labels, new Dialogs.Pick() {
            @Override
            public void picked(int index) {
                apply("sub_refresh_hours", hours[index], null);
            }
        });
    }

    private void editDns() {
        Dialogs.prompt(this, "DNS", "An IPv4 address, for example 1.1.1.1", values.get("dns_upstream"),
                android.text.InputType.TYPE_CLASS_PHONE, "Save", new Dialogs.Text() {
                    @Override
                    public void entered(String text) {
                        apply("dns_upstream", text.trim(), null);
                    }
                });
    }


    private String languageName() {
        int lang = Prefs.language();
        return lang == Tr.RUSSIAN ? "Русский" : lang == Tr.CHINESE ? "中文" : "English";
    }

    private void chooseLanguage() {
        List<String> labels = new ArrayList<String>();
        labels.add("English");
        labels.add("Русский");
        labels.add("中文");
        Dialogs.menu(this, "Language", labels, new Dialogs.Pick() {
            @Override
            public void picked(int index) {
                Prefs.setLanguage(index);
                // every screen builds its text once, so they all rebuild
                Theme.changed();
            }
        });
    }


    private void backupMenu() {
        List<String> labels = new ArrayList<String>();
        labels.add(Tr.t("Export backup"));
        labels.add(Tr.t("Restore backup"));
        Dialogs.menu(this, "Backup", labels, new Dialogs.Pick() {
            @Override
            public void picked(int index) {
                if (index == 0) {
                    Dialogs.confirm(SettingsActivity.this, "Export backup",
                            "The backup contains server credentials. Anyone with access to the saved file can read them.",
                            "Export backup", new Runnable() {
                                @Override public void run() { exportBackup(); }
                            });
                } else restoreBackup();
            }
        });
    }

    private File backupFile() {
        return new File(getFilesDir(), "backup.senko");
    }

    private void exportBackup() {
        senko.run(true, new Senko.Work<String>() {
            @Override
            public String run(ControlClient client) throws IOException {
                return client.exportBackup();
            }
        }, new Senko.Done<String>() {
            @Override
            public void done(String reply, IOException error) {
                if (error != null) {
                    backupMessage(Senko.describe(error));
                    return;
                }
                handOver();
            }
        });
    }

    /** the file the engine wrote leaves the private directory through the system's file dialog */
    private void handOver() {
        if (Build.VERSION.SDK_INT >= 19) {
            Intent intent = new Intent(Intent.ACTION_CREATE_DOCUMENT);
            intent.addCategory(Intent.CATEGORY_OPENABLE);
            intent.setType("application/octet-stream");
            intent.putExtra(Intent.EXTRA_TITLE, BACKUP_NAME);
            startActivityForResult(intent, REQUEST_EXPORT);
            return;
        }
        // api 16 to 18 have no document dialog, the public downloads folder is the hand over
        File directory = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS);
        File target = null;
        try {
            if (!directory.isDirectory() && !directory.mkdirs())
                throw new IOException("cannot create downloads directory");
            for (int i = 0; i < 1000; ++i) {
                String name = i == 0 ? BACKUP_NAME : "senko-backup-" + i + ".senko";
                File candidate = new File(directory, name);
                if (candidate.createNewFile()) {
                    target = candidate;
                    break;
                }
            }
            if (target == null) throw new IOException("no free backup filename");
            copy(new FileInputStream(backupFile()), new FileOutputStream(target));
            backupMessage(Tr.f("Saved to %s", target.getPath()));
        } catch (IOException e) {
            if (target != null && !target.delete())
                android.util.Log.w("senko", "could not remove incomplete backup");
            android.util.Log.e("senko", "backup export failed", e);
            backupMessage(Tr.t("Backup export failed"));
        }
    }

    private void restoreBackup() {
        Intent intent = new Intent(Intent.ACTION_GET_CONTENT);
        intent.setType("*/*");
        intent.addCategory(Intent.CATEGORY_OPENABLE);
        startActivityForResult(Intent.createChooser(intent, Tr.t("Restore configuration")), REQUEST_RESTORE);
    }

    @Override
    protected void onActivityResult(int request, int result, Intent data) {
        super.onActivityResult(request, result, data);
        if (result != RESULT_OK || data == null || data.getData() == null) return;
        Uri uri = data.getData();
        if (request == REQUEST_EXPORT) {
            try {
                OutputStream out = getContentResolver().openOutputStream(uri);
                if (out == null) throw new IOException("no stream");
                copy(new FileInputStream(backupFile()), out);
                backupMessage(Tr.t("Backup saved"));
            } catch (IOException e) {
                backupMessage(Tr.t("Backup export failed"));
            }
        } else if (request == REQUEST_RESTORE) {
            stageRestore(uri);
        }
    }

    private static void copy(InputStream in, OutputStream out) throws IOException {
        try {
            byte[] buffer = new byte[16 * 1024];
            int got;
            while ((got = in.read(buffer)) > 0) out.write(buffer, 0, got);
        } finally {
            in.close();
            out.close();
        }
    }

    /** a backup is the config file itself, so it is recognised by the keywords the engine writes */
    static boolean looksLikeBackup(byte[] data) {
        String[] keys = { "SET ", "SRV ", "SUB ", "SEL ", "ORDER" };
        String text;
        try {
            text = new String(data, 0, Math.min(data.length, 4096), "UTF-8");
        } catch (java.io.UnsupportedEncodingException e) {
            throw new IllegalStateException(e);
        }
        for (String line : text.split("\n")) {
            for (String key : keys) if (line.startsWith(key)) return true;
        }
        return false;
    }

    private void stageRestore(Uri uri) {
        final byte[] data;
        try {
            InputStream in = getContentResolver().openInputStream(uri);
            if (in == null) throw new IOException("no stream");
            ByteArrayOutputStream bytes = new ByteArrayOutputStream();
            byte[] buffer = new byte[16 * 1024];
            int got;
            try {
                while ((got = in.read(buffer)) > 0 && bytes.size() <= 1024 * 1024) bytes.write(buffer, 0, got);
            } finally {
                in.close();
            }
            data = bytes.toByteArray();
        } catch (IOException e) {
            backupMessage(Tr.t("Not a senko backup"));
            return;
        }
        if (data.length == 0 || data.length > 1024 * 1024 || !looksLikeBackup(data)) {
            backupMessage(Tr.t("Not a senko backup"));
            return;
        }
        Dialogs.confirm(this, "Replace configuration?",
                "The imported backup will replace all current servers and subscriptions.", "Replace",
                new Runnable() {
                    @Override
                    public void run() {
                        restore(data);
                    }
                });
    }

    private void restore(final byte[] data) {
        senko.run(true, new Senko.Work<String>() {
            @Override
            public String run(ControlClient client) throws IOException {
                File stage = new File(getFilesDir(), "import.senko");
                FileOutputStream out = new FileOutputStream(stage);
                try {
                    out.write(data);
                } finally {
                    out.close();
                }
                return client.restoreBackup();
            }
        }, new Senko.Done<String>() {
            @Override
            public void done(String reply, IOException error) {
                senko.loadCatalog(null);
                if (error != null) backupMessage(Senko.describe(error));
                else backupMessage(Tr.t("Configuration restored"));
                load();
            }
        });
    }

    private void backupMessage(String text) {
        Dialogs.message(this, Tr.t("Configuration backup"), text);
    }
}
