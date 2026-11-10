package com.senko.droid;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.ConscryptMode;

/**
 * the geometry of the tablet and television layouts: the capped column of the list screens and the
 * three arrangements of the home screen. positions only, nothing here says how it looks
 */
@RunWith(RobolectricTestRunner.class)
@ConscryptMode(ConscryptMode.Mode.OFF)
@Config(sdk = {28})
public class WideLayoutTest {
    private static View first(ViewGroup group) {
        return group.getChildAt(0);
    }

    private static void layout(View root, int w, int h) {
        root.measure(View.MeasureSpec.makeMeasureSpec(w, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(h, View.MeasureSpec.EXACTLY));
        root.layout(0, 0, w, h);
    }

    @Test
    @Config(qualifiers = "w320dp-h480dp-mdpi")
    public void smallPhoneHeaderKeepsTitleBetweenButtons() {
        HomeActivity home = Robolectric.buildActivity(HomeActivity.class).create().get();
        View[] parts = homeParts(home);
        layout(parts[3], Ui.dp(320), Ui.dp(480));
        ViewGroup header = (ViewGroup) parts[0];
        View left = header.getChildAt(0);
        View right = header.getChildAt(1);
        View title = header.getChildAt(2);
        assertTrue(title.getLeft() >= left.getRight());
        assertTrue(title.getRight() <= right.getLeft());
        assertTrue("orbit " + parts[1].getTop() + ":" + parts[1].getBottom()
                + " info " + parts[2].getTop() + ":" + parts[2].getBottom()
                + " children " + ((ViewGroup) parts[2]).getChildAt(0).getMeasuredHeight() + ","
                + ((ViewGroup) parts[2]).getChildAt(1).getMeasuredHeight() + ","
                + ((ViewGroup) parts[2]).getChildAt(2).getMeasuredHeight() + ","
                + ((ViewGroup) parts[2]).getChildAt(3).getMeasuredHeight(),
                parts[1].getBottom() <= parts[2].getTop());
        assertTrue(parts[2].getBottom() <= parts[3].getHeight());
    }

    @Test
    @Config(qualifiers = "w1280dp-h800dp-xhdpi")
    public void columnStopsAtItsCapAndCenters() {
        Wide.Column column = new Wide.Column(Robolectric.buildActivity(HomeActivity.class).get(), false);
        View child = new View(column.getContext());
        column.addView(child, new ViewGroup.LayoutParams(-1, -1));
        int width = Ui.dp(1280);
        layout(column, width, Ui.dp(800));
        assertEquals(Ui.dp(Wide.COLUMN_DP), child.getWidth());
        assertEquals((width - Ui.dp(Wide.COLUMN_DP)) / 2, child.getLeft());
    }

    @Test
    @Config(qualifiers = "w360dp-h640dp-xhdpi")
    public void columnKeepsAPhoneFullWidth() {
        Wide.Column column = new Wide.Column(Robolectric.buildActivity(HomeActivity.class).get(), false);
        View child = new View(column.getContext());
        column.addView(child, new ViewGroup.LayoutParams(-1, -1));
        layout(column, Ui.dp(360), Ui.dp(640));
        assertEquals(Ui.dp(360), child.getWidth());
        assertEquals(0, child.getLeft());
    }

    private static View[] homeParts(HomeActivity home) {
        FrameLayout root = (FrameLayout) home.findViewById(android.R.id.content).getRootView()
                .findViewById(android.R.id.content);
        ViewGroup content = (ViewGroup) root.getChildAt(0);
        ViewGroup layout = (ViewGroup) content.getChildAt(content.getChildCount() - 1);
        return new View[] { layout.getChildAt(0), layout.getChildAt(1), layout.getChildAt(2), layout };
    }

    @Test
    @Config(qualifiers = "w1280dp-h800dp-xhdpi")
    public void tabletStacksAndHoldsTheColumn() {
        HomeActivity home = Robolectric.buildActivity(HomeActivity.class).create().get();
        View[] parts = homeParts(home);
        layout(parts[3], Ui.dp(1280), Ui.dp(800));
        View orbit = parts[1];
        View info = parts[2];
        assertEquals(Ui.dp(480), info.getWidth());
        assertEquals((Ui.dp(1280) - Ui.dp(480)) / 2, info.getLeft());
        assertTrue("the button sits above the column", orbit.getBottom() <= info.getTop());
        assertTrue(orbit.getWidth() <= Ui.dp(380));
    }

    @Test
    @Config(qualifiers = "w960dp-h540dp-xhdpi")
    public void televisionPutsTheButtonBesideTheColumn() {
        HomeActivity home = Robolectric.buildActivity(HomeActivity.class).create().get();
        View[] parts = homeParts(home);
        layout(parts[3], Ui.dp(960), Ui.dp(540));
        View orbit = parts[1];
        View info = parts[2];
        assertTrue("the column is right of the button", info.getLeft() >= orbit.getRight());
        assertTrue("the column does not stretch across the right side", info.getWidth() <= Ui.dp(400));
        assertTrue(info.getRight() <= Ui.dp(960));
        assertTrue(orbit.getBottom() <= Ui.dp(540));
    }

    @Test
    @Config(qualifiers = "w640dp-h360dp-xhdpi")
    public void phoneOnItsSideUsesTheSameSplit() {
        HomeActivity home = Robolectric.buildActivity(HomeActivity.class).create().get();
        View[] parts = homeParts(home);
        layout(parts[3], Ui.dp(640), Ui.dp(360));
        assertTrue(parts[2].getLeft() >= parts[1].getRight());
        assertTrue(parts[2].getWidth() <= Ui.dp(340));
        assertFalse(parts[1].getBottom() > Ui.dp(360));
    }
}
