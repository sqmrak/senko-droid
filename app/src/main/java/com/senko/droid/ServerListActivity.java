package com.senko.droid;

import android.app.Activity;
import android.content.Intent;
import android.graphics.drawable.Drawable;
import android.os.Bundle;
import android.text.Editable;
import android.text.InputType;
import android.text.TextWatcher;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.AdapterView;
import android.widget.BaseAdapter;
import android.widget.EditText;
import android.widget.HorizontalScrollView;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.ProgressBar;
import android.widget.TextView;

import java.io.IOException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * the servers of every subscription and the manual ones in one list, with a
 * search, group chips, sorting and ping sweeps. choosing a row picks the
 * server for the power button, and switches to it when the tunnel is up
 */
public final class ServerListActivity extends BaseActivity implements ImportFlow.Host, ServerSheet.Host {
    private static final int ITEM_HEADER = 0;
    private static final int ITEM_ROW = 1;
    private static final int ITEM_AWG = 2;
    private static final int ITEM_AUTO = 3;

    private static final class Item {
        int type;
        ServerList.Section section;
        ServerList.Row row;
    }

    private final List<Item> items = new ArrayList<Item>();
    private ServerList.Result result;
    private Integer filter;
    private String query = "";
    private ListView list;
    private LinearLayout chips;
    private TextView empty;
    private View emptyState;
    private TextView hwidValue;
    private Adapter adapter;

    private final ServerList.Pings pings = new ServerList.Pings() {
        @Override
        public int of(int serverIndex) {
            Model.Server server = senko.catalog().server(serverIndex);
            return server == null ? -2 : senko.ping(server);
        }
    };

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        setScreenTitle("Servers");
        int ink = barInk();
        addBarIcon(Icons.glyph(this, "sort", ink), "Sort", new View.OnClickListener() {
            @Override
            public void onClick(View view) {
                chooseSort();
            }
        });
        addBarIcon(Icons.shape(this, Icons.Shape.GAUGE, ink), "Check speed", new View.OnClickListener() {
            @Override
            public void onClick(View view) {
                senko.pingGroup(-2, null);
            }
        });
        addBarIcon(Icons.shape(this, Icons.Shape.PLUS, ink), "Add", new View.OnClickListener() {
            @Override
            public void onClick(View view) {
                ImportFlow.showAddMenu(ServerListActivity.this);
            }
        });

        LinearLayout column = new LinearLayout(this);
        column.setOrientation(LinearLayout.VERTICAL);

