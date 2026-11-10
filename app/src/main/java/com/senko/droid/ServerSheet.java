package com.senko.droid;

import android.app.Activity;
import android.content.DialogInterface;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

/** what the list can do with one server: its facts and the actions on it */
final class ServerSheet {
    private ServerSheet() {}

    interface Host {
        void toastEnglish(String english);

        void fail(String text);

        void changed();
    }

    static void show(final Activity activity, final Model.Server server, final List<Integer> members,
                     final Host host) {
        final Senko senko = Senko.get();
        final boolean current = server.selected && senko.isActive() && senko.activeBackend() == Prefs.BACKEND_SERVER;
        int ink = Theme.color(Theme.INK);
        int muted = Theme.color(Theme.INK_MUTED);
        LinearLayout box = new LinearLayout(activity);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(Ui.dp(20), Ui.dp(4), Ui.dp(20), 0);
        fact(activity, box, "Host", server.port > 0 ? server.host + ":" + server.port : server.host, ink, muted);
        fact(activity, box, "Protocol", server.proto, ink, muted);
        fact(activity, box, "Transport", server.net, ink, muted);
        fact(activity, box, "Security", server.security, ink, muted);
        fact(activity, box, "Latency", latency(senko.ping(server)), ink, muted);
        fact(activity, box, "Source", source(senko, server), ink, muted);
        if (members.size() > 1)
            fact(activity, box, "Same name", String.valueOf(members.size()), ink, muted);
        if (!server.supported) {
            TextView warning = Ui.text(activity, Tr.t("This server uses a transport senko cannot dial yet"), 13,
                    Theme.stateColor("error"), false);
            warning.setPadding(0, Ui.dp(8), 0, 0);
            box.addView(warning);
        }
        ScrollView scroll = new ScrollView(activity);
        scroll.addView(box);

        final List<String> actions = new ArrayList<String>();
        final List<Runnable> run = new ArrayList<Runnable>();
        if (server.supported) {
            if (current) {
                actions.add(Tr.t("Disconnect"));
                run.add(new Runnable() {
                    @Override
                    public void run() {
                        senko.disconnect(null);
                    }
                });
            } else {
                actions.add(Tr.t("Connect"));
                run.add(new Runnable() {
                    @Override
                    public void run() {
                        connect(senko, server);
                    }
                });
            }
        }
        actions.add(Tr.t("Ping"));
        run.add(new Runnable() {
            @Override
            public void run() {
                senko.pingServers(members, null);
            }
        });
        actions.add(Tr.t("Copy link"));
        run.add(new Runnable() {
            @Override
            public void run() {
                copyLink(activity, senko, server, host);
            }
        });
        if (server.group < 0) {
            actions.add(Tr.t("Edit"));
            run.add(new Runnable() {
                @Override
                public void run() {
                    edit(activity, senko, server, host);
                }
            });
            actions.add(Tr.t("Remove"));
            run.add(new Runnable() {
                @Override
                public void run() {
                    remove(activity, senko, server, host);
                }
            });
        }

        final SenkoDialog[] dialog = new SenkoDialog[1];
        LinearLayout list = new LinearLayout(activity);
        list.setOrientation(LinearLayout.VERTICAL);
        box.addView(Ui.divider(activity));
        for (int i = 0; i < actions.size(); ++i) {
            final Runnable action = run.get(i);
            TextView item = Ui.text(activity, actions.get(i), 16,
                    actions.get(i).equals(Tr.t("Remove")) ? Theme.stateColor("error") : Theme.color(Theme.ACCENT), false);
            item.setPadding(0, Ui.dp(12), 0, Ui.dp(12));
            Ui.background(item, Ui.rowPress());
            item.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View view) {
                    dialog[0].dismiss();
                    action.run();
                }
            });
            box.addView(item);
        }
        dialog[0] = new SenkoDialog.Builder(activity)
                .setTitle(Names.title(server))
                .setView(scroll)
                .setMood(SenkoDialog.MOOD_ASK)
                .setNegativeButton(Tr.t("Close"), null)
                .create();
        dialog[0].show();
    }

    private static void fact(Activity activity, LinearLayout box, String label, String value, int ink, int muted) {
        if (value == null || value.isEmpty()) return;
        LinearLayout line = new LinearLayout(activity);
        line.setPadding(0, Ui.dp(4), 0, Ui.dp(4));
        TextView name = Ui.text(activity, Tr.t(label), 14, muted, false);
        line.addView(name, new LinearLayout.LayoutParams(Ui.dp(96), -2));
        TextView text = Ui.text(activity, value, 14, ink, false);
        Compat.selectable(text, false);
        line.addView(text, Ui.weighted(1));
        box.addView(line);
    }

    static String latency(int ms) {
        if (ms >= 0) return ms + " ms";
        if (ms == -3) return Tr.t("Checking...");
        if (ms == -1) return Tr.t("No answer");
        return "";
    }

    private static String source(Senko senko, Model.Server server) {
        if (server.group < 0) return Tr.t("Manual");
        Model.Sub sub = senko.catalog().sub(server.group);
        if (sub == null) return "";
        return ServerList.subscriptionTitle(sub.name.isEmpty() ? "Subscription" : sub.name.replace('_', ' '))[0];
    }

    private static void connect(final Senko senko, final Model.Server server) {
        Prefs.setAutoServer(false);
        senko.pick(server.index);
        if (senko.isActive() && senko.activeBackend() == Prefs.BACKEND_AWG) {
            senko.disconnect(new Senko.Done<Void>() {
                @Override
                public void done(Void value, IOException error) {
                    if (error == null) senko.connect(server.index, null);
                }
            });
            return;
        }
        senko.connect(server.index, null);
    }

    private static void copyLink(final Activity activity, final Senko senko, final Model.Server server,
                                 final Host host) {
        senko.run(false, new Senko.Work<String>() {
            @Override
            public String run(ControlClient client) throws IOException {
                return client.link(server.index);
            }
        }, new Senko.Done<String>() {
            @Override
            public void done(String link, IOException error) {
                if (error != null) {
                    host.fail(Senko.describe(error));
                    return;
                }
                Dialogs.copy(activity, "link", link);
                host.toastEnglish("Link copied");
            }
        });
    }

    private static void edit(final Activity activity, final Senko senko, final Model.Server server,
                             final Host host) {
        senko.run(false, new Senko.Work<String>() {
            @Override
            public String run(ControlClient client) throws IOException {
                return client.link(server.index);
            }
        }, new Senko.Done<String>() {
            @Override
            public void done(String link, IOException error) {
                if (error != null) {
                    host.fail(Senko.describe(error));
                    return;
                }
                Dialogs.prompt(activity, "Edit server", "server link", link, Dialogs.urlInput(), "Save",
                        new Dialogs.Text() {
                            @Override
                            public void entered(final String text) {
                                senko.run(true, new Senko.Work<String>() {
                                    @Override
                                    public String run(ControlClient client) throws IOException {
                                        return client.replaceServer(server.index, text);
                                    }
                                }, new Senko.Done<String>() {
                                    @Override
                                    public void done(String value, IOException failure) {
                                        senko.loadCatalog(null);
                                        if (failure != null) host.fail(Senko.describe(failure));
                                        else host.changed();
                                    }
                                });
                            }
                        });
            }
        });
    }

    private static void remove(final Activity activity, final Senko senko, final Model.Server server,
                               final Host host) {
        Dialogs.confirm(activity, "Remove server", "Remove this server from the list?", "Remove", new Runnable() {
            @Override
            public void run() {
                senko.run(true, new Senko.Work<String>() {
                    @Override
                    public String run(ControlClient client) throws IOException {
                        return client.deleteServer(server.index);
                    }
                }, new Senko.Done<String>() {
                    @Override
                    public void done(String value, IOException error) {
                        senko.loadCatalog(null);
                        if (error != null) host.fail(Senko.describe(error));
                    }
                });
            }
        });
    }
}
