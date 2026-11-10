package com.senko.droid;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import android.app.Activity;
import android.content.Intent;
import android.view.View;
import android.view.ViewGroup;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.android.controller.ActivityController;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.ConscryptMode;

import java.lang.reflect.Field;
import java.util.List;

/**
 * builds every screen on a simulated framework with a catalog in it, under
 * every theme. it finds crashes at construction, missing resources and null
 * drawables. it says nothing about how a screen looks or behaves on a device
 */
@RunWith(RobolectricTestRunner.class)
// the conscrypt build robolectric ships has no native library for this aarch64 host, and no screen uses tls
@ConscryptMode(ConscryptMode.Mode.OFF)
@Config(sdk = {19, 28})
public class ScreensSmokeTest {
    private static final Class<?>[] SCREENS = {
        HomeActivity.class, ServerListActivity.class, SettingsActivity.class, StatsActivity.class,
        ThemesActivity.class, LogsActivity.class, RoutingActivity.class, RulesActivity.class,
        AboutActivity.class, DevMenuActivity.class,
    };

    @Before
    public void fillCatalog() throws Exception {
        Model.Catalog catalog = new Model.Catalog();
        Model.Sub sub = new Model.Sub();
        sub.index = 0;
        sub.name = "Panel_One";
        sub.url = "https://example.com/sub";
        sub.total = 100L << 30;
        sub.download = 40L << 30;
        sub.expire = System.currentTimeMillis() / 1000 + 86400 * 10;
        catalog.subs.add(sub);
        for (int i = 0; i < 6; ++i) {
            Model.Server server = new Model.Server();
            server.index = i;
            server.group = i < 4 ? 0 : -1;
            server.proto = i % 2 == 0 ? "vless" : "trojan";
            server.net = "tcp";
            server.security = "reality";
            server.supported = i != 3;
            server.host = "h" + i + ".example.com";
            server.port = 443;
            server.remark = "🇩🇪 Berlin " + (i % 3);
            server.identity = "id" + i;
            server.selected = i == 1;
            catalog.servers.add(server);
        }
        catalog.order = new int[] { -1, 0 };
        set("catalog", catalog);
        set("catalogLoaded", Boolean.TRUE);
        set("picked", 1);
    }

    private static void set(String name, Object value) throws Exception {
        Field field = Senko.class.getDeclaredField(name);
        field.setAccessible(true);
        field.set(Senko.get(), value);
    }

    private static int count(View view) {
        int total = 1;
        if (view instanceof ViewGroup) {
            ViewGroup group = (ViewGroup) view;
            for (int i = 0; i < group.getChildCount(); ++i) total += count(group.getChildAt(i));
        }
        return total;
    }

    @Test
    public void switchReportsItsStateAndHasAnAccessibleTouchTarget() {
        ActivityController<Activity> controller = Robolectric.buildActivity(Activity.class).create().start().resume().visible();
        Toggle toggle = new Toggle(controller.get());
        controller.get().setContentView(toggle);
        toggle.setContentDescription("Automatic reconnect");
        toggle.setChecked(true);
        toggle.measure(View.MeasureSpec.makeMeasureSpec(1000, View.MeasureSpec.AT_MOST),
                View.MeasureSpec.makeMeasureSpec(1000, View.MeasureSpec.AT_MOST));
        assertTrue(toggle.getMeasuredHeight() >= Ui.dp(48));
        android.view.accessibility.AccessibilityNodeInfo info =
                android.view.accessibility.AccessibilityNodeInfo.obtain();
        toggle.onInitializeAccessibilityNodeInfo(info);
        assertTrue(info.isCheckable());
        assertTrue(info.isChecked());
        assertTrue(info.getClassName().toString().equals("android.widget.Switch"));
        info.recycle();
        controller.pause().stop().destroy();
    }

    @Test
    public void importFailuresRemainVisibleInADialog() {
        ActivityController<ServerListActivity> controller = Robolectric.buildActivity(ServerListActivity.class);
        ServerListActivity screen = controller.create().start().resume().visible().get();
        screen.fail("The clipboard is empty.");
        android.app.Dialog dialog = org.robolectric.shadows.ShadowDialog.getLatestDialog();
        assertTrue(dialog instanceof SenkoDialog);
        assertTrue(dialog.isShowing());
        boolean hasMessage = false;
        java.util.ArrayDeque<View> pending = new java.util.ArrayDeque<View>();
        pending.add(dialog.getWindow().getDecorView());
        while (!pending.isEmpty()) {
            View view = pending.poll();
            if (view instanceof android.widget.TextView
                    && ((android.widget.TextView) view).getText().toString().contains("clipboard")) hasMessage = true;
            if (view instanceof ViewGroup) {
                for (int i = 0; i < ((ViewGroup) view).getChildCount(); i++) pending.add(((ViewGroup) view).getChildAt(i));
            }
        }
        assertTrue(hasMessage);
        dialog.dismiss();
        controller.pause().stop().destroy();
    }

