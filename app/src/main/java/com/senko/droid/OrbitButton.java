package com.senko.droid;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.PorterDuff;
import android.graphics.drawable.Drawable;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.graphics.Shader;
import android.view.MotionEvent;
import android.view.View;
import android.view.animation.DecelerateInterpolator;
import android.view.animation.LinearInterpolator;
import android.view.animation.OvershootInterpolator;

/**
 * the power control: a disc with a power glyph inside a ring, and a dot that
 * circles the rim while the tunnel is up or dialing. the dot is the only
 * animated part and only runs while it is wanted, so an idle screen draws
 * nothing per frame
 */
final class OrbitButton extends View {
    private static final long SPIN_CONNECTING_MS = 1400;
    private static final long SPIN_CONNECTED_MS = 4500;

    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Drawable powerIcon;
    private final RectF oval = new RectF();
    private String state = "idle";
    private float angle;
    private boolean running = true;
    private boolean pressed;
    private Tween spinner;
    // a ring that leaves the button once when the state changes, 0 to 1 while it travels
    private Tween pulse;
    private float pulseAt = 1f;
    // the expressive theme draws the face as a scalloped cookie that morphs with the state
    private final Path cookie = new Path();
    private float amplitude = Shapes.AMPLITUDE;
    private Tween morph;
    // the ring colour eases to the colour of the new state instead of jumping
    private int shownTint = Theme.stateColor("idle");
    private Tween tintAnim;
    private Tween shake;
    // 0 at rest, 1 while a finger holds the button, drives the swell and the turn of the cookie
    private float press;
    private Tween pressSpring;
    private static final int[] DIAL_LOBES = { 9, 5, 3, 7 };
    private final float[] dialWeights = new float[DIAL_LOBES.length];
    private int iconTint;
    private boolean iconTintSet;

    OrbitButton(Context context) {
        super(context);
        powerIcon = Icons.glyph(context, "power", 0);
        setClickable(true);
        setFocusable(true);
    }

    /** idle, connecting, connected or error */
    void setStateKey(String key) {
        String next = key == null ? "idle" : key;
        if (next.equals(state)) return;
        state = next;
        syncSpin();
        startPulse();
        startMorph();
        startTint();
        if ("error".equals(next)) startShake();
        invalidate();
    }

    /** the dot leaves the ring while it cannot spin, for instance while a request is in flight */
    void setRunning(boolean value) {
        running = value;
        syncSpin();
    }

    private void startTint() {
        if (tintAnim != null) tintAnim.cancel();
        int target = Theme.stateColor(state);
        if (getWindowToken() == null) {
            shownTint = target;
            return;
        }
        tintAnim = Tween.ofArgb(shownTint, target);
        tintAnim.setDuration(380);
        tintAnim.addUpdateListener(new Tween.Update() {
            @Override
            public void onUpdate(Tween animation) {
                shownTint = (Integer) animation.getAnimatedValue();
                invalidate();
            }
        });
        tintAnim.start();
    }

    /** a failed connect shakes the button sideways with a fading swing */
    private void startShake() {
        if (shake != null) shake.cancel();
        if (getWindowToken() == null) return;
        shake = Tween.ofFloat(0f, 1f);
        shake.setDuration(480);
        shake.addUpdateListener(new Tween.Update() {
            @Override
            public void onUpdate(Tween animation) {
                float t = (Float) animation.getAnimatedValue();
                ViewFx.translationX(OrbitButton.this, (float) (Math.sin(t * Math.PI * 6) * (1f - t) * Ui.dp(14)));
            }
        });
        shake.addListener(new Tween.End() {
            @Override
            public void onEnd(Tween animation, boolean cancelled) {
                ViewFx.translationX(OrbitButton.this, 0f);
            }
        });
        shake.start();
    }