        EditText search = new EditText(this);
        search.setSingleLine(true);
        search.setHint(Tr.t("Search"));
        search.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS);
        search.setTextColor(Theme.ink());
        search.setHintTextColor(Theme.color(Theme.INK_MUTED));
        search.setTextSize(15);
        search.setPadding(Ui.dp(14), 0, Ui.dp(14), 0);
        Ui.background(search, Ui.plate(10, Theme.color(Theme.WELL)));
        search.addTextChangedListener(new TextWatcher() {
            @Override
            public void beforeTextChanged(CharSequence s, int start, int count, int after) {}

            @Override
            public void onTextChanged(CharSequence s, int start, int before, int count) {}

            @Override
            public void afterTextChanged(Editable s) {
                query = s.toString();
                rebuild();
            }
        });
        LinearLayout.LayoutParams searchParams = new LinearLayout.LayoutParams(-1, Ui.dp(40));
        searchParams.setMargins(Ui.dp(12), Ui.dp(10), Ui.dp(12), Ui.dp(6));
        column.addView(search, searchParams);

        HorizontalScrollView scroller = new HorizontalScrollView(this);
        scroller.setHorizontalScrollBarEnabled(false);
        chips = new LinearLayout(this);
        chips.setPadding(Ui.dp(8), 0, Ui.dp(8), Ui.dp(6));
        scroller.addView(chips);
        column.addView(scroller, new LinearLayout.LayoutParams(-1, -2));

        list = new ListView(this);
        list.setDivider(null);
        list.setSelector(Ui.listSelector());
        list.setCacheColorHint(0);
        adapter = new Adapter();
        list.setAdapter(adapter);
        list.setOnItemClickListener(new AdapterView.OnItemClickListener() {
            @Override
            public void onItemClick(AdapterView<?> parent, View view, int position, long id) {
                tapped(items.get(position));
            }
        });
        list.setOnItemLongClickListener(new AdapterView.OnItemLongClickListener() {
            @Override
            public boolean onItemLongClick(AdapterView<?> parent, View view, int position, long id) {
                return held(items.get(position));
            }
        });
        empty = Ui.text(this, Tr.t("Nothing found"), 15, Theme.color(Theme.INK_MUTED), false);
        empty.setGravity(Gravity.CENTER);
        empty.setVisibility(View.GONE);
        emptyState = buildEmptyState();
        emptyState.setVisibility(View.GONE);
        android.widget.FrameLayout stack = new android.widget.FrameLayout(this);
        stack.addView(list, new android.widget.FrameLayout.LayoutParams(-1, -1));
        stack.addView(empty, new android.widget.FrameLayout.LayoutParams(-1, -1));
        // below the auto row, which stays in the list: the block scrolls when a keyboard takes the
        // room, instead of being centred over the rows above it
        android.widget.FrameLayout.LayoutParams emptyParams = new android.widget.FrameLayout.LayoutParams(-1, -1);
        emptyParams.topMargin = Ui.dp(64);
        stack.addView(emptyState, emptyParams);
        column.addView(stack, new LinearLayout.LayoutParams(-1, 0, 1));
        setBody(column);
        rebuild();
    }

    @Override
    protected void onStart() {
        super.onStart();
        senko.loadCatalog(null);
        rebuild();
    }

    @Override
    public void onChange(int what) {
        if ((what & (Senko.CATALOG | Senko.PING | Senko.STATE | Senko.BUSY)) != 0) rebuild();
    }

    /** the screen of a list with nothing in it: what to add, and the device id panels may ask for */
    private View buildEmptyState() {
        android.widget.ScrollView scroll = new android.widget.ScrollView(this);
        scroll.setFillViewport(true);
        scroll.setVerticalScrollBarEnabled(false);
        scroll.addView(buildEmptyBox(), new android.widget.FrameLayout.LayoutParams(-1, -2));
        return scroll;
    }

    private View buildEmptyBox() {
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setGravity(Gravity.CENTER);
        box.setPadding(Ui.dp(28), Ui.dp(20), Ui.dp(28), Ui.dp(20));
        ImageView icon = new ImageView(this);
        icon.setImageDrawable(Icons.glyph(this, "cloud", Theme.color(Theme.ACCENT)));
        box.addView(icon, new LinearLayout.LayoutParams(Ui.dp(56), Ui.dp(56)));
        TextView headline = Ui.text(this, Tr.t("No servers yet"), 20, Theme.ink(), true);
        headline.setGravity(Gravity.CENTER);
        headline.setPadding(0, Ui.dp(12), 0, Ui.dp(6));
        box.addView(headline);
        TextView body = Ui.text(this, Tr.t("To use a server add a proxy link or a subscription."), 14,
                Theme.color(Theme.INK_MUTED), false);
        body.setGravity(Gravity.CENTER);
        box.addView(body);

        LinearLayout buttons = new LinearLayout(this);
        buttons.setGravity(Gravity.CENTER);
        buttons.setPadding(0, Ui.dp(18), 0, Ui.dp(18));
        box.setMinimumWidth(0);
        // three buttons share the width equally, so a narrow screen shortens them and never wraps a word
        buttons.addView(emptyButton("Paste", new View.OnClickListener() {
            @Override
            public void onClick(View view) {
                ImportFlow.paste(ServerListActivity.this);
            }
        }));
        if (Wide.hasCamera(this)) buttons.addView(emptyButton("QR code", new View.OnClickListener() {
            @Override
            public void onClick(View view) {
                scanQr();
            }
        }));
        buttons.addView(emptyButton("Add", new View.OnClickListener() {
            @Override
            public void onClick(View view) {
                ImportFlow.showAddMenu(ServerListActivity.this);
            }
        }));
        box.addView(buttons);

        final TextView caption = Ui.text(this, Tr.t("Device ID (tap to copy)"), 12, Theme.color(Theme.INK_MUTED), false);
        caption.setGravity(Gravity.CENTER);
        box.addView(caption);
        hwidValue = Ui.text(this, "", 13, Theme.ink(), false);
        hwidValue.setTypeface(android.graphics.Typeface.MONOSPACE);
        hwidValue.setGravity(Gravity.CENTER);
        hwidValue.setPadding(0, Ui.dp(4), 0, 0);
        box.addView(hwidValue);
        View.OnClickListener copy = new View.OnClickListener() {
            @Override
            public void onClick(View view) {
                if (senko.hwid().isEmpty()) return;
                Dialogs.copy(ServerListActivity.this, "device id", senko.hwid());
                caption.setText(Tr.t("Device ID copied"));
            }
        };
        caption.setOnClickListener(copy);
        hwidValue.setOnClickListener(copy);
        return box;
    }

    private View emptyButton(String english, View.OnClickListener click) {
        TextView button = Ui.text(this, Tr.t(english), 15, Ui.onColor(Theme.color(Theme.ACCENT)), true);
        button.setGravity(Gravity.CENTER);
        button.setPadding(Ui.dp(8), Ui.dp(10), Ui.dp(8), Ui.dp(10));
        button.setSingleLine(true);
        button.setEllipsize(android.text.TextUtils.TruncateAt.END);
        Ui.background(button, Ui.solid(Theme.color(Theme.ACCENT), Theme.current().radius));
        button.setOnClickListener(click);
        // equal shares of the row: with wrap_content the last button was squeezed to a narrow column and
        // its label broke into syllables on a 480 px screen
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(0, -2, 1f);
        params.setMargins(Ui.dp(4), 0, Ui.dp(4), 0);
        button.setLayoutParams(params);
        return button;
    }


    private void rebuild() {
        if (list == null) return;
        Model.Catalog catalog = senko.catalog();
        result = ServerList.build(catalog, senko.picked(), Prefs.sortMode(), pings, filter, query,
                senko.hasAwgProfile(), System.currentTimeMillis() / 1000, Prefs.language());
        if (filter != null && !result.chipKeys.contains(filter)) filter = null;
        items.clear();
        boolean searching = !query.trim().isEmpty();
        if (!searching && filter == null) items.add(item(ITEM_AUTO, null, null));
        Set<String> collapsed = Prefs.collapsed();
        for (ServerList.Section section : result.sections) {
            items.add(item(ITEM_HEADER, section, null));
            // a search shows every match, even inside a collapsed section
            if (collapsed.contains(sectionKey(section)) && !searching) continue;
            for (ServerList.Row row : section.rows) items.add(item(ITEM_ROW, section, row));
            if (section.manual() && result.awgRowShown) items.add(item(ITEM_AWG, section, null));
        }
        buildChips();
        boolean none = result.sections.isEmpty();
        boolean blank = none && catalogIsEmpty() && senko.catalogLoaded();
        emptyState.setVisibility(blank ? View.VISIBLE : View.GONE);
        empty.setVisibility(none && !blank ? View.VISIBLE : View.GONE);
        hwidValue.setText(senko.hwid().isEmpty() ? Tr.t("not available yet") : senko.hwid());
        adapter.notifyDataSetChanged();
    }

    private boolean catalogIsEmpty() {
        return senko.catalog().servers.isEmpty() && !senko.hasAwgProfile();
    }

    private static Item item(int type, ServerList.Section section, ServerList.Row row) {
        Item item = new Item();
        item.type = type;
        item.section = section;
        item.row = row;
        return item;
    }

    /** a subscription is known by its address, since its index moves when another is removed */
    private String sectionKey(ServerList.Section section) {
        if (section.manual()) return "manual";
        Model.Sub sub = senko.catalog().sub(section.subIdx);
        return sub == null ? "sub" + section.subIdx : "sub:" + sub.url;
    }

    private void buildChips() {
        chips.removeAllViews();
        // a single chip filters nothing
        if (result.chipKeys.size() <= 2) return;
        for (int i = 0; i < result.chipKeys.size(); ++i) {
            final Integer key = result.chipKeys.get(i);
            boolean on = key == null ? filter == null : key.equals(filter);
            int accent = Theme.color(Theme.ACCENT);
            TextView chip = Ui.text(this, i == 0 ? Tr.t("All") : result.chipTitles.get(i), 13,
                    on ? Ui.onColor(accent) : Theme.ink(), on);
            chip.setSingleLine(true);
            chip.setPadding(Ui.dp(12), Ui.dp(6), Ui.dp(12), Ui.dp(6));
            Ui.background(chip, on ? Ui.solid(accent, 14)
                    : Ui.plate(14, Theme.color(Theme.CELL_HI)));
            chip.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View view) {
                    filter = key;
                    rebuild();
                }
            });
            LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(-2, -2);
            params.setMargins(Ui.dp(4), 0, Ui.dp(4), 0);
            chips.addView(chip, params);
        }
    }


    private void chooseSort() {
        List<String> labels = new ArrayList<String>();
        labels.add(Tr.t("Default order"));
        labels.add(Tr.t("By ping"));
        labels.add(Tr.t("By name"));
        Dialogs.menu(this, "Sort servers", labels, new Dialogs.Pick() {
            @Override
            public void picked(int index) {
                Prefs.setSortMode(index);
                rebuild();
            }
        });
    }

    private void tapped(Item item) {
        if (item.type == ITEM_HEADER) {
            toggle(item.section);
        } else if (item.type == ITEM_ROW) {
            choose(item.row);
        } else if (item.type == ITEM_AWG) {
            chooseAwg();
        } else if (item.type == ITEM_AUTO) {
            chooseAuto();
        }
    }

    private boolean held(Item item) {
        if (item.type == ITEM_ROW) {
            ServerSheet.show(this, item.row.server, item.row.members, this);
            return true;
        }
        if (item.type == ITEM_HEADER) {
            sectionMenu(item.section);
            return true;
        }
        if (item.type == ITEM_AWG) {
            awgMenu();
            return true;
        }
        return false;
    }

    // the section that was just opened or closed, its chevron turns on the next build
    private String toggledKey = "";

    private void toggle(ServerList.Section section) {
        Set<String> next = new HashSet<String>(Prefs.collapsed());
        String key = sectionKey(section);
        toggledKey = key;
        if (!next.remove(key)) next.add(key);
        Prefs.setCollapsed(next);
        rebuild();
    }

    private void switchTo(final Runnable start) {
        if (!senko.isActive()) {
            start.run();
            return;
        }
        // one tunnel at a time: the other backend is stopped first
        if (senko.activeBackend() == Prefs.BACKEND_AWG) {
            senko.disconnect(new Senko.Done<Void>() {
                @Override
                public void done(Void value, IOException error) {
                    if (error == null) start.run();
                }
            });
            return;
        }
        start.run();
    }

    private void choose(final ServerList.Row row) {
        if (!row.server.supported) {
            toast("This server uses a transport senko cannot dial yet");
            return;
        }
        final boolean wasActive = senko.isActive();
        final int target = ServerList.pick(row, pings);
        Prefs.setAutoServer(false);
        senko.pick(target);
        if (wasActive) {
            switchTo(new Runnable() {
                @Override
                public void run() {
                    senko.connect(target, null);
                }
            });
        }
        finish();
    }

    private void chooseAuto() {
        final boolean wasActive = senko.isActive();
        Prefs.setAutoServer(true);
        senko.selectServer();
        if (wasActive && senko.activeBackend() == Prefs.BACKEND_AWG) senko.disconnect(null);
        finish();
    }

    private void chooseAwg() {
        final boolean wasActive = senko.isActive();
        senko.selectAwg();
        if (wasActive && senko.activeBackend() == Prefs.BACKEND_SERVER) {
            senko.disconnect(new Senko.Done<Void>() {
                @Override
                public void done(Void value, IOException error) {
                    if (error == null) senko.startAwg(null);
                }
            });
        }
        finish();
    }

    private void sectionMenu(final ServerList.Section section) {
        final List<String> labels = new ArrayList<String>();
        final List<Runnable> actions = new ArrayList<Runnable>();
        final boolean manual = section.manual();
        if (!manual) {
            labels.add(Tr.t("Refresh now"));
            actions.add(new Runnable() {
                @Override
                public void run() {
                    refresh(section.subIdx);
                }
            });
        }
        labels.add(Tr.t("Check ping"));
        actions.add(new Runnable() {
            @Override
            public void run() {
                senko.pingGroup(manual ? -1 : section.subIdx, null);
            }
        });
        if (!manual) {
            labels.add(Tr.t("Subscription details"));
            actions.add(new Runnable() {
                @Override
                public void run() {
                    details(section);
                }
            });
            Model.Sub panel = senko.catalog().sub(section.subIdx);
            if (panel != null && openable(panel.webPageUrl)) {
                final String page = panel.webPageUrl;
                labels.add(Tr.t("Account page"));
                actions.add(new Runnable() {
                    @Override
                    public void run() {
                        open(page);
                    }
                });
            }
            if (panel != null && openable(panel.supportUrl)) {
                final String support = panel.supportUrl;
                labels.add(Tr.t("Support"));
                actions.add(new Runnable() {
                    @Override
                    public void run() {
                        open(support);
                    }
                });
            }
            labels.add(Tr.t("Edit details"));
            actions.add(new Runnable() {
                @Override
                public void run() {
                    Intent intent = new Intent(ServerListActivity.this, EditSubscriptionActivity.class);
                    intent.putExtra(EditSubscriptionActivity.EXTRA_INDEX, section.subIdx);
                    startActivity(intent);
                }
            });
        }
        labels.add(Tr.t("Move up"));
        actions.add(new Runnable() {
            @Override
            public void run() {
                move(section, -1);
            }
        });
        labels.add(Tr.t("Move down"));
        actions.add(new Runnable() {
            @Override
            public void run() {
                move(section, 1);
            }
        });
        labels.add(Tr.t(manual ? "Delete all servers" : "Remove"));
        actions.add(new Runnable() {
            @Override
            public void run() {
                if (manual) clearManual();
                else removeSubscription(section);
            }
        });
        Dialogs.menu(this, section.title, labels, new Dialogs.Pick() {
            @Override
            public void picked(int index) {
                actions.get(index).run();
            }
        });
    }

    /** a link the panel sent is opened only as a web or telegram address, never any other scheme */
    private static boolean openable(String url) {
        if (url == null) return false;
        String lower = url.toLowerCase(java.util.Locale.US);
        return lower.startsWith("https://") || lower.startsWith("http://") || lower.startsWith("tg://");
    }

    private void open(String url) {
        try {
            startActivity(new Intent(Intent.ACTION_VIEW, android.net.Uri.parse(url)));
        } catch (android.content.ActivityNotFoundException e) {
            toast("No app can open this link");
        }
    }

    private void refresh(final int subIdx) {
        senko.run(true, new Senko.Work<String>() {
            @Override
            public String run(ControlClient client) throws IOException {
                return client.refreshSubscription(subIdx);
            }
        }, new Senko.Done<String>() {
            @Override
            public void done(String value, IOException error) {
                senko.loadCatalog(null);
                if (error != null) toastError(error);
                else toast("Subscription refreshed");
            }
        });
    }

    private void details(ServerList.Section section) {
        Model.Sub sub = senko.catalog().sub(section.subIdx);
        if (sub == null) return;
        StringBuilder text = new StringBuilder();
        if (!sub.url.isEmpty()) text.append(sub.url).append("\n\n");
        if (!section.meta.isEmpty()) text.append(section.meta).append("\n");
        if (!sub.description.isEmpty()) text.append("\n").append(sub.description).append("\n");
        if (!sub.announce.isEmpty()) text.append("\n").append(sub.announce).append("\n");
        if (!sub.webPageUrl.isEmpty()) text.append("\n").append(sub.webPageUrl);
        if (!sub.supportUrl.isEmpty()) text.append("\n").append(sub.supportUrl);
        if (sub.refillDate > 0) {
            text.append("\n\n").append(Tr.t("Counter resets")).append(": ")
                    .append(java.text.DateFormat.getDateInstance(java.text.DateFormat.MEDIUM)
                            .format(new java.util.Date(sub.refillDate * 1000L)));
        }
        if (sub.updateHours > 0) {
            text.append("\n").append(Tr.t("The panel asks for a refresh every")).append(" ")
                    .append(sub.updateHours).append(" ").append(Tr.t("h"));
        }
        Dialogs.message(this, section.title, text.toString().trim());
    }

    private void move(ServerList.Section section, int step) {
        int[] order = senko.catalog().order;
        int at = -1;
        for (int i = 0; i < order.length; ++i) if (order[i] == section.subIdx) at = i;
        int to = at + step;
        if (at < 0 || to < 0 || to >= order.length) return;
        final int id = section.subIdx;
        final int position = to;
        senko.run(false, new Senko.Work<String>() {
            @Override
            public String run(ControlClient client) throws IOException {
                return client.moveSection(id, position);
            }
        }, new Senko.Done<String>() {
            @Override
            public void done(String value, IOException error) {
                senko.loadCatalog(null);
                if (error != null) toastError(error);
            }
        });
    }

    private void clearManual() {
        Dialogs.confirm(this, "Delete all servers", "Remove every server added by hand?", "Delete", new Runnable() {
            @Override
            public void run() {
                senko.run(true, new Senko.Work<String>() {
                    @Override
                    public String run(ControlClient client) throws IOException {
                        return client.clearManual();
                    }
                }, new Senko.Done<String>() {
                    @Override
                    public void done(String value, IOException error) {
                        senko.loadCatalog(null);
                        if (error != null) toastError(error);
                    }
                });
            }
        });
    }

    private void removeSubscription(final ServerList.Section section) {
        Dialogs.confirm(this, "Remove subscription", "Remove this subscription and its servers?", "Remove",
                new Runnable() {
                    @Override
                    public void run() {
                        senko.run(true, new Senko.Work<String>() {
                            @Override
                            public String run(ControlClient client) throws IOException {
                                return client.deleteSubscription(section.subIdx);
                            }
                        }, new Senko.Done<String>() {
                            @Override
                            public void done(String value, IOException error) {
                                senko.loadCatalog(null);
                                if (error != null) toastError(error);
                            }
                        });
                    }
                });
    }

    private void awgMenu() {
        final List<String> labels = new ArrayList<String>();
        labels.add(Tr.t("Check ping"));
        labels.add(Tr.t("Edit config"));
        labels.add(Tr.t("Remove"));
        Dialogs.menu(this, "AmneziaWG", labels, new Dialogs.Pick() {
            @Override
            public void picked(int index) {
                if (index == 0) pingAwg();
                else if (index == 1) editAwg();
                else removeAwg();
            }
        });
    }

    private void pingAwg() {
        senko.probeAwg(new Senko.Done<Integer>() {
            @Override
            public void done(Integer ms, IOException error) {
                if (error != null) toastError(error);
                else toastRaw(ServerSheet.latency(ms == null ? -1 : ms));
            }
        });
    }

    private void editAwg() {
        String current = "";
        try {
            java.io.FileInputStream in = new java.io.FileInputStream(senko.awgProfile());
            try {
                java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
                byte[] buffer = new byte[4096];
                int got;
                while ((got = in.read(buffer)) > 0) out.write(buffer, 0, got);
                current = new String(out.toByteArray(), "UTF-8");
            } finally {
                in.close();
            }
        } catch (IOException e) {
            toastError(e);
            return;
        }
        Dialogs.editor(this, "Edit config", current, "Save", new Dialogs.Text() {
            @Override
            public void entered(String text) {
                saveAwg(text);
            }
        });
    }

    private void saveAwg(String text) {
        senko.saveAwgConfig(text, false, new Senko.Done<String>() {
            @Override
            public void done(String value, IOException error) {
                if (error != null) toastError(error);
                else toast("Saved");
            }
        });
    }

    private void removeAwg() {
        Dialogs.confirm(this, "Remove AmneziaWG profile", "Remove the profile and its keys from this device?",
                "Remove", new Runnable() {
                    @Override
                    public void run() {
                        senko.removeAwgProfile(new Senko.Done<Void>() {
                            @Override
                            public void done(Void value, IOException error) {
                                if (error != null) toastError(error);
                                rebuild();
                            }
                        });
                    }
                });
    }


    @Override
    public Activity activity() {
        return this;
    }

    @Override
    public void pickFile() {
        startActivityForResult(ImportFlow.pickFileIntent(), ImportFlow.REQUEST_FILE);
    }

    @Override
    public void scanQr() {
        startActivityForResult(new Intent(this, QrScanActivity.class), ImportFlow.REQUEST_QR);
    }

    @Override
    public void say(String text) {
        toast(text);
    }

    @Override
    public void fail(String text) {
        showError(Tr.t(text));
    }

    @Override
    protected void onActivityResult(int request, int result, Intent data) {
        super.onActivityResult(request, result, data);
        ImportFlow.handleActivityResult(this, request, result, data);
    }

    @Override
    public void toastEnglish(String english) {
        toast(english);
    }

    @Override
    public void toastRaw(String text) {
        super.toastRaw(text);
    }

    @Override
    public void changed() {
        rebuild();
    }


    private final class Adapter extends BaseAdapter {
        @Override
        public int getCount() {
            return items.size();
        }

        @Override
        public Object getItem(int position) {
            return items.get(position);
        }

        @Override
        public long getItemId(int position) {
            return position;
        }

        @Override
        public int getViewTypeCount() {
            return 4;
        }

        @Override
        public int getItemViewType(int position) {
            return items.get(position).type;
        }

        @Override
        public View getView(int position, View convert, ViewGroup parent) {
            Item item = items.get(position);
            switch (item.type) {
                case ITEM_HEADER:
                    return headerView(item.section);
                case ITEM_AUTO:
                    return autoView();
                case ITEM_AWG:
                    return awgView();
                default:
                    return rowView(item.row);
            }
        }
    }

    private LinearLayout shell(boolean first) {
        LinearLayout line = new LinearLayout(this);
        line.setGravity(Gravity.CENTER_VERTICAL);
        line.setMinimumHeight(Ui.dp(54));
        line.setPadding(Ui.dp(14), Ui.dp(6), Ui.dp(10), Ui.dp(6));
        Ui.background(line, Ui.rowPress());
        return line;
    }

    private View headerView(final ServerList.Section section) {
        LinearLayout line = new LinearLayout(this);
        line.setGravity(Gravity.CENTER_VERTICAL);
        line.setPadding(Ui.dp(14), Ui.dp(14), Ui.dp(6), Ui.dp(6));
        boolean collapsed = Prefs.collapsed().contains(sectionKey(section)) && query.trim().isEmpty();
        View arrow = new View(this);
        Ui.background(arrow, Icons.shape(this, Icons.Shape.CHEVRON, Theme.color(Theme.INK_MUTED)));
        line.addView(arrow, new LinearLayout.LayoutParams(Ui.dp(14), Ui.dp(14)));
        // a chevron points right when the section is closed and down when it is open
        float angle = collapsed ? 0f : 90f;
        if (sectionKey(section).equals(toggledKey)) {
            toggledKey = "";
            ViewFx.rotation(arrow, collapsed ? 90f : 0f, angle, true);
        } else {
            ViewFx.rotation(arrow, angle, angle, false);
        }
        String code = Names.flagCode(section.flag);
        Drawable flag = code == null ? null : Icons.flag(this, code);
        if (flag != null) {
            ImageView image = new ImageView(this);
            image.setImageDrawable(flag);
            LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(Ui.dp(22), Ui.dp(16));
            params.leftMargin = Ui.dp(8);
            line.addView(image, params);
        }
        LinearLayout texts = new LinearLayout(this);
        texts.setOrientation(LinearLayout.VERTICAL);
        texts.setPadding(Ui.dp(8), 0, Ui.dp(8), 0);
        TextView title = Ui.text(this, section.manual() ? Tr.t("Manual") : section.title, 14,
                Theme.heading(), true);
        title.setSingleLine(true);
        title.setEllipsize(android.text.TextUtils.TruncateAt.END);
        texts.addView(title);
        if (!section.meta.isEmpty()) {
            TextView meta = Ui.text(this, section.meta, 11, Theme.color(Theme.INK_MUTED), false);
            meta.setSingleLine(true);
            texts.addView(meta);
        }
        line.addView(texts, Ui.weighted(1));
        final int muted = Theme.color(Theme.INK_MUTED);
        if (!section.manual()) {
            line.addView(headerButton(Icons.shape(this, Icons.Shape.REFRESH, muted), "Refresh", new View.OnClickListener() {
                @Override
                public void onClick(View view) {
                    refresh(section.subIdx);
                }
            }));
        }
        line.addView(headerButton(Icons.shape(this, Icons.Shape.GAUGE, muted), "Check speed", new View.OnClickListener() {
            @Override
            public void onClick(View view) {
                senko.pingGroup(section.manual() ? -1 : section.subIdx, null);
            }
        }));
        line.addView(headerButton(Icons.shape(this, Icons.Shape.DOTS, muted), "More options", new View.OnClickListener() {
            @Override
            public void onClick(View view) {
                sectionMenu(section);
            }
        }));
        return line;
    }

    private View headerButton(Drawable icon, String label, View.OnClickListener click) {
        View button = new View(this);
        Ui.background(button, Ui.layers(Ui.rowPress(), Icons.inBox(icon, 18)));
        button.setContentDescription(Tr.t(label));
        button.setOnClickListener(click);
        Ui.pressEffect(button);
        button.setLayoutParams(new LinearLayout.LayoutParams(Ui.dp(48), Ui.dp(48)));
        return button;
    }

    private View badge(Drawable picture) {
        ImageView image = new ImageView(this);
        image.setScaleType(ImageView.ScaleType.CENTER_INSIDE);
        image.setImageDrawable(picture);
        Ui.background(image, Ui.plate(8, Theme.color(Theme.WELL)));
        image.setPadding(Ui.dp(4), Ui.dp(4), Ui.dp(4), Ui.dp(4));
        image.setLayoutParams(new LinearLayout.LayoutParams(Ui.dp(40), Ui.dp(32)));
        return image;
    }

    private View radio(boolean on) {
        View view = new View(this);
        int accent = Theme.color(Theme.ACCENT);
        if (on) Ui.background(view, Icons.shape(this, Icons.Shape.CHECK, accent));
        view.setLayoutParams(new LinearLayout.LayoutParams(Ui.dp(22), Ui.dp(22)));
        return view;
    }

    private View texts(String title, String caption) {
        LinearLayout texts = new LinearLayout(this);
        texts.setOrientation(LinearLayout.VERTICAL);
        texts.setPadding(Ui.dp(12), 0, Ui.dp(8), 0);
        TextView name = Ui.text(this, title, 16, Theme.ink(), false);
        // a node name is read whole: it wraps to a second line and is cut only after that
        name.setSingleLine(false);
        name.setMaxLines(2);
        name.setEllipsize(android.text.TextUtils.TruncateAt.END);
        texts.addView(name);
        if (caption != null && !caption.isEmpty()) {
            TextView sub = Ui.text(this, caption, 12, Theme.color(Theme.INK_MUTED), false);
            sub.setSingleLine(true);
            sub.setEllipsize(android.text.TextUtils.TruncateAt.END);
            texts.addView(sub);
        }
        texts.setLayoutParams(Ui.weighted(1));
        return texts;
    }

    private View autoView() {
        LinearLayout line = shell(true);
        line.addView(badge(Icons.glyph(this, "bolt", Theme.color(Theme.ACCENT))));
        line.addView(texts(Tr.t("Auto"), Tr.t("Connects to the fastest server")));
        line.addView(radio(Prefs.autoServer() && !senko.awgSelected()));
        return line;
    }

    private View awgView() {
        LinearLayout line = shell(false);
        line.addView(badge(Icons.glyph(this, "shield", Theme.color(Theme.ACCENT))));
        line.addView(texts("AmneziaWG", "AMNEZIAWG  ·  UDP"));
        line.addView(radio(senko.awgSelected()));
        return line;
    }

    private View rowView(ServerList.Row row) {
        LinearLayout line = shell(false);
        Model.Server server = row.server;
        String code = Names.flagCode(server.remark);
        Drawable flag = code == null ? null : Icons.flag(this, code);
        line.addView(badge(flag != null ? flag : Icons.placeholder(this)));
        // "VLESS / TCP / REALITY", the way the profile is described in the list of a client app
        String label = Names.protocolLabel(server).replace("/", " / ").toUpperCase(java.util.Locale.US);
        View texts = texts(row.name.isEmpty() ? Names.title(server) : row.name, label);
        ViewFx.alpha(texts, server.supported ? 1f : 0.5f);
        line.addView(texts);
        int ms = best(row.members);
        if (ms == -3) {
            ProgressBar spinner = new ProgressBar(this, null, android.R.attr.progressBarStyleSmall);
            line.addView(spinner, new LinearLayout.LayoutParams(Ui.dp(18), Ui.dp(18)));
        } else if (ms >= 0 || ms == -1) {
            // the number carries its unit, which the language table turns into "мс" or "毫秒"
            String shown = ms >= 0 ? Tr.t(ms + " ms").replace(" ", "") : "×";
            TextView ping = Ui.text(this, shown, 14, pingColor(ms), false);
            ping.setPadding(Ui.dp(8), 0, Ui.dp(6), 0);
            line.addView(ping);
        }
        boolean chosen = !Prefs.autoServer() && !senko.awgSelected() && row.members.contains(senko.picked());
        View arrow = new View(this);
        Ui.background(arrow, Icons.shape(this, Icons.Shape.CHEVRON, Theme.color(Theme.INK_MUTED)));
        line.addView(arrow, new LinearLayout.LayoutParams(Ui.dp(14), Ui.dp(14)));
        // the chosen server is the one whose row is tinted, there is no radio to read beside the ping
        if (chosen) Ui.background(line, Ui.layers(
                Ui.solid(Ui.alpha(Theme.color(Theme.ACCENT), 0.20f), Theme.current().radius), Ui.rowPress()));
        return line;
    }

    private int best(List<Integer> members) {
        int best = -2;
        boolean running = false;
        for (int index : members) {
            int ms = pings.of(index);
            if (ms == -3) running = true;
            else if (ms >= 0 && (best < 0 || ms < best)) best = ms;
            else if (ms == -1 && best == -2) best = -1;
        }
        return running ? -3 : best;
    }

    private int pingColor(int ms) {
        if (ms < 0) return Theme.stateColor("error");
        if (ms < 150) return Theme.stateColor("connected");
        if (ms < 400) return Theme.color(Theme.ACCENT);
        return Theme.stateColor("error");
    }
}