    @Test
    public void headingsFollowTheThemeAndAeroSoftensItsWallpaper() {
        Theme.apply("senko-holo", false, 0);
        assertTrue(Theme.heading() == 0xffffffff);
        Theme.apply("senko-holo", true, 0);
        assertTrue(Theme.heading() == 0xff111111);
        Theme.apply("senko-frutigeraero", true, 0);
        android.graphics.drawable.Drawable page = Decor.page();
        assertTrue(page instanceof android.graphics.drawable.LayerDrawable);
        assertTrue(((android.graphics.drawable.LayerDrawable) page).getNumberOfLayers() == 3);
    }

    @Test
    public void theAndroidThemesAreOfficialAndFirst() {
        assertTrue("the android group leads the picker", Theme.groups().get(0).equals("android"));
        List<Theme.Def> android = Theme.inGroup("android");
        assertTrue(android.size() == 3);
        for (String id : new String[] { "senko-material", "senko-material5", "senko-holo" }) {
            assertNotNull(id, Theme.find(id));
            assertFalse(id + " is not a theme of the user", CustomThemes.isCustom(id));
        }
        assertTrue("Material You keeps the id the old Material theme had", Theme.find("senko-material").name.contains("You"));
        assertTrue(Theme.find("senko-material").flags.contains("rubik"));
    }

    @Test
    public void expressiveButtonFinishesItsPressWhenHidden() throws Exception {
        Theme.apply("senko-material", false, 0);
        android.graphics.drawable.Drawable button = Shapes.pressableCookie();
        button.setState(new int[] { android.R.attr.state_pressed, android.R.attr.state_enabled });
        button.jumpToCurrentState();
        Field press = button.getClass().getDeclaredField("press");
        press.setAccessible(true);
        assertEquals(1f, press.getFloat(button), 0.001f);
        button.setState(new int[] { android.R.attr.state_enabled });
        button.setVisible(false, false);
        assertEquals(0f, press.getFloat(button), 0.001f);
    }

    @Test
    public void materialYouShowsTheShimmeringTitleAndTheOthersDoNot() {
        Theme.apply("senko-material", false, 0);
        Activity you = build(HomeActivity.class, new Intent(RuntimeEnvironment.getApplication(), HomeActivity.class));
        assertTrue(hasView(you.findViewById(android.R.id.content), ShimmerText.class));
        assertTrue(hasText(you.findViewById(android.R.id.content), "senko-droid"));
        Theme.apply("senko-holo", true, 0);
        Activity holo = build(HomeActivity.class, new Intent(RuntimeEnvironment.getApplication(), HomeActivity.class));
        assertFalse(hasView(holo.findViewById(android.R.id.content), ShimmerText.class));
    }

