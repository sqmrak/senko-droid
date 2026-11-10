package com.senko.droid;

import android.graphics.Canvas;
import android.graphics.ColorFilter;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.PixelFormat;
import android.graphics.drawable.Drawable;

/** the scalloped "cookie" outline of the expressive Material You theme */
final class Shapes {
    private Shapes() {}

    static final int LOBES = 9;
    static final float AMPLITUDE = 0.05f;

    /** a circle whose radius swells and dips lobes times around, amplitude is a fraction of the radius */
    static Path cookie(Path out, float cx, float cy, float radius, float amplitude, int lobes, double rotation) {
        out.reset();
        int steps = 96;
        for (int i = 0; i <= steps; ++i) {
            double angle = Math.PI * 2 * i / steps;
            double r = radius * (1 + amplitude * Math.cos(lobes * angle + rotation));
            float x = (float) (cx + r * Math.cos(angle));
            float y = (float) (cy + r * Math.sin(angle));
            if (i == 0) out.moveTo(x, y);
            else out.lineTo(x, y);
        }
        out.close();
        return out;
    }

    static boolean expressive() {
        return Theme.current().flags.contains("expressive");
    }

    /**
     * several lobed outlines laid over each other, each with its own weight. moving the weights from
     * one outline to the next morphs the shape. the sum of the weights bounds the swell, so the
     * outline stays inside the circle of the given radius while it changes
     */
    static Path blend(Path out, float cx, float cy, float radius, int[] lobes, float[] weights, double turn) {
        float swell = 0;
        for (float w : weights) swell += w;
        out.reset();
        int steps = 120;
        for (int i = 0; i <= steps; ++i) {
            double angle = Math.PI * 2 * i / steps;
            double wave = 1;
            for (int k = 0; k < lobes.length; ++k) wave += weights[k] * Math.cos(lobes[k] * (angle - turn));
            double r = radius / (1 + swell) * wave;
            float x = (float) (cx + r * Math.cos(angle));
            float y = (float) (cy + r * Math.sin(angle));
            if (i == 0) out.moveTo(x, y);
            else out.lineTo(x, y);
        }
        out.close();
        return out;
    }

    /** a button face in the cookie outline that swells, turns and darkens under a finger */
    static Drawable pressableCookie() {
        return new Cookie(Theme.color(Theme.CELL_HI), Ui.shade(Theme.color(Theme.CELL_HI), -0.10f));
    }

    private static final class Cookie extends Drawable {
        private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Path path = new Path();
        private final int rest;
        private final int pressedColor;
        private float press;
        private boolean pressedNow;
        private Tween spring;

        Cookie(int rest, int pressedColor) {
            this.rest = rest;
            this.pressedColor = pressedColor;
            paint.setColor(rest);
        }

        @Override
        public boolean isStateful() {
            return true;
        }

        @Override
        protected boolean onStateChange(int[] state) {
            boolean down = false;
            for (int item : state) if (item == android.R.attr.state_pressed) down = true;
            if (down == pressedNow) return false;
            pressedNow = down;
            if (spring != null) spring.cancel();
            spring = Tween.ofFloat(press, down ? 1f : 0f);
            spring.setDuration(down ? 130 : Motion.SPRING_MS);
            spring.setInterpolator(down ? Motion.ENTER : Motion.SPRING);
            spring.addUpdateListener(new Tween.Update() {
                @Override
                public void onUpdate(Tween animation) {
                    press = (Float) animation.getAnimatedValue();
                    invalidateSelf();
                }
            });
            spring.start();
            return true;
        }

        @Override
        public void jumpToCurrentState() {
            if (spring != null) {
                spring.cancel();
                spring = null;
            }
            press = pressedNow ? 1f : 0f;
            invalidateSelf();
        }

        @Override
        public boolean setVisible(boolean visible, boolean restart) {
            if (!visible) jumpToCurrentState();
            return super.setVisible(visible, restart);
        }

        @Override
        public void draw(Canvas canvas) {
            float w = getBounds().width();
            float h = getBounds().height();
            float amplitude = AMPLITUDE + 0.08f * press;
            float radius = Math.min(w, h) / 2f / (1 + amplitude);
            // the lobes turn a little as the button sinks and settle back when it is released
            cookie(path, getBounds().exactCenterX(), getBounds().exactCenterY(), radius, amplitude, 8, press * 0.6);
            paint.setColor(blendColor(rest, pressedColor, Math.max(0f, Math.min(1f, press))));
            canvas.drawPath(path, paint);
        }

        private int blendColor(int from, int to, float t) {
            return Tween.mix(from, to, t);
        }

        @Override
        public void setAlpha(int alpha) {
            paint.setAlpha(alpha);
        }

        @Override
        public void setColorFilter(ColorFilter filter) {
            paint.setColorFilter(filter);
        }

        @Override
        public int getOpacity() {
            return PixelFormat.TRANSLUCENT;
        }
    }
}
