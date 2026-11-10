package com.senko.droid;

import android.content.Intent;
import android.graphics.Typeface;
import android.os.Bundle;
import android.text.InputType;
import android.view.View;
import android.view.inputmethod.EditorInfo;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

/**
 * the developer tools in one screen class: the engine's diagnostics, staged
 * checks of one server, the switches only a tester needs, the rescue actions
 * and a console on the control socket. the tool comes in the intent
 */
public final class DevToolsActivity extends BaseActivity {
    static final String EXTRA_TOOL = "tool";
    static final String STATE = "state";
    static final String CHECKS = "checks";
    static final String FORCE = "force";
    static final String RESCUE = "rescue";
    static final String CONSOLE = "console";

    private static final String[] MODES = { "tcp", "real", "tunnel", "handshake" };
    private static final String[] BLOCK_ANSWERS = { "zero", "nxdomain", "refused" };

    private String tool;
    private Rows rows;
    private List<Model.DiagFact> facts;
    private java.util.Map<String, String> settings;
    private String hwid = "";
    private int checkServer = -1;
    private String checkMode = "tcp";
    private Model.CheckResult checkResult;
    private String checkNote = "";
    private TextView consoleOut;
    private ScrollView consoleScroll;

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        tool = getIntent().getStringExtra(EXTRA_TOOL);
        if (tool == null) tool = STATE;
        setScreenTitle(tool.equals(STATE) ? "State" : tool.equals(CHECKS) ? "Checks"
                : tool.equals(FORCE) ? "Force" : tool.equals(RESCUE) ? "Rescue" : "Console");
        if (tool.equals(CONSOLE)) {
            buildConsole();
            return;
        }
        rows = new Rows(this);
        setBody(rows.view());
        checkServer = senko.picked();
        if (tool.equals(STATE)) {
            addBarText("Copy", new View.OnClickListener() {
                @Override
                public void onClick(View view) {
                    copyState();
                }
            });
        }
        render();
    }

    @Override
    protected void onStart() {
        super.onStart();
        if (!tool.equals(CONSOLE)) load();
    }

    private void load() {
        if (tool.equals(STATE)) {
            senko.run(false, new Senko.Work<List<Model.DiagFact>>() {
                @Override
                public List<Model.DiagFact> run(ControlClient client) throws IOException {
                    return client.diagnostics();
                }
            }, new Senko.Done<List<Model.DiagFact>>() {
                @Override
                public void done(List<Model.DiagFact> value, IOException error) {
                    facts = value;
                    render();
                }
            });
        } else if (tool.equals(FORCE)) {
            senko.run(false, new Senko.Work<List<String[]>>() {
                @Override
                public List<String[]> run(ControlClient client) throws IOException {
                    return client.settings();
                }
            }, new Senko.Done<List<String[]>>() {
                @Override
                public void done(List<String[]> value, IOException error) {
                    settings = null;
                    if (value != null) {
                        settings = new java.util.HashMap<String, String>();
                        for (String[] pair : value) settings.put(pair[0], pair[1]);
                    }
                    render();
                }
            });
        } else if (tool.equals(RESCUE)) {
            senko.run(false, new Senko.Work<String>() {
                @Override
                public String run(ControlClient client) throws IOException {
                    return client.hwid();
                }
            }, new Senko.Done<String>() {
                @Override
                public void done(String value, IOException error) {
                    hwid = value == null ? "" : value;
                    render();
                }
            });
        }
    }

    private void render() {
        if (rows == null) return;
        rows.clear();
        if (tool.equals(STATE)) renderState();
        else if (tool.equals(CHECKS)) renderChecks();
        else if (tool.equals(FORCE)) renderForce();
        else renderRescue();
    }


    private void renderState() {
        rows.section(null);
        if (facts == null || facts.isEmpty()) {
            rows.footer(Tr.t(facts == null ? "Asking the engine..." : "Engine is unreachable"));
            return;
        }
        for (Model.DiagFact fact : facts) {
            Rows.Line line = rows.action(null, fact.key, fact.value, false, null);
            line.value.setSingleLine(false);
            line.value.setMaxLines(4);
        }
    }

    private void copyState() {
        if (facts == null) return;
        StringBuilder text = new StringBuilder();
        for (Model.DiagFact fact : facts) text.append(fact.key).append(": ").append(fact.value).append('\n');
        Dialogs.copy(this, "state", Errors.redact(text.toString()));
        toast("Copied to the clipboard.");
    }


    private void renderChecks() {
        rows.section("Server");
        Model.Server server = senko.catalog().server(checkServer);
        rows.action("globe", "Server", server == null ? Tr.t("Choose a server") : Names.title(server),
                new View.OnClickListener() {
                    @Override
                    public void onClick(View view) {
                        chooseServer();
                    }
                });
        rows.section("Probe");
        for (final String mode : MODES) {
            rows.action("search", mode, mode.equals(checkMode) && checkResult != null ? "✓" : "", false,
                    new View.OnClickListener() {
                        @Override
                        public void onClick(View view) {
                            runCheck(mode);
                        }
                    });
        }
        if (!checkNote.isEmpty()) rows.footer(checkNote);
        if (checkResult != null) {
            rows.section("Stages");
            for (Model.CheckStage stage : checkResult.stages) {
                Rows.Line line = rows.action(null, stage.name, (stage.ok ? "ok " : "failed ") + stage.ms + " ms",
                        false, null);
                line.value.setTextColor(stage.ok ? Theme.stateColor("connected") : Theme.stateColor("error"));
            }
        }
    }

    private void chooseServer() {
        final List<Model.Server> list = new ArrayList<Model.Server>();
        List<String> labels = new ArrayList<String>();
        for (Model.Server server : senko.catalog().servers) {
            if (!server.supported) continue;
            list.add(server);
            labels.add(Names.title(server));
        }
        if (list.isEmpty()) {
            toast("No servers");
            return;
        }
        Dialogs.menu(this, "Server", labels, new Dialogs.Pick() {
            @Override
            public void picked(int index) {
                checkServer = list.get(index).index;
                checkResult = null;
                render();
            }
        });
    }

    private void runCheck(final String mode) {
        if (checkServer < 0) {
            toast("Choose a server");
            return;
        }
        checkMode = mode;
        checkNote = Tr.t("Checking...");
        checkResult = null;
        render();
        senko.check(checkServer, mode, new Senko.Done<Model.CheckResult>() {
            @Override
            public void done(Model.CheckResult result, IOException error) {
                checkNote = error != null ? Senko.describe(error) : "";
                checkResult = result;
                render();
            }
        });
    }


    private void renderForce() {
        boolean engine = settings != null;
        rows.section("Force");
        rows.toggle("code", "Ignore device gating", engine && "1".equals(settings.get("sub_ignore_gating")), engine,
                new Toggle.Listener() {
                    @Override
                    public void changed(Toggle toggle, boolean on) {
                        set("sub_ignore_gating", on ? "1" : "0", toggle);
                    }
                });
        rows.toggle("logs", "Session trace", engine && "1".equals(settings.get("trace")), engine,
                new Toggle.Listener() {
                    @Override
                    public void changed(Toggle toggle, boolean on) {
                        set("trace", on ? "1" : "0", toggle);
                    }
                });
        rows.action("rocket", "Tunnel core", engine ? coreLabel(settings.get("core")) : Tr.t("unreachable"),
                engine, new View.OnClickListener() {
                    @Override
                    public void onClick(View view) {
                        chooseCore();
                    }
                });
        rows.action("shield", "Blocked answers", engine ? settings.get("block_response") : Tr.t("unreachable"),
                engine, new View.OnClickListener() {
                    @Override
                    public void onClick(View view) {
                        chooseBlockAnswer();
                    }
                });
        rows.footer(Tr.t("Automatic uses the Xray core when it can carry the profile. Applied on the next connect."));
        rows.section("Maintenance");
        rows.action("reconnect", "Flush DNS cache", "", flush("dns", "Flush DNS cache", "Flush"));
        rows.action("split", "Forget direct addresses", "", flush("bypass", "Forget direct addresses", "Reset"));
        rows.action("gear", "Reset settings", "", flush("config", "Reset settings", "Reset"));
        rows.footer(Tr.t("Catalog and rules are kept."));
    }

    private View.OnClickListener flush(final String what, final String title, final String button) {
        return new View.OnClickListener() {
            @Override
            public void onClick(View view) {
                Dialogs.confirm(DevToolsActivity.this, title, "This cannot be undone.", button, new Runnable() {
                    @Override
                    public void run() {
                        senko.run(false, new Senko.Work<String>() {
                            @Override
                            public String run(ControlClient client) throws IOException {
                                return client.flush(what);
                            }
                        }, new Senko.Done<String>() {
                            @Override
                            public void done(String reply, IOException error) {
                                if (error != null) toastError(error);
                                else toast("Done.");
                                load();
                            }
                        });
                    }
                });
            }
        };
    }

    private void set(final String key, final String value, final Toggle toggle) {
        senko.run(false, new Senko.Work<String>() {
            @Override
            public String run(ControlClient client) throws IOException {
                return client.set(key, value);
            }
        }, new Senko.Done<String>() {
            @Override
            public void done(String reply, IOException error) {
                if (error == null) {
                    settings.put(key, value);
                    return;
                }
                toastError(error);
                if (toggle != null) toggle.setChecked("1".equals(settings.get(key)));
            }
        });
    }

    private static final String[] CORES = { "auto", "xray", "engine" };

    private static String coreLabel(String value) {
        if ("xray".equals(value)) return Tr.t("Xray core");
        if ("engine".equals(value)) return Tr.t("Built-in engine");
        return Tr.t("Automatic");
    }

    private void chooseCore() {
        List<String> labels = new ArrayList<String>();
        for (String core : CORES) labels.add(coreLabel(core));
        Dialogs.menu(this, "Tunnel core", labels, new Dialogs.Pick() {
            @Override
            public void picked(int index) {
                set("core", CORES[index], null);
                settings.put("core", CORES[index]);
                render();
            }
        });
    }

    private void chooseBlockAnswer() {
        List<String> labels = new ArrayList<String>();
        for (String answer : BLOCK_ANSWERS) labels.add(answer);
        Dialogs.menu(this, "Blocked answers", labels, new Dialogs.Pick() {
            @Override
            public void picked(int index) {
                set("block_response", BLOCK_ANSWERS[index], null);
                settings.put("block_response", BLOCK_ANSWERS[index]);
                render();
            }
        });
    }


    private void renderRescue() {
        rows.section("Device id");
        rows.action("hash", "Device id", hwid.isEmpty() ? Tr.t("unreachable") : hwid, false,
                new View.OnClickListener() {
                    @Override
                    public void onClick(View view) {
                        if (hwid.isEmpty()) return;
                        Dialogs.copy(DevToolsActivity.this, "device id", hwid);
                        toast("Copied to the clipboard.");
                    }
                });
        rows.action("repeat", "New device id", "", new View.OnClickListener() {
            @Override
            public void onClick(View view) {
                Dialogs.confirm(DevToolsActivity.this, "New device id",
                        "Subscription panels will see a new device.", "Reset", new Runnable() {
                            @Override
                            public void run() {
                                resetHwid();
                            }
                        });
            }
        });
        rows.section("Remove");
        rows.action("logs", "Delete all rules", "", flush("rules", "Delete all rules", "Delete"));
        rows.action("backup", "Export debug bundle", "", new View.OnClickListener() {
            @Override
            public void onClick(View view) {
                exportBundle();
            }
        });
    }

    private void resetHwid() {
        senko.run(false, new Senko.Work<String>() {
            @Override
            public String run(ControlClient client) throws IOException {
                client.resetHwid();
                return client.hwid();
            }
        }, new Senko.Done<String>() {
            @Override
            public void done(String value, IOException error) {
                if (error != null) toastError(error);
                else hwid = value == null ? "" : value;
                render();
            }
        });
    }

    /** diagnostics and the engine log, redacted, as one text any app can take */
    private void exportBundle() {
        senko.run(true, new Senko.Work<String>() {
            @Override
            public String run(ControlClient client) throws IOException {
                StringBuilder out = new StringBuilder();
                out.append("senko ").append(AboutActivity.versionName(DevToolsActivity.this)).append('\n');
                out.append(AboutActivity.deviceLine()).append("\n\n");
                for (Model.DiagFact fact : client.diagnostics())
                    out.append(fact.key).append(": ").append(fact.value).append('\n');
                out.append("\n").append(client.logs());
                return Errors.redact(out.toString());
            }
        }, new Senko.Done<String>() {
            @Override
            public void done(String bundle, IOException error) {
                if (error != null) {
                    toastError(error);
                    return;
                }
                Intent send = new Intent(Intent.ACTION_SEND);
                send.setType("text/plain");
                send.putExtra(Intent.EXTRA_TEXT, bundle);
                startActivity(Intent.createChooser(send, Tr.t("Export debug bundle")));
            }
        });
    }


    private void buildConsole() {
        LinearLayout column = new LinearLayout(this);
        column.setOrientation(LinearLayout.VERTICAL);
        consoleOut = new TextView(this);
        consoleOut.setTypeface(Typeface.MONOSPACE);
        consoleOut.setTextSize(11);
        consoleOut.setTextColor(Theme.ink());
        Compat.selectable(consoleOut, true);
        consoleOut.setPadding(Ui.dp(10), Ui.dp(8), Ui.dp(10), Ui.dp(8));
        consoleOut.setText(Tr.t("Type a control command, for example STATUS or LIST."));
        consoleScroll = new ScrollView(this);
        consoleScroll.addView(consoleOut);
        column.addView(consoleScroll, new LinearLayout.LayoutParams(-1, 0, 1));

        LinearLayout bar = new LinearLayout(this);
        bar.setPadding(Ui.dp(8), Ui.dp(6), Ui.dp(8), Ui.dp(6));
        final EditText input = new EditText(this);
        input.setSingleLine(true);
        input.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
                | InputType.TYPE_TEXT_FLAG_CAP_CHARACTERS);
        input.setImeOptions(EditorInfo.IME_ACTION_SEND);
        input.setTypeface(Typeface.MONOSPACE);
        input.setTextColor(Theme.ink());
        input.setTextSize(14);
        Ui.background(input, Ui.plate(10, Theme.color(Theme.WELL)));
        input.setPadding(Ui.dp(12), 0, Ui.dp(12), 0);
        bar.addView(input, new LinearLayout.LayoutParams(0, Ui.dp(42), 1));
        TextView send = Ui.text(this, Tr.t("Send"), 15, Ui.onColor(Theme.color(Theme.ACCENT)), true);
        send.setGravity(android.view.Gravity.CENTER);
        send.setPadding(Ui.dp(16), 0, Ui.dp(16), 0);
        Ui.background(send, Ui.solid(Theme.color(Theme.ACCENT), 10));
        LinearLayout.LayoutParams sendParams = new LinearLayout.LayoutParams(-2, Ui.dp(42));
        sendParams.leftMargin = Ui.dp(8);
        bar.addView(send, sendParams);
        column.addView(bar);
        setBody(column);

        final Runnable submit = new Runnable() {
            @Override
            public void run() {
                String line = input.getText().toString().trim();
                if (line.isEmpty()) return;
                input.setText("");
                sendConsole(line);
            }
        };
        send.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View view) {
                submit.run();
            }
        });
        input.setOnEditorActionListener(new TextView.OnEditorActionListener() {
            @Override
            public boolean onEditorAction(TextView v, int actionId, android.view.KeyEvent event) {
                submit.run();
                return true;
            }
        });
    }

    private void sendConsole(final String line) {
        append("> " + line);
        senko.run(false, new Senko.Work<List<String>>() {
            @Override
            public List<String> run(ControlClient client) throws IOException {
                return client.raw(line, 2500);
            }
        }, new Senko.Done<List<String>>() {
            @Override
            public void done(List<String> lines, IOException error) {
                if (error != null) {
                    append(Senko.describe(error));
                    return;
                }
                for (String reply : lines) append(Errors.redact(reply));
            }
        });
    }

    private void append(String line) {
        String current = consoleOut.getText().toString();
        // the buffer is bounded so a long session cannot grow without limit
        if (current.length() > 60000) current = current.substring(current.length() - 40000);
        consoleOut.setText(current + "\n" + line);
        consoleScroll.post(new Runnable() {
            @Override
            public void run() {
                consoleScroll.fullScroll(View.FOCUS_DOWN);
            }
        });
    }
}