    @Test
    public void materialYouHeaderContainsTheWholeCaption() {
        Theme.apply("senko-material", false, 0);
        Activity activity = build(HomeActivity.class,
                new Intent(RuntimeEnvironment.getApplication(), HomeActivity.class));
        View content = activity.findViewById(android.R.id.content);
        android.widget.TextView caption = findText(content, "by sqmrak :3");
        assertNotNull(caption);
        caption.setTextSize(22);
        content.measure(View.MeasureSpec.makeMeasureSpec(480, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(900, View.MeasureSpec.EXACTLY));
        content.layout(0, 0, 480, 900);
        View title = (View) caption.getParent();
        View header = (View) title.getParent();
        assertTrue("the caption must fit inside the header", title.getBottom() <= header.getHeight());
        assertTrue("the caption must have its full measured height", caption.getBottom() <= title.getHeight());
        assertTrue("the caption line must not be clipped", caption.getHeight() >= caption.getLineHeight());
    }

    private static android.widget.TextView findText(View view, String text) {
        if (view instanceof android.widget.TextView
                && ((android.widget.TextView) view).getText().toString().equals(text))
            return (android.widget.TextView) view;
        if (view instanceof ViewGroup) {
            ViewGroup group = (ViewGroup) view;
            for (int i = 0; i < group.getChildCount(); ++i) {
                android.widget.TextView found = findText(group.getChildAt(i), text);
                if (found != null) return found;
            }
        }
        return null;
    }

    private static boolean hasView(View view, Class<?> type) {
        if (type.isInstance(view)) return true;
        if (view instanceof ViewGroup) {
            ViewGroup group = (ViewGroup) view;
            for (int i = 0; i < group.getChildCount(); ++i) if (hasView(group.getChildAt(i), type)) return true;
        }
        return false;
    }

    private static boolean hasText(View view, String needle) {
        if (view instanceof android.widget.TextView
                && ((android.widget.TextView) view).getText().toString().contains(needle)) return true;
        if (view instanceof android.widget.ListView) {
            android.widget.ListView list = (android.widget.ListView) view;
            // a list lays out only the rows that fit, so the adapter is walked for the rest
            list.measure(View.MeasureSpec.makeMeasureSpec(480, View.MeasureSpec.EXACTLY),
                    View.MeasureSpec.makeMeasureSpec(4000, View.MeasureSpec.EXACTLY));
            list.layout(0, 0, 480, 4000);
        }
        if (view instanceof ViewGroup) {
            ViewGroup group = (ViewGroup) view;
            for (int i = 0; i < group.getChildCount(); ++i) if (hasText(group.getChildAt(i), needle)) return true;
        }
        return false;
    }

    private static Activity build(Class<?> screen, Intent intent) {
        ActivityController<? extends Activity> controller =
                Robolectric.buildActivity(screen.asSubclass(Activity.class), intent);
        Activity activity = controller.create().start().resume().visible().get();
        assertFalse(screen.getSimpleName() + " finished while it was built", activity.isFinishing());
        View content = activity.findViewById(android.R.id.content);
        assertNotNull(content);
        assertTrue(screen.getSimpleName() + " is empty", count(content) > 3);
        controller.pause().stop().destroy();
        return activity;
    }

    @Test
    public void everyScreenBuildsUnderTheDefaultTheme() {
        for (Class<?> screen : SCREENS)
            build(screen, new Intent(RuntimeEnvironment.getApplication(), screen));
    }

    @Test
    public void theScreensShowTheCatalog() {
        Activity list = build(ServerListActivity.class,
                new Intent(RuntimeEnvironment.getApplication(), ServerListActivity.class));
        View listContent = list.findViewById(android.R.id.content);
        assertTrue(hasText(listContent, "Berlin"));
        assertTrue(hasText(listContent, "Panel One"));
        Activity stats = build(StatsActivity.class,
                new Intent(RuntimeEnvironment.getApplication(), StatsActivity.class));
        assertTrue(hasText(stats.findViewById(android.R.id.content), "Panel One"));
        Activity settings = build(SettingsActivity.class,
                new Intent(RuntimeEnvironment.getApplication(), SettingsActivity.class));
        assertTrue(hasText(settings.findViewById(android.R.id.content), "Quick connect"));
    }

    @Test
    public void everyThemeBuildsTheHomeAndTheList() {
        List<Theme.Def> themes = Theme.all();
        assertTrue(themes.size() >= 7);
        for (Theme.Def def : themes) {
            for (boolean light : new boolean[] { false, true }) {
                Theme.apply(def.id, light, 0);
                build(HomeActivity.class, new Intent(RuntimeEnvironment.getApplication(), HomeActivity.class));
                build(ServerListActivity.class,
                        new Intent(RuntimeEnvironment.getApplication(), ServerListActivity.class));
            }
        }
        Theme.apply(Theme.DEFAULT_ID, false, 0);
    }

    @Test
    public void everyLanguageBuildsTheSettings() {
        for (int lang = Tr.ENGLISH; lang <= Tr.CHINESE; ++lang) {
            Tr.setLanguage(lang);
            build(SettingsActivity.class, new Intent(RuntimeEnvironment.getApplication(), SettingsActivity.class));
            build(ServerListActivity.class,
                    new Intent(RuntimeEnvironment.getApplication(), ServerListActivity.class));
        }
        Tr.setLanguage(Tr.ENGLISH);
    }

    @Test
    public void theGroupAndEditScreensBuild() {
        Intent group = new Intent(RuntimeEnvironment.getApplication(), ThemesActivity.class);
        group.putExtra(ThemesActivity.EXTRA_GROUP, "custom");
        build(ThemesActivity.class, group);
        Intent ios = new Intent(RuntimeEnvironment.getApplication(), ThemesActivity.class);
        ios.putExtra(ThemesActivity.EXTRA_GROUP, "ios");
        build(ThemesActivity.class, ios);

        String id = CustomThemes.save(CustomThemes.draftFrom(Theme.DEFAULT_ID));
        assertNotNull(id);
        Intent edit = new Intent(RuntimeEnvironment.getApplication(), ThemeEditActivity.class);
        edit.putExtra(ThemeEditActivity.EXTRA_ID, id);
        build(ThemeEditActivity.class, edit);

        Intent sub = new Intent(RuntimeEnvironment.getApplication(), EditSubscriptionActivity.class);
        sub.putExtra(EditSubscriptionActivity.EXTRA_INDEX, 0);
        build(EditSubscriptionActivity.class, sub);
    }

    @Test
    public void theDeveloperToolsBuild() {
        for (String tool : new String[] { DevToolsActivity.STATE, DevToolsActivity.CHECKS, DevToolsActivity.FORCE,
                DevToolsActivity.RESCUE, DevToolsActivity.CONSOLE }) {
            Intent intent = new Intent(RuntimeEnvironment.getApplication(), DevToolsActivity.class);
            intent.putExtra(DevToolsActivity.EXTRA_TOOL, tool);
            build(DevToolsActivity.class, intent);
        }
        Intent entries = new Intent(RuntimeEnvironment.getApplication(), ProfileEntriesActivity.class);
        entries.putExtra(ProfileEntriesActivity.EXTRA_INDEX, 0);
        build(ProfileEntriesActivity.class, entries);
    }

    @Test
    public void theAppListBuilds() {
        build(AppRoutingActivity.class,
                new Intent(RuntimeEnvironment.getApplication(), AppRoutingActivity.class));
    }
}
