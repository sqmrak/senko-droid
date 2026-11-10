package com.senko.droid;

import android.content.DialogInterface;
import android.os.Bundle;
import android.text.InputType;
import android.view.View;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.SeekBar;
import android.widget.TextView;

import java.util.ArrayList;
import java.util.List;

/**
 * edits one custom theme: its name, style, corner radius and the sixteen
 * colours of the light and the dark palette. a colour is three sliders and a
 * hex field, which reaches every value on a screen of any size
 */
public final class ThemeEditActivity extends BaseActivity {
    static final String EXTRA_ID = "id";

    private CustomThemes.Draft draft;
    private boolean editingDark;
    private Rows rows;

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        setScreenTitle("Edit theme");
        draft = CustomThemes.find(getIntent().getStringExtra(EXTRA_ID));
        if (draft == null) {
            finish();
            return;
        }
        rows = new Rows(this);
        setBody(rows.view());
        addBarText("Save", new View.OnClickListener() {
            @Override
            public void onClick(View view) {
                save();
            }
        });
        render();
    }

    /** the palette being edited changes with the editor, so a theme switch must not rebuild it */
    @Override
    public void onThemeChanged() {}

    private int[] palette() {
        return editingDark && draft.dark != null ? draft.dark : draft.light;
    }

    private void render() {
        rows.clear();
        rows.section("Theme");
        rows.action(null, "Name", draft.name, new View.OnClickListener() {
            @Override
            public void onClick(View view) {
                Dialogs.prompt(ThemeEditActivity.this, "Name", null, draft.name, Dialogs.plainInput(), "Save",
                        new Dialogs.Text() {
                            @Override
                            public void entered(String text) {
                                draft.name = text.length() > 40 ? text.substring(0, 40) : text;
                                render();
                            }
                        });
            }
        });
        String[] styles = { Tr.t("Classic"), Tr.t("Flat"), Tr.t("Glass") };
        rows.action(null, "Style", styles[draft.style], new View.OnClickListener() {
            @Override
            public void onClick(View view) {
                List<String> labels = new ArrayList<String>();
                labels.add(Tr.t("Classic"));
                labels.add(Tr.t("Flat"));
                labels.add(Tr.t("Glass"));
                Dialogs.menu(ThemeEditActivity.this, "Style", labels, new Dialogs.Pick() {
                    @Override
                    public void picked(int index) {
                        draft.style = index;
                        render();
                    }
                });
            }
        });
        rows.action(null, "Corner radius", String.valueOf(Math.round(draft.radius)), new View.OnClickListener() {
            @Override
            public void onClick(View view) {
                radiusDialog();
            }
        });
        rows.toggle(null, "Has a dark palette", draft.dark != null, true, new Toggle.Listener() {
            @Override
            public void changed(Toggle toggle, boolean on) {
                draft.dark = on ? draft.light.clone() : null;
                if (!on) editingDark = false;
                render();
            }
        });

        if (draft.dark != null) {
            rows.section("Palette");
            rows.custom(paletteTabs());
        } else {
            rows.section("Palette");
        }
        int[] colors = palette();
        for (int i = 0; i < Theme.SLOTS; ++i) {
            final int slot = i;
            Rows.Line line = rows.action(null, CustomThemes.TITLES[i], CustomThemes.hex(colors[i]), false,
                    new View.OnClickListener() {
                        @Override
                        public void onClick(View view) {
                            colorDialog(slot);
                        }
                    });
            View swatch = new View(this);
            android.graphics.drawable.GradientDrawable fill = Ui.solid(colors[i], 6);
            fill.setStroke(Ui.dp(1), Ui.alpha(Theme.ink(), 0.3f));
            Ui.background(swatch, fill);
            LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(Ui.dp(28), Ui.dp(28));
            params.leftMargin = Ui.dp(10);
            line.view.addView(swatch, params);
        }
        rows.footer(Tr.t("Colours show on this screen only after saving."));
    }

    private View paletteTabs() {
        LinearLayout box = new LinearLayout(this);
        box.setPadding(Ui.dp(12), Ui.dp(10), Ui.dp(12), Ui.dp(10));
        String[] names = { Tr.t("Light"), Tr.t("Dark") };
        for (int i = 0; i < 2; ++i) {
            final boolean dark = i == 1;
            boolean on = editingDark == dark;
            int accent = Theme.color(Theme.ACCENT);
            TextView tab = Ui.text(this, names[i], 15, on ? Ui.onColor(accent) : Theme.ink(), on);
            tab.setGravity(android.view.Gravity.CENTER);
            tab.setPadding(0, Ui.dp(8), 0, Ui.dp(8));
            Ui.background(tab, on ? Ui.solid(accent, 8) : Ui.plate(8, Theme.color(Theme.WELL)));
            tab.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View view) {
                    editingDark = dark;
                    render();
                }
            });
            LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(0, -2, 1);
            params.setMargins(Ui.dp(3), 0, Ui.dp(3), 0);
            box.addView(tab, params);
        }
        return box;
    }

    private void radiusDialog() {
        final SeekBar bar = new SeekBar(this);
        bar.setMax(32);
        bar.setProgress(Math.round(draft.radius));
        LinearLayout box = new LinearLayout(this);
        box.setPadding(Ui.dp(20), Ui.dp(16), Ui.dp(20), Ui.dp(4));
        box.addView(bar, new LinearLayout.LayoutParams(-1, -2));
        new SenkoDialog.Builder(this)
                .setTitle(Tr.t("Corner radius"))
                .setView(box)
                .setNegativeButton(Tr.t("Cancel"), null)
                .setPositiveButton(Tr.t("OK"), new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface dialog, int which) {
                        draft.radius = bar.getProgress();
                        render();
                    }
                })
                .show();
    }

    private void colorDialog(final int slot) {
        final int[] colors = palette();
        final int[] rgb = { (colors[slot] >> 16) & 0xff, (colors[slot] >> 8) & 0xff, colors[slot] & 0xff };
        final EditText hex = new EditText(this);
        hex.setSingleLine(true);
        hex.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_CAP_CHARACTERS
                | InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS);
        hex.setText(CustomThemes.hex(colors[slot]));
        final View preview = new View(this);
        final SeekBar[] bars = new SeekBar[3];
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(Ui.dp(20), Ui.dp(12), Ui.dp(20), 0);
        box.addView(preview, new LinearLayout.LayoutParams(-1, Ui.dp(40)));
        final boolean[] syncing = { false };
        final Runnable paint = new Runnable() {
            @Override
            public void run() {
                int color = 0xff000000 | (rgb[0] << 16) | (rgb[1] << 8) | rgb[2];
                Ui.background(preview, Ui.solid(color, 6));
            }
        };
        paint.run();
        String[] names = { "R", "G", "B" };
        for (int i = 0; i < 3; ++i) {
            final int channel = i;
            LinearLayout line = new LinearLayout(this);
            line.setGravity(android.view.Gravity.CENTER_VERTICAL);
            TextView letter = new TextView(this);
            letter.setText(names[channel]);
            line.addView(letter, new LinearLayout.LayoutParams(Ui.dp(20), -2));
            bars[i] = new SeekBar(this);
            bars[i].setMax(255);
            bars[i].setProgress(rgb[i]);
            bars[i].setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
                @Override
                public void onProgressChanged(SeekBar bar, int value, boolean fromUser) {
                    if (!fromUser) return;
                    rgb[channel] = value;
                    syncing[0] = true;
                    hex.setText(CustomThemes.hex((rgb[0] << 16) | (rgb[1] << 8) | rgb[2]));
                    syncing[0] = false;
                    paint.run();
                }

                @Override
                public void onStartTrackingTouch(SeekBar bar) {}

                @Override
                public void onStopTrackingTouch(SeekBar bar) {}
            });
            line.addView(bars[i], new LinearLayout.LayoutParams(0, -2, 1));
            box.addView(line);
        }
        box.addView(hex);
        hex.addTextChangedListener(new android.text.TextWatcher() {
            @Override
            public void beforeTextChanged(CharSequence s, int start, int count, int after) {}

            @Override
            public void onTextChanged(CharSequence s, int start, int before, int count) {}

            @Override
            public void afterTextChanged(android.text.Editable s) {
                if (syncing[0]) return;
                Integer color = CustomThemes.parseHex(s.toString());
                if (color == null) return;
                rgb[0] = (color >> 16) & 0xff;
                rgb[1] = (color >> 8) & 0xff;
                rgb[2] = color & 0xff;
                for (int i = 0; i < 3; ++i) bars[i].setProgress(rgb[i]);
                paint.run();
            }
        });
        new SenkoDialog.Builder(this)
                .setTitle(Tr.t(CustomThemes.TITLES[slot]))
                .setMessage(Tr.t(CustomThemes.HINTS[slot]))
                .setView(box)
                .setNegativeButton(Tr.t("Cancel"), null)
                .setPositiveButton(Tr.t("OK"), new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface dialog, int which) {
                        palette()[slot] = 0xff000000 | (rgb[0] << 16) | (rgb[1] << 8) | rgb[2];
                        render();
                    }
                })
                .show();
    }

    private void save() {
        if (draft.name.trim().isEmpty()) {
            toast("Name is required");
            return;
        }
        String id = CustomThemes.save(draft);
        if (id == null) {
            toast("The custom theme limit is reached.");
            return;
        }
        // editing the active theme repaints every screen with the new colours
        if (id.equals(Theme.currentId())) Theme.choose(id, Prefs.light(), Prefs.ios6Accent());
        finish();
    }
}