    private void springPress(float target) {
        if (!expressive()) return;
        if (pressSpring != null) pressSpring.cancel();
        pressSpring = Tween.ofFloat(press, target);
        pressSpring.setDuration(target > 0 ? 140 : Motion.SPRING_MS);
        pressSpring.setInterpolator(target > 0 ? Motion.ENTER : Motion.SPRING);
        pressSpring.addUpdateListener(new Tween.Update() {
            @Override
            public void onUpdate(Tween animation) {
                press = (Float) animation.getAnimatedValue();
                invalidate();
            }
        });
        pressSpring.start();
    }

    private boolean expressive() {
        return Theme.current().flags.contains("expressive");
    }

    /** smooth round while connected, a wavier outline while dialing, the plain cookie at rest */
    private float targetAmplitude() {
        if ("connected".equals(state)) return 0f;
        if ("connecting".equals(state)) return 0.075f;
        return Shapes.AMPLITUDE;
    }

    private void startMorph() {
        if (morph != null) morph.cancel();
        if (!expressive()) return;
        float target = targetAmplitude();
        if (getWindowToken() == null) {
            amplitude = target;
            return;
        }
        morph = Tween.ofFloat(amplitude, target);
        morph.setDuration(Motion.SPRING_MS);
        morph.setInterpolator(Motion.SPRING);
        morph.addUpdateListener(new Tween.Update() {
            @Override
            public void onUpdate(Tween animation) {
                amplitude = Math.max(0f, (Float) animation.getAnimatedValue());
                invalidate();
            }
        });
        morph.start();
    }

    private void startPulse() {
        if (pulse != null) pulse.cancel();
        if (getWindowToken() == null) return;
        pulse = Tween.ofFloat(0f, 1f);
        pulse.setDuration(650);
        pulse.setInterpolator(new DecelerateInterpolator());
        pulse.addUpdateListener(new Tween.Update() {
            @Override
            public void onUpdate(Tween animation) {
                pulseAt = (Float) animation.getAnimatedValue();
                invalidate();
            }
        });
        pulseAt = 0f;
        pulse.start();
    }

    private boolean orbitWanted() {
        return running && !"idle".equals(state) && !"error".equals(state);
    }

    private void syncSpin() {
        boolean enabled = Tween.enabled();
        boolean wanted = enabled && orbitWanted() && getWindowToken() != null && getWindowVisibility() == VISIBLE;
        if (!wanted) {
            if (spinner != null) {
                spinner.cancel();
                spinner = null;
            }
            return;
        }
        long period = "connecting".equals(state) ? SPIN_CONNECTING_MS : SPIN_CONNECTED_MS;
        if (spinner != null && spinner.getDuration() == period) return;
        if (spinner != null) spinner.cancel();
        spinner = Tween.ofFloat(angle, angle + 360f);
        spinner.setDuration(period);
        spinner.setInterpolator(new LinearInterpolator());
        spinner.setRepeatCount(Tween.INFINITE);
        spinner.addUpdateListener(new Tween.Update() {
            @Override
            public void onUpdate(Tween animation) {
                angle = (Float) animation.getAnimatedValue() % 360f;
                invalidate();
            }
        });
        spinner.start();
    }

    @Override
    protected void onAttachedToWindow() {
        super.onAttachedToWindow();
        syncSpin();
    }

    @Override
    protected void onWindowVisibilityChanged(int visibility) {
        super.onWindowVisibilityChanged(visibility);
        syncSpin();
    }

    @Override
    protected void onDetachedFromWindow() {
        if (spinner != null) {
            spinner.cancel();
            spinner = null;
        }
        if (pulse != null) {
            pulse.cancel();
            pulse = null;
        }
        if (morph != null) {
            morph.cancel();
            morph = null;
        }
        if (tintAnim != null) {
            tintAnim.cancel();
            tintAnim = null;
        }
        if (shake != null) {
            shake.cancel();
            shake = null;
        }
        if (pressSpring != null) {
            pressSpring.cancel();
            pressSpring = null;
        }
        press = 0f;
        shownTint = Theme.stateColor(state);
        amplitude = expressive() ? targetAmplitude() : amplitude;
        pulseAt = 1f;
        ViewFx.cancel(this);
        ViewFx.resetScale(this);
        super.onDetachedFromWindow();
    }

