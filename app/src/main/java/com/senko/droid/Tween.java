package com.senko.droid;

import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.view.animation.Interpolator;

/**
 * a float or argb animation driven by display frames when available. android.animation starts at api 11,
 * and android 2.3 has nothing of the kind outside view animations, which cannot drive a custom view
 */
final class Tween {
    static final int INFINITE = -1;
    private static final long FRAME_MS = 16;

    interface Update {
        void onUpdate(Tween tween);
    }

    interface End {
        void onEnd(Tween tween, boolean cancelled);
    }

    /** made on first use, so the pure helpers of this class work without a looper */
    private static final class Main {
        static final Handler HANDLER = new Handler(Looper.getMainLooper());
    }

    private final float from;
    private final float to;
    private final boolean argb;
    private long duration = 300;
    private Interpolator interpolator;
    private int repeat;
    private Update update;
    private End end;
    private long startedAt;
    private boolean running;
    private Object value;

    private final Runnable frame = new Runnable() {
        @Override
        public void run() {
            step();
        }
    };
    private Object frameCallback;

    private Tween(float from, float to, boolean argb) {
        this.from = from;
        this.to = to;
        this.argb = argb;
        this.value = argb ? (Object) Integer.valueOf((int) from) : (Object) Float.valueOf(from);
    }

    static Tween ofFloat(float from, float to) {
        return new Tween(from, to, false);
    }

    /** a color blend, channel by channel */
    static Tween ofArgb(int from, int to) {
        Tween tween = new Tween(0f, 1f, true);
        tween.argbFrom = from;
        tween.argbTo = to;
        tween.value = Integer.valueOf(from);
        return tween;
    }

    private int argbFrom;
    private int argbTo;

    /** the system switch that turns animations off arrived with android 8 */
    static boolean enabled() {
        if (android.os.Build.VERSION.SDK_INT < 26) return true;
        return Api26.animatorsEnabled();
    }

    Tween setDuration(long millis) {
        duration = Math.max(1, millis);
        return this;
    }

    long getDuration() {
        return duration;
    }

    Tween setInterpolator(Interpolator value) {
        interpolator = value;
        return this;
    }

    Tween setRepeatCount(int count) {
        repeat = count;
        return this;
    }

    Tween addUpdateListener(Update listener) {
        update = listener;
        return this;
    }

    Tween addListener(End listener) {
        end = listener;
        return this;
    }

    Object getAnimatedValue() {
        return value;
    }

    void start() {
        cancelQuietly();
        running = true;
        startedAt = SystemClock.uptimeMillis();
        publish(0f);
        scheduleFrame();
    }

    void cancel() {
        if (!running) return;
        cancelQuietly();
        if (end != null) end.onEnd(this, true);
    }

    private void cancelQuietly() {
        running = false;
        if (android.os.Build.VERSION.SDK_INT >= 16 && frameCallback != null) {
            Api16.remove(frameCallback);
        } else {
            Main.HANDLER.removeCallbacks(frame);
        }
    }

    private void step() {
        if (!running) return;
        long elapsed = SystemClock.uptimeMillis() - startedAt;
        long cycle = elapsed / duration;
        if (repeat != INFINITE && cycle > repeat) {
            publish(1f);
            cancelQuietly();
            if (end != null) end.onEnd(this, false);
            return;
        }
        float t = (elapsed % duration) / (float) duration;
        publish(t);
        if (running) scheduleFrame();
    }

    private void scheduleFrame() {
        if (android.os.Build.VERSION.SDK_INT >= 16) {
            if (frameCallback == null) frameCallback = Api16.callback(frame);
            Api16.post(frameCallback);
        } else {
            Main.HANDLER.postDelayed(frame, FRAME_MS);
        }
    }

    private void publish(float t) {
        float eased = interpolator == null ? t : interpolator.getInterpolation(t);
        if (argb) {
            value = Integer.valueOf(mix(argbFrom, argbTo, eased));
        } else {
            value = Float.valueOf(from + (to - from) * eased);
        }
        if (update != null) update.onUpdate(this);
    }

    static int mix(int a, int b, float t) {
        int ar = (a >>> 24), rr = (a >> 16) & 255, gr = (a >> 8) & 255, br = a & 255;
        int a2 = (b >>> 24), r2 = (b >> 16) & 255, g2 = (b >> 8) & 255, b2 = b & 255;
        return ((int) (ar + (a2 - ar) * t) << 24) | ((int) (rr + (r2 - rr) * t) << 16)
                | ((int) (gr + (g2 - gr) * t) << 8) | (int) (br + (b2 - br) * t);
    }

    @android.annotation.TargetApi(16)
    private static final class Api16 {
        static Object callback(final Runnable frame) {
            return new android.view.Choreographer.FrameCallback() {
                @Override public void doFrame(long frameTimeNanos) { frame.run(); }
            };
        }

        static void post(Object callback) {
            android.view.Choreographer.getInstance().postFrameCallback(
                    (android.view.Choreographer.FrameCallback) callback);
        }

        static void remove(Object callback) {
            android.view.Choreographer.getInstance().removeFrameCallback(
                    (android.view.Choreographer.FrameCallback) callback);
        }
    }

    /** kept apart so the class that names ValueAnimator is never loaded below android 8 */
    @android.annotation.TargetApi(26)
    private static final class Api26 {
        static boolean animatorsEnabled() {
            return android.animation.ValueAnimator.areAnimatorsEnabled();
        }
    }
}
