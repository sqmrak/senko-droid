package com.senko.droid;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import android.app.Activity;
import android.content.DialogInterface;
import android.view.View;
import android.view.ViewGroup;
import android.widget.EditText;
import android.widget.ImageView;
import android.widget.TextView;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.ConscryptMode;

/** the popup is built, shows its text and sticker, and reports button and list taps */
@RunWith(RobolectricTestRunner.class)
@ConscryptMode(ConscryptMode.Mode.OFF)
@Config(sdk = {19, 28})
public class SenkoDialogTest {
    private static Activity activity() {
        return Robolectric.buildActivity(Activity.class).create().start().resume().visible().get();
    }

    private static View content(SenkoDialog dialog) {
        return dialog.getWindow().getDecorView();
    }

    private static void collect(View view, Class<? extends View> type, List<View> out) {
        if (type.isInstance(view)) out.add(view);
        if (view instanceof ViewGroup) {
            ViewGroup group = (ViewGroup) view;
            for (int i = 0; i < group.getChildCount(); i++) collect(group.getChildAt(i), type, out);
        }
    }

    private static boolean hasText(View root, String text) {
        List<View> found = new ArrayList<View>();
        collect(root, TextView.class, found);
        for (View view : found) if (text.contentEquals(((TextView) view).getText())) return true;
        return false;
    }

    @Test
    @Config(qualifiers = "w320dp-h480dp-mdpi")
    public void stickerRisesAboveTheCardOnASmallScreen() {
        SenkoDialog dialog = new SenkoDialog.Builder(activity()).setTitle("Title")
                .setMessage("Message").setPositiveButton("OK", null).show();
        List<View> images = new ArrayList<View>();
        collect(content(dialog), ImageView.class, images);
        ImageView face = (ImageView) images.get(0);
        ViewGroup holder = (ViewGroup) face.getParent();
        holder.measure(View.MeasureSpec.makeMeasureSpec(Ui.dp(280), View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(Ui.dp(460), View.MeasureSpec.AT_MOST));
        holder.layout(0, 0, holder.getMeasuredWidth(), holder.getMeasuredHeight());
        View card = holder.getChildAt(0);
        assertTrue(card.getTop() >= face.getHeight() * 3 / 4 - 1);
        assertTrue(holder.getMeasuredHeight() <= Ui.dp(460));
        dialog.dismiss();
    }

    @Test
    public void aNoteShowsTitleMessageStickerAndOneFilledButton() {
        final int[] clicks = new int[1];
        SenkoDialog dialog = new SenkoDialog.Builder(activity()).setTitle("Saved").setMessage("All done")
                .setPositiveButton("OK", new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface d, int which) {
                        assertEquals(DialogInterface.BUTTON_POSITIVE, which);
                        clicks[0]++;
                    }
                }).show();
        assertTrue(dialog.isShowing());
        assertTrue(hasText(content(dialog), "Saved"));
        assertTrue(hasText(content(dialog), "All done"));
        List<View> images = new ArrayList<View>();
        collect(content(dialog), ImageView.class, images);
        assertEquals(1, images.size());
        assertNotNull(((ImageView) images.get(0)).getDrawable());
        assertNull(dialog.getButton(DialogInterface.BUTTON_NEGATIVE));
        dialog.getButton(DialogInterface.BUTTON_POSITIVE).performClick();
        assertEquals(1, clicks[0]);
        assertFalse(dialog.isShowing());
    }

    @Test
    public void everyMoodHasItsOwnSticker() {
        for (int mood : new int[] { SenkoDialog.MOOD_HAPPY, SenkoDialog.MOOD_ASK, SenkoDialog.MOOD_INPUT,
                SenkoDialog.MOOD_ERROR }) {
            SenkoDialog dialog = new SenkoDialog.Builder(activity()).setMood(mood).setTitle("t")
                    .setPositiveButton("OK", null).show();
            List<View> images = new ArrayList<View>();
            collect(content(dialog), ImageView.class, images);
            assertNotNull("mood " + mood, ((ImageView) images.get(0)).getDrawable());
            dialog.dismiss();
        }
    }

    @Test
    public void aChoiceListReportsTheTappedIndexAndClosesFirst() {
        final int[] picked = { -1 };
        final SenkoDialog[] holder = new SenkoDialog[1];
        holder[0] = new SenkoDialog.Builder(activity()).setTitle("Sort")
                .setItems(new CharSequence[] { "A", "B", "C" }, new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface d, int which) {
                        assertFalse(holder[0].isShowing());
                        picked[0] = which;
                    }
                }).setNegativeButton("Cancel", null).show();
        List<View> texts = new ArrayList<View>();
        collect(content(holder[0]), TextView.class, texts);
        for (View view : texts) {
            if ("B".contentEquals(((TextView) view).getText())) view.performClick();
        }
        assertEquals(1, picked[0]);
    }

    @Test
    public void cancelAndActionButtonsSitInOrderAndDismiss() {
        SenkoDialog dialog = new SenkoDialog.Builder(activity()).setTitle("Delete?")
                .setNegativeButton("Cancel", null).setPositiveButton("Delete", null)
                .setNeutralButton("Later", null).show();
        View[] buttons = {
            dialog.getButton(DialogInterface.BUTTON_NEGATIVE), dialog.getButton(DialogInterface.BUTTON_NEUTRAL),
            dialog.getButton(DialogInterface.BUTTON_POSITIVE),
        };
        ViewGroup row = (ViewGroup) buttons[0].getParent();
        assertEquals(3, row.getChildCount());
        assertEquals(Arrays.asList(buttons), Arrays.asList(row.getChildAt(0), row.getChildAt(1), row.getChildAt(2)));
        buttons[0].performClick();
        assertFalse(dialog.isShowing());
    }

    @Test
    public void aButtonListenerSetAfterwardsKeepsTheDialogOpen() {
        SenkoDialog dialog = new SenkoDialog.Builder(activity()).setTitle("Link")
                .setNeutralButton("Paste", null).setPositiveButton("Add", null).show();
        final int[] taps = new int[1];
        dialog.getButton(DialogInterface.BUTTON_NEUTRAL).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                taps[0]++;
            }
        });
        dialog.getButton(DialogInterface.BUTTON_NEUTRAL).performClick();
        assertEquals(1, taps[0]);
        assertTrue(dialog.isShowing());
    }

    @Test
    public void aTextPromptHandsOverWhatWasTyped() {
        Activity activity = activity();
        final String[] entered = new String[1];
        Dialogs.prompt(activity, "Add link", "Paste it", "", Dialogs.urlInput(), "Add", new Dialogs.Text() {
            @Override
            public void entered(String text) {
                entered[0] = text;
            }
        });
        // the dialog is the newest window of the activity, found through its edit field
        assertNotNull(org.robolectric.shadows.ShadowDialog.getLatestDialog());
        SenkoDialog dialog = (SenkoDialog) org.robolectric.shadows.ShadowDialog.getLatestDialog();
        List<View> fields = new ArrayList<View>();
        collect(content(dialog), EditText.class, fields);
        assertEquals(1, fields.size());
        ((EditText) fields.get(0)).setText("vless://x");
        dialog.getButton(DialogInterface.BUTTON_POSITIVE).performClick();
        assertEquals("vless://x", entered[0]);
    }
}
