package com.senko.droid;

import android.app.Activity;
import android.content.Context;
import android.graphics.drawable.Drawable;
import android.os.Build;
import android.os.Bundle;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.inputmethod.InputMethodManager;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

/**
 * every screen but the home screen: a bar with a back button, a title and
 * actions, over the themed page. a screen fills the body and listens to the
 * engine while it is in front
 */
abstract class BaseActivity extends Activity implements Senko.Listener, Theme.Listener {
    protected Senko senko;
    private int themeVersion;
    private LinearLayout page;
    private FrameLayout body;
    private TextView titleView;
    private LinearLayout rightBar;
    private TextView backLabel;

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        senko = Senko.get();
        themeVersion = Theme.version();
        buildChrome();
    }

    private void buildChrome() {
        page = new LinearLayout(this);
        page.setOrientation(LinearLayout.VERTICAL);
        // the screen itself takes the first focus. a search field that was the first focusable view got it
        // instead, and its keyboard came up as soon as the screen opened
        page.setFocusable(true);
        page.setFocusableInTouchMode(true);
        getWindow().setSoftInputMode(android.view.WindowManager.LayoutParams.SOFT_INPUT_STATE_HIDDEN
                | android.view.WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE);
        Ui.background(page, Ui.page());

        LinearLayout bar = new LinearLayout(this);
        bar.setOrientation(LinearLayout.HORIZONTAL);
        bar.setGravity(Gravity.CENTER_VERTICAL);
        int hi = Theme.color(Theme.CHROME_HI);
        Ui.background(bar, Ui.gradient(hi, Theme.color(Theme.CHROME_LO), 0));
        if (Theme.current().flags.contains("holo")) {
            // the 2 dp blue rule under the action bar of android 4, the bar is 52 dp high
            android.graphics.drawable.LayerDrawable holo = new android.graphics.drawable.LayerDrawable(
                    new Drawable[] { Ui.gradient(hi, Theme.color(Theme.CHROME_LO), 0), Ui.solid(Theme.color(Theme.ACCENT), 0) });
            holo.setLayerInset(1, 0, Ui.dp(50), 0, 0);
            Ui.background(bar, holo);
        }
        int ink = Ui.onColor(Ui.shade(hi, -0.05f));

        LinearLayout left = new LinearLayout(this);
        left.setGravity(Gravity.CENTER_VERTICAL);
        left.setMinimumHeight(Ui.dp(48));
        Ui.background(left, Ui.rowPress());
        left.setPadding(Ui.dp(8), 0, Ui.dp(12), 0);
        View arrow = new View(this);
        Ui.background(arrow, Icons.shape(this, Icons.Shape.BACK, ink));
        left.addView(arrow, new LinearLayout.LayoutParams(Ui.dp(22), Ui.dp(22)));
        backLabel = Ui.text(this, "", 16, ink, false);
        left.addView(backLabel);
        left.setContentDescription(Tr.t("Back"));
        left.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View view) {
                onBackPressed();
            }
        });
        bar.addView(left);

        titleView = Ui.text(this, "", 18, ink, true);
        titleView.setSingleLine(true);
        titleView.setEllipsize(android.text.TextUtils.TruncateAt.END);
        bar.addView(titleView, Ui.weighted(1));

        rightBar = new LinearLayout(this);
        rightBar.setGravity(Gravity.CENTER_VERTICAL);
        bar.addView(rightBar);
        page.addView(bar, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, Ui.dp(52)));

        body = new FrameLayout(this);
        page.addView(body, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1));
        setContentView(page);
        if (Build.VERSION.SDK_INT >= 21)
            getWindow().setStatusBarColor(Ui.shade(Theme.color(Theme.CHROME_LO), -0.25f));
    }

    /** the title of the screen, an english sentence the language table knows */
    protected void setScreenTitle(String english) {
        titleView.setText(Tr.t(english));
        setTitle(Tr.t(english));
    }

    protected void setBody(View view) {
        body.removeAllViews();
        Wide.Column column = new Wide.Column(this, Wide.dpad(this));
        column.addView(view, new ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT));
        body.addView(column, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT));
    }

    protected FrameLayout body() {
        return body;
    }

    /** a text button at the right of the bar */
    protected TextView addBarText(String english, View.OnClickListener click) {
        int ink = Ui.onColor(Ui.shade(Theme.color(Theme.CHROME_HI), -0.05f));
        TextView button = Ui.text(this, Tr.t(english), 16, ink, true);
        button.setGravity(Gravity.CENTER);
        button.setPadding(Ui.dp(14), 0, Ui.dp(14), 0);
        Ui.background(button, Ui.rowPress());
        button.setOnClickListener(click);
        rightBar.addView(button, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, Ui.dp(52)));
        return button;
    }

    /** an icon button at the right of the bar */
    protected View addBarIcon(Drawable icon, String label, View.OnClickListener click) {
        View button = new View(this);
        Ui.background(button, Ui.layers(Ui.rowPress(), Icons.inBox(icon, 22)));
        button.setContentDescription(Tr.t(label));
        button.setOnClickListener(click);
        Ui.pressEffect(button);
        rightBar.addView(button, new LinearLayout.LayoutParams(Ui.dp(48), Ui.dp(52)));
        return button;
    }

    protected void clearBarActions() {
        rightBar.removeAllViews();
    }

    protected int barInk() {
        return Ui.onColor(Ui.shade(Theme.color(Theme.CHROME_HI), -0.05f));
    }

    @Override
    protected void onStart() {
        super.onStart();
        senko.screenStarted();
        senko.addListener(this);
        Theme.addListener(this);
        // the theme changed on a screen above this one, whose views still hold the old colours
        if (themeVersion != Theme.version()) recreateAfterStart();
    }

    /**
     * recreate() inside onStart is queued against a screen that is still starting, and a back press
     * that arrives meanwhile goes to the instance that is about to die. it is posted instead, once
     * the start has finished and only if the screen is still there
     */
    private void recreateAfterStart() {
        getWindow().getDecorView().post(new Runnable() {
            @Override
            public void run() {
                if (!isFinishing() && themeVersion != Theme.version()) Compat.recreate(BaseActivity.this);
            }
        });
    }

    @Override
    protected void onStop() {
        Theme.removeListener(this);
        senko.removeListener(this);
        senko.screenStopped();
        super.onStop();
    }

    @Override
    public void onChange(int what) {
        // a screen reads what it shows from Senko when something changed
    }

    @Override
    public void onThemeChanged() {
        Compat.recreate(this);
    }

    protected void toast(String english) {
        Toast.makeText(this, Tr.t(english), Toast.LENGTH_SHORT).show();
    }

    protected void toastRaw(String text) {
        Toast.makeText(this, text, Toast.LENGTH_SHORT).show();
    }

    protected void toastError(java.io.IOException error) {
        showError(Senko.describe(error));
    }

    protected void showError(String text) {
        if (isFinishing() || (Build.VERSION.SDK_INT >= 17 && isDestroyed())) {
            android.util.Log.e("senko", text);
            return;
        }
        Dialogs.error(this, Tr.t("Error"), text);
    }

    protected void hideKeyboard() {
        View focus = getCurrentFocus();
        if (focus == null) return;
        InputMethodManager manager = (InputMethodManager) getSystemService(Context.INPUT_METHOD_SERVICE);
        manager.hideSoftInputFromWindow(focus.getWindowToken(), 0);
    }
}
