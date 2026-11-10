package com.senko.droid;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import android.view.animation.Interpolator;

import org.junit.Test;

public final class MotionTest {
    private static float peak(Interpolator curve) {
        float best = 0f;
        for (int i = 0; i <= 1000; ++i) best = Math.max(best, curve.getInterpolation(i / 1000f));
        return best;
    }

    private static int crossings(Interpolator curve) {
        int count = 0;
        boolean above = false;
        for (int i = 1; i < 1000; ++i) {
            boolean now = curve.getInterpolation(i / 1000f) > 1f;
            if (i > 1 && now != above) ++count;
            above = now;
        }
        return count;
    }

    @Test
    public void everyCurveStartsAtZeroAndEndsExactlyAtOne() {
        Interpolator[] all = { Motion.SPRING, Motion.WOBBLE, Motion.SETTLE, Motion.ENTER, Motion.EXIT,
                Motion.POPUP_ENTER, Motion.POPUP_EXIT, Motion.LIST_MOVE, Motion.DISMISS, Motion.SWEEP };
        for (Interpolator curve : all) {
            assertEquals(0f, curve.getInterpolation(0f), 0f);
            assertEquals(1f, curve.getInterpolation(1f), 0f);
            assertEquals("a value outside the range reads as the end", 1f, curve.getInterpolation(1.5f), 0f);
        }
    }

    @Test
    public void theSpringOvershootsByAboutNinePercentLikeExteria() {
        // exp(-pi * 0.6 / sqrt(1 - 0.36)) for a damping ratio of 0.6
        assertEquals(1.095f, peak(Motion.SPRING), 0.01f);
    }

    @Test
    public void theWobbleSwingsPastOneAndBackMoreThanOnce() {
        assertTrue("a wide first swing", peak(Motion.WOBBLE) > 1.2f);
        assertTrue("it comes back through the end and passes it again", crossings(Motion.WOBBLE) >= 3);
    }

    @Test
    public void theSettlingCurveStaysNearTheEnd() {
        assertTrue(peak(Motion.SETTLE) < 1.06f);
    }

    @Test
    public void quintEaseOutCoversMostOfTheWayEarly() {
        assertTrue(Motion.ENTER.getInterpolation(0.25f) > 0.7f);
        assertTrue("and never passes the end", peak(Motion.ENTER) <= 1.0001f);
    }

    @Test
    public void emphasizedPopupCurvesAreFastInAndSlowOut() {
        assertTrue(Motion.POPUP_ENTER.getInterpolation(0.25f) > 0.6f);
        assertTrue(Motion.POPUP_EXIT.getInterpolation(0.5f) < 0.2f);
    }

    @Test
    public void onlyTheSpringsAreMarkedAsOvershooting() {
        assertTrue(Motion.overshoots(Motion.SPRING));
        assertTrue(Motion.overshoots(Motion.WOBBLE));
        assertTrue(Motion.overshoots(Motion.SETTLE));
        assertTrue(!Motion.overshoots(Motion.ENTER));
    }
}
