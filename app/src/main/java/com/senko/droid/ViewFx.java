package com.senko.droid;

import android.view.View;
import android.view.animation.AlphaAnimation;
import android.view.animation.Animation;
import android.view.animation.AnimationSet;
import android.view.animation.Interpolator;
import android.view.animation.RotateAnimation;
import android.view.animation.ScaleAnimation;
import android.view.animation.TranslateAnimation;

/**
 * view alpha, scale, translation and rotation as the view property api has them from android 3.0
 * (ViewPropertyAnimator usable from 4.0). below that the same effect runs through view animations, which move the
 * drawing and not the hit area, as nothing here needs the hit area to move
 */
final class ViewFx {
    private ViewFx() {
    }

    static void cancel(View view) {
        if (android.os.Build.VERSION.SDK_INT >= 14) view.animate().cancel();
    }

    /** a still alpha */
    static void alpha(View view, float value) {
        if (android.os.Build.VERSION.SDK_INT >= 11) {
            view.setAlpha(value);
            return;
        }
        if (value >= 1f) {
            view.clearAnimation();
            return;
        }
        AlphaAnimation hold = new AlphaAnimation(value, value);
        hold.setDuration(0);
        hold.setFillAfter(true);
        view.startAnimation(hold);
    }

    static void translationX(View view, float px) {
        if (android.os.Build.VERSION.SDK_INT >= 11) view.setTranslationX(px);
    }

    static void resetScale(View view) {
        if (android.os.Build.VERSION.SDK_INT >= 11) {
            view.setScaleX(1f);
            view.setScaleY(1f);
        }
    }

    static void fadeIn(View view, long duration) {
        if (android.os.Build.VERSION.SDK_INT >= 14) {
            view.setAlpha(0f);
            view.animate().alpha(1f).setDuration(duration).start();
            return;
        }
        AlphaAnimation fade = new AlphaAnimation(0f, 1f);
        fade.setDuration(duration);
        view.startAnimation(fade);
    }

    /**
     * fade in while sliding up from risePx below, or growing from scaleFrom. a curve that overshoots (the
     * springs of {@link Motion}) moves the size and the offset only: the fade runs on its own deceleration,
     * because an alpha past 1 wraps on the old canvas and flashes
     */
    static void enter(View view, float risePx, float scaleFrom, long delay, long duration, Interpolator curve) {
        boolean apart = Motion.overshoots(curve);
        if (android.os.Build.VERSION.SDK_INT >= 14) {
            view.setAlpha(0f);
            if (scaleFrom != 1f) {
                view.setScaleX(scaleFrom);
                view.setScaleY(scaleFrom);
            }
            if (risePx != 0f) view.setTranslationY(risePx);
            android.view.ViewPropertyAnimator move = view.animate().scaleX(1f).scaleY(1f).translationY(0f)
                    .setStartDelay(delay).setDuration(duration).setInterpolator(curve);
            if (apart) {
                android.animation.ObjectAnimator fade = android.animation.ObjectAnimator.ofFloat(view, "alpha", 0f, 1f);
                fade.setStartDelay(delay);
                fade.setDuration(Math.min(duration, 200));
                fade.setInterpolator(Motion.SWEEP);
                fade.start();
            } else {
                move.alpha(1f);
            }
            move.start();
            return;
        }
        AnimationSet set = new AnimationSet(false);
        set.setInterpolator(curve);
        AlphaAnimation fadeIn = new AlphaAnimation(0f, 1f);
        if (apart) fadeIn.setInterpolator(Motion.SWEEP);
        set.addAnimation(fadeIn);
        if (scaleFrom != 1f) {
            set.addAnimation(new ScaleAnimation(scaleFrom, 1f, scaleFrom, 1f, Animation.RELATIVE_TO_SELF, 0.5f,
                    Animation.RELATIVE_TO_SELF, 0.5f));
        }
        if (risePx != 0f) set.addAnimation(new TranslateAnimation(0f, 0f, risePx, 0f));
        set.setStartOffset(delay);
        set.setDuration(duration);
        // the first frame must already be transparent, or the row flashes before the delay ends
        set.setFillBefore(true);
        view.startAnimation(set);
    }

    /** the press sink of a pressable view, which has no counterpart below android 3.1 */
    static void scaleTo(View view, float scale, long duration, Interpolator curve) {
        if (android.os.Build.VERSION.SDK_INT < 14) return;
        view.animate().scaleX(scale).scaleY(scale).setDuration(duration).setInterpolator(curve).start();
    }

    static void rotation(View view, float from, float to, boolean animate) {
        if (android.os.Build.VERSION.SDK_INT >= 11) {
            if (animate) {
                view.setRotation(from);
                if (android.os.Build.VERSION.SDK_INT >= 14) {
                    view.animate().rotation(to).setDuration(220).start();
                    return;
                }
            }
            view.setRotation(to);
            return;
        }
        RotateAnimation turn = new RotateAnimation(animate ? from : to, to, Animation.RELATIVE_TO_SELF, 0.5f,
                Animation.RELATIVE_TO_SELF, 0.5f);
        turn.setDuration(animate ? 220 : 0);
        turn.setFillAfter(true);
        view.startAnimation(turn);
    }
}
