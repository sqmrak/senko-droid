package com.senko.droid;

import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.view.Gravity;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;

/**
 * the theme picker. the top screen holds the style groups, the appearance and
 * the ios 6 accent; a group screen lists its themes, and the custom group also
 * makes, imports, exports and deletes them
 */
public final class ThemesActivity extends BaseActivity {
    static final String EXTRA_GROUP = "group";

    private static final int REQUEST_IMPORT = 61;
    private static final int REQUEST_EXPORT = 62;

    private String group;
    private Rows rows;
    private String exporting;

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        group = getIntent().getStringExtra(EXTRA_GROUP);
        setScreenTitle(group == null ? "Themes" : Theme.groupTitle(group));
        rows = new Rows(this);
        setBody(rows.view());
        if ("custom".equals(group)) {
            addBarIcon(Icons.shape(this, Icons.Shape.PLUS, barInk()), "Add", new View.OnClickListener() {
                @Override
                public void onClick(View view) {
                    newTheme();
                }
            });
        }
        render();
    }

    @Override
    protected void onResume() {
        super.onResume();
        render();
    }

    @Override
    public void onThemeChanged() {
        // the picker keeps its scroll position while the colours change under it
        Compat.recreate(this);
    }

    private void render() {
        rows.clear();
        if (group == null) renderTop();
        else renderGroup();
    }

    private void renderTop() {
        rows.section("Style");
        for (final String id : Theme.groups()) {
            rows.action("palette", Theme.groupTitle(id), "", new View.OnClickListener() {
                @Override
                public void onClick(View view) {
                    Intent intent = new Intent(ThemesActivity.this, ThemesActivity.class);
                    intent.putExtra(EXTRA_GROUP, id);
                    startActivity(intent);
                }
            });
        }
        Theme.Def def = Theme.current();
        rows.footer(def.name + ": " + Tr.t(def.blurb));

        final boolean fixed = def.darkOnly() || def.lightOnly();
        rows.section("Appearance");
        rows.custom(segmented(def));
        rows.footer(Tr.t("Dark / Light applies to the selected style. Choice is stored on device."));

        if (def.id.equals("senko-ios6")) {
            rows.section("Accent");
            rows.custom(swatches());
        }
        if (fixed) rows.footer(Tr.t(def.darkOnly() ? "This style has a dark palette only." : "This style has a light palette only."));
    }

    private View segmented(Theme.Def def) {
        LinearLayout box = new LinearLayout(this);
        box.setPadding(Ui.dp(12), Ui.dp(10), Ui.dp(12), Ui.dp(10));
        String[] names = { Tr.t("Dark"), Tr.t("Light") };
        for (int i = 0; i < 2; ++i) {
            final boolean light = i == 1;
            boolean allowed = light ? !def.darkOnly() : def.allowsDark() && !def.lightOnly();
            boolean on = Theme.isLight() == light;
            int accent = Theme.color(Theme.ACCENT);
            TextView tab = Ui.text(this, names[i], 15, on ? Ui.onColor(accent) : Theme.ink(), on);
            tab.setGravity(Gravity.CENTER);
            tab.setPadding(0, Ui.dp(8), 0, Ui.dp(8));
            ViewFx.alpha(tab, allowed ? 1f : 0.4f);
            Ui.background(tab, on ? Ui.solid(accent, 8) : Ui.plate(8, Theme.color(Theme.WELL)));
            if (allowed) {
                tab.setOnClickListener(new View.OnClickListener() {
                    @Override
                    public void onClick(View view) {
                        Theme.choose(Theme.currentId(), light, Prefs.ios6Accent());
                    }
                });
            }
            LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(0, -2, 1);
            params.setMargins(Ui.dp(3), 0, Ui.dp(3), 0);
            box.addView(tab, params);
        }
        return box;
    }

    /** a color dot as wide as its share of the row allows and no wider than 40 dp, always round */
    private static final class RoundSwatch extends View {
        RoundSwatch(android.content.Context context) {
            super(context);
        }

        @Override
        protected void onMeasure(int widthSpec, int heightSpec) {
            int size = Math.min(MeasureSpec.getSize(widthSpec), Ui.dp(40));
            setMeasuredDimension(size, size);
        }
    }

    private View swatches() {
        LinearLayout box = new LinearLayout(this);
        box.setPadding(Ui.dp(10), Ui.dp(12), Ui.dp(10), Ui.dp(12));
        box.setGravity(Gravity.CENTER);
        for (int i = 0; i < Theme.ACCENT_COUNT; ++i) {
            final int index = i;
            View swatch = new RoundSwatch(this);
            boolean on = Prefs.ios6Accent() == i;
            android.graphics.drawable.GradientDrawable fill = Ui.solid(Theme.accentSwatch(i), 20);
            fill.setStroke(Ui.dp(on ? 3 : 1), on ? Theme.ink() : Ui.alpha(Theme.ink(), 0.25f));
            Ui.background(swatch, fill);
            swatch.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View view) {
                    Theme.choose(Theme.currentId(), Theme.isLight(), index);
                }
            });
            // equal shares of the row, so six of them fit a 320 dp screen instead of running off both edges
            LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(0, -2, 1f);
            params.setMargins(Ui.dp(4), 0, Ui.dp(4), 0);
            box.addView(swatch, params);
        }
        return box;
    }

    private void renderGroup() {
        rows.section(null);
        List<Theme.Def> defs = Theme.inGroup(group);
        if (defs.isEmpty()) {
            rows.footer(Tr.t("No themes yet. Use + to make one."));
            return;
        }
        for (final Theme.Def def : defs) {
            boolean on = def.id.equals(Theme.currentId());
            final boolean custom = CustomThemes.isCustom(def.id);
            Rows.Line line = rows.action(null, def.name, on ? "✓" : "", false, new View.OnClickListener() {
                @Override
                public void onClick(View view) {
                    if (!def.id.equals(Theme.currentId())) Theme.choose(def.id, Prefs.light(), Prefs.ios6Accent());
                }
            });
            if (!def.blurb.isEmpty()) {
                TextView blurb = Ui.text(this, Tr.t(def.blurb), 12, Theme.color(Theme.INK_MUTED), false);
                LinearLayout column = new LinearLayout(this);
                column.setOrientation(LinearLayout.VERTICAL);
                line.view.removeView(line.title);
                // the title kept the weight of a horizontal row, which is zero width in a column
                column.addView(line.title, new LinearLayout.LayoutParams(-1, -2));
                column.addView(blurb);
                line.view.addView(column, 0, Ui.weighted(1));
            }
            if (custom) {
                line.view.setOnLongClickListener(new View.OnLongClickListener() {
                    @Override
                    public boolean onLongClick(View view) {
                        customMenu(def);
                        return true;
                    }
                });
            }
        }
        if ("custom".equals(group)) rows.footer(Tr.t("Hold a custom theme to edit, export or delete it."));
    }


    private void newTheme() {
        List<String> labels = new ArrayList<String>();
        labels.add(Tr.t("Copy current theme"));
        labels.add(Tr.t("Import from file"));
        Dialogs.menu(this, "New theme", labels, new Dialogs.Pick() {
            @Override
            public void picked(int index) {
                if (index == 0) {
                    CustomThemes.Draft draft = CustomThemes.draftFrom(Theme.currentId());
                    String id = CustomThemes.save(draft);
                    if (id == null) {
                        Dialogs.error(ThemesActivity.this, Tr.t("Could not create theme"),
                                Tr.t("The custom theme limit is reached."));
                        return;
                    }
                    edit(id);
                } else {
                    Intent intent = new Intent(Intent.ACTION_GET_CONTENT);
                    intent.setType("*/*");
                    intent.addCategory(Intent.CATEGORY_OPENABLE);
                    startActivityForResult(Intent.createChooser(intent, Tr.t("Import theme")), REQUEST_IMPORT);
                }
            }
        });
    }

    private void edit(String id) {
        Intent intent = new Intent(this, ThemeEditActivity.class);
        intent.putExtra(ThemeEditActivity.EXTRA_ID, id);
        startActivity(intent);
    }

    private void customMenu(final Theme.Def def) {
        List<String> labels = new ArrayList<String>();
        labels.add(Tr.t("Edit"));
        labels.add(Tr.t("Export"));
        labels.add(Tr.t("Delete"));
        Dialogs.menu(this, def.name, labels, new Dialogs.Pick() {
            @Override
            public void picked(int index) {
                if (index == 0) edit(def.id);
                else if (index == 1) export(def.id);
                else delete(def.id);
            }
        });
    }

    private void delete(final String id) {
        Dialogs.confirm(this, "Delete theme", "Delete this custom theme?", "Delete", new Runnable() {
            @Override
            public void run() {
                boolean wasCurrent = id.equals(Theme.currentId());
                if (!CustomThemes.delete(id)) return;
                if (wasCurrent) Theme.choose(Theme.DEFAULT_ID, Prefs.light(), Prefs.ios6Accent());
                else render();
            }
        });
    }

    private void export(String id) {
        exporting = CustomThemes.export(id);
        if (exporting == null) return;
        if (android.os.Build.VERSION.SDK_INT >= 19) {
            Intent intent = new Intent(Intent.ACTION_CREATE_DOCUMENT);
            intent.addCategory(Intent.CATEGORY_OPENABLE);
            intent.setType("application/octet-stream");
            Theme.Def def = Theme.find(id);
            intent.putExtra(Intent.EXTRA_TITLE, (def == null ? "theme" : def.name.replace(' ', '-')) + ".senkotheme");
            startActivityForResult(intent, REQUEST_EXPORT);
            return;
        }
        // before api 19 there is no document dialog, so the theme goes out as text for any app to take
        Intent send = new Intent(Intent.ACTION_SEND);
        send.setType("text/plain");
        send.putExtra(Intent.EXTRA_TEXT, exporting);
        startActivity(Intent.createChooser(send, Tr.t("Export")));
    }

    @Override
    protected void onActivityResult(int request, int result, Intent data) {
        super.onActivityResult(request, result, data);
        if (result != RESULT_OK || data == null || data.getData() == null) return;
        Uri uri = data.getData();
        try {
            if (request == REQUEST_EXPORT && exporting != null) {
                java.io.OutputStream out = getContentResolver().openOutputStream(uri);
                if (out == null) throw new IOException("no stream");
                try {
                    out.write(exporting.getBytes("UTF-8"));
                } finally {
                    out.close();
                }
                toast("Theme exported");
            } else if (request == REQUEST_IMPORT) {
                InputStream in = getContentResolver().openInputStream(uri);
                if (in == null) throw new IOException("no stream");
                ByteArrayOutputStream bytes = new ByteArrayOutputStream();
                byte[] buffer = new byte[4096];
                int got;
                try {
                    while ((got = in.read(buffer)) > 0 && bytes.size() <= 65536) bytes.write(buffer, 0, got);
                } finally {
                    in.close();
                }
                CustomThemes.importText(new String(bytes.toByteArray(), "UTF-8"));
                toast("Theme imported");
                render();
            }
        } catch (IOException e) {
            Dialogs.error(this, Tr.t("Import failed"), Tr.t(e.getMessage() == null ? "Unknown error" : e.getMessage()));
        }
    }
}