    @Override
    public boolean onTouchEvent(MotionEvent event) {
        int action = event.getActionMasked();
        if (action == MotionEvent.ACTION_DOWN) {
            pressed = true;
            springPress(1f);
            float scale = expressive() ? 0.96f : 0.94f;
            ViewFx.scaleTo(this, scale, expressive() ? 110 : 90, new DecelerateInterpolator());
            invalidate();
        } else if (action == MotionEvent.ACTION_UP || action == MotionEvent.ACTION_CANCEL) {
            pressed = false;
            springPress(0f);
            ViewFx.scaleTo(this, 1f, expressive() ? Motion.WOBBLE_MS : 260,
                    expressive() ? Motion.WOBBLE : new OvershootInterpolator(2.2f));
            invalidate();
        }
        return super.onTouchEvent(event);
    }

    @Override
    protected void onMeasure(int widthSpec, int heightSpec) {
        int width = MeasureSpec.getSize(widthSpec);
        int height = MeasureSpec.getSize(heightSpec);
        int side = Math.min(width > 0 ? width : height, height > 0 ? height : width);
        if (side <= 0 && MeasureSpec.getMode(widthSpec) == MeasureSpec.UNSPECIFIED
                && MeasureSpec.getMode(heightSpec) == MeasureSpec.UNSPECIFIED) side = Ui.dp(200);
        side = Math.max(0, Math.min(side, Ui.dp(Wide.tablet() ? 380 : 320)));
        setMeasuredDimension(side, side);
    }

    private LinearGradient face;
    private float faceTop;
    private float faceBottom;
    private int faceHi;
    private int faceLo;

    /** the face gradient only changes with size, theme and press, so it is rebuilt then and not every frame */
    private LinearGradient faceShader(float top, float bottom, int hi, int lo) {
        if (face == null || top != faceTop || bottom != faceBottom || hi != faceHi || lo != faceLo) {
            face = new LinearGradient(0, top, 0, bottom, hi, lo, Shader.TileMode.CLAMP);
            faceTop = top;
            faceBottom = bottom;
            faceHi = hi;
            faceLo = lo;
        }
        return face;
    }

    /** the cookie outline inside a circle of this radius, turning while the dot circles */
    private Path cookieAt(float cx, float cy, float radius) {
        double turn = orbitWanted() ? Math.toRadians(angle) : 0;
        if ("connecting".equals(state) && orbitWanted() && amplitude > 0.02f) {
            // dialing: the outline passes from nine lobes to five, three, seven and round again,
            // as a loading indicator. the weights always sum to the same swell
            float[] weights = dialWeights;
            float phase = angle / 360f * DIAL_LOBES.length;
            for (int k = 0; k < weights.length; ++k) {
                float d = ((phase - k) % weights.length + weights.length) % weights.length;
                if (d > weights.length / 2f) d -= weights.length;
                float near = Math.min(1f, Math.abs(d));
                weights[k] = 0.085f * (float) (Math.cos(Math.PI * near) + 1) / 2f;
            }
            return Shapes.blend(cookie, cx, cy, radius, DIAL_LOBES, weights, turn);
        }
        float swell = amplitude + 0.035f * press;
        return Shapes.cookie(cookie, cx, cy, radius / (1 + swell), swell, Shapes.LOBES, turn + press * 0.5);
    }

