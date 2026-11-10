package com.senko.droid;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import static org.robolectric.Shadows.shadowOf;

import android.os.Looper;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.ConscryptMode;
import org.robolectric.annotation.LooperMode;

import java.util.concurrent.TimeUnit;

@RunWith(RobolectricTestRunner.class)
@ConscryptMode(ConscryptMode.Mode.OFF)
@Config(sdk = {19, 28})
@LooperMode(LooperMode.Mode.PAUSED)
public final class TweenTest {
    @Test public void framesAdvanceAndCancellationStopsUpdates() {
        final int[] updates = {0};
        final int[] endings = {0};
        Tween tween = Tween.ofFloat(0f, 1f).setDuration(1000);
        tween.addUpdateListener(new Tween.Update() {
            @Override public void onUpdate(Tween animation) { updates[0]++; }
        });
        tween.addListener(new Tween.End() {
            @Override public void onEnd(Tween animation, boolean cancelled) { endings[0]++; }
        });
        tween.start();
        shadowOf(Looper.getMainLooper()).idleFor(100, TimeUnit.MILLISECONDS);
        assertTrue(updates[0] > 1);
        assertTrue((Float) tween.getAnimatedValue() > 0f);
        tween.cancel();
        int stopped = updates[0];
        shadowOf(Looper.getMainLooper()).idleFor(100, TimeUnit.MILLISECONDS);
        assertEquals(stopped, updates[0]);
        assertEquals(1, endings[0]);
    }

    @Test public void finiteAnimationFinishesAtItsEndValue() {
        Tween tween = Tween.ofFloat(2f, 5f).setDuration(100);
        tween.start();
        shadowOf(Looper.getMainLooper()).idleFor(200, TimeUnit.MILLISECONDS);
        assertEquals(5f, (Float) tween.getAnimatedValue(), 0f);
    }
}