    @Override
    protected void onDraw(Canvas canvas) {
        float side = Math.min(getWidth(), getHeight());
        float cx = getWidth() / 2f;
        float cy = getHeight() / 2f;
        float dotRadius = Math.max(Ui.dp(4), side * 0.018f);
        float trackRadius = side * 0.5f - dotRadius - Ui.dp(3);
        float ringRadius = trackRadius * 0.76f;
        float faceRadius = ringRadius - 1f;
        int tint = shownTint;
        boolean idle = "idle".equals(state);
        boolean flat = Theme.current().flat() && !Theme.current().glass();

        paint.setStyle(Paint.Style.STROKE);
        paint.setStrokeWidth(Ui.dp(1));
        paint.setColor(Ui.alpha(tint, 0.22f));
        canvas.drawCircle(cx, cy, trackRadius, paint);
        if (isFocused()) {
            // a d-pad has no touch to show where it is
            paint.setStrokeWidth(Ui.dp(3));
            paint.setColor(Theme.color(Theme.ACCENT));
            canvas.drawCircle(cx, cy, trackRadius, paint);
            paint.setStrokeWidth(Ui.dp(1));
        }

        if (!idle) {
            paint.setStyle(Paint.Style.FILL);
            paint.setColor(Ui.alpha(tint, 0.18f));
            canvas.drawCircle(cx, cy, ringRadius + Ui.dp(6), paint);
        }

        if (pulseAt < 1f) {
            paint.setStyle(Paint.Style.STROKE);
            paint.setStrokeWidth(Ui.dp(3));
            paint.setColor(Ui.alpha(tint, 0.55f * (1f - pulseAt)));
            canvas.drawCircle(cx, cy, ringRadius + (trackRadius + dotRadius - ringRadius) * pulseAt, paint);
        }

        int hi = Theme.color(Theme.CELL_HI);
        paint.setStyle(Paint.Style.FILL);
        paint.setShader(faceShader(cy - faceRadius, cy + faceRadius,
                Ui.shade(hi, flat ? -0.03f : (pressed ? -0.10f : 0.04f)),
                Ui.shade(hi, flat ? -0.03f : -0.14f)));
        if (expressive()) canvas.drawPath(cookieAt(cx, cy, faceRadius), paint);
        else canvas.drawCircle(cx, cy, faceRadius, paint);
        paint.setShader(null);

        paint.setStyle(Paint.Style.STROKE);
        paint.setStrokeWidth(Math.max(Ui.dp(2.5f), side * 0.013f));
        paint.setColor(tint);
        if (expressive()) canvas.drawPath(cookieAt(cx, cy, ringRadius), paint);
        else canvas.drawCircle(cx, cy, ringRadius, paint);

        if (powerIcon != null) {
            int half = Math.round(ringRadius * 0.48f);
            powerIcon.setBounds(Math.round(cx) - half, Math.round(cy) - half,
                    Math.round(cx) + half, Math.round(cy) + half);
            if (!iconTintSet || iconTint != tint) {
                powerIcon.setColorFilter(tint, PorterDuff.Mode.SRC_IN);
                iconTint = tint;
                iconTintSet = true;
            }
            powerIcon.draw(canvas);
        }

        if (orbitWanted()) {
            double radians = Math.toRadians(angle);
            paint.setStyle(Paint.Style.STROKE);
            paint.setStrokeWidth(Ui.dp(2));
            paint.setColor(Ui.alpha(tint, 0.45f));
            oval.set(cx - trackRadius, cy - trackRadius, cx + trackRadius, cy + trackRadius);
            canvas.drawArc(oval, angle - 60f, 60f, false, paint);
            paint.setStyle(Paint.Style.FILL);
            paint.setColor(tint);
            canvas.drawCircle((float) (cx + trackRadius * Math.cos(radians)),
                    (float) (cy + trackRadius * Math.sin(radians)), dotRadius, paint);
        } else {
            paint.setStyle(Paint.Style.FILL);
            paint.setColor(Ui.alpha(tint, idle ? 0.35f : 0.8f));
            canvas.drawCircle(cx + trackRadius, cy, dotRadius * 0.6f, paint);
        }
    }
}
