package com.senko.droid;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RadialGradient;
import android.graphics.RectF;
import android.graphics.Shader;
import android.os.SystemClock;
import android.view.View;

import java.util.Random;

/**
 * flakes that fall down the screen: soft snow dots, or the sprite of a theme.
 * it takes no touches, draws at about 30 frames a second only while the window
 * is visible, and holds a fixed number of flakes so nothing grows with time
 */
final class FallField extends View {
    private static final int SNOW_COUNT = 32;
    private static final int SPRITE_COUNT = 10;
    private static final long FRAME_MS = 33;
    private static final float SNOW_RADIUS = 16f;

    private final boolean snow;
    private final Bitmap sprite;
    private final Random random = new Random();
    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);
    private final RectF target = new RectF();
    private final float[] x;
    private final float[] y;
    private final float[] vx;
    private final float[] vy;
    private final float[] rotation;
    private final float[] spin;
    private final float[] scale;
    private final float[] alpha;
    private final float[] phase;
    private final float[] phaseSpeed;
    private final float[] sway;
    private final float baseHeight;
    private long last;
    private boolean running;
    private int laidWidth;
    private int laidHeight;

    private final Runnable frame = new Runnable() {
        @Override
        public void run() {
            if (!running) return;
            invalidate();
            postDelayed(this, FRAME_MS);
        }
    };

    /** a snow field when spriteResource is null, otherwise flakes of that drawable */
    FallField(Context context, String spriteResource) {
        super(context);
        setClickable(false);
        snow = spriteResource == null;
        baseHeight = Ui.dp(snow ? 12 : 70);
        sprite = snow ? null : loadSprite(context, spriteResource, Math.round(baseHeight * 1.2f));
        int count = snow ? SNOW_COUNT : SPRITE_COUNT;
        x = new float[count];
        y = new float[count];
        vx = new float[count];
        vy = new float[count];
        rotation = new float[count];
        spin = new float[count];
        scale = new float[count];
        alpha = new float[count];
        phase = new float[count];
        phaseSpeed = new float[count];
        sway = new float[count];
        if (snow) {
            // a soft white dot with a faint blue edge, so a flake still shows on a light theme
            paint.setShader(new RadialGradient(0, 0, SNOW_RADIUS - 3, new int[] { 0xffffffff, 0x00ffffff },
                    new float[] { 0.35f, 1f }, Shader.TileMode.CLAMP));
        }
    }

    /** one scaled copy, decoded with a sample size first so a large png never sits in memory whole */
    private static Bitmap loadSprite(Context context, String name, int height) {
        int id = context.getResources().getIdentifier(name, "drawable", context.getPackageName());
        if (id == 0) return null;
        BitmapFactory.Options bounds = new BitmapFactory.Options();
        bounds.inJustDecodeBounds = true;
        BitmapFactory.decodeResource(context.getResources(), id, bounds);
        BitmapFactory.Options options = new BitmapFactory.Options();
        options.inSampleSize = 1;
        while (bounds.outHeight / (options.inSampleSize * 2) >= height) options.inSampleSize *= 2;
        Bitmap source = BitmapFactory.decodeResource(context.getResources(), id, options);
        if (source == null) return null;
        int width = Math.max(1, Math.round(source.getWidth() * (height / (float) source.getHeight())));
        Bitmap scaled = Bitmap.createScaledBitmap(source, width, height, true);
        if (scaled != source) source.recycle();
        return scaled;
    }

    private float between(float low, float high) {
        return low + random.nextFloat() * (high - low);
    }

    private void reset(int i, boolean scatter) {
        float width = Math.max(getWidth(), Ui.dp(320));
        float height = Math.max(getHeight(), Ui.dp(480));
        float density = Ui.density();
        if (snow) {
            scale[i] = between(0.45f, 1.5f);
            alpha[i] = between(0.55f, 1f);
            // small flakes fall slower than big ones, which reads as depth
            vy[i] = (between(26f, 40f) + 22f * scale[i]) * density;
            vx[i] = between(-5f, 5f) * density;
            sway[i] = between(6f, 18f) * density;
            phase[i] = between(0f, 6.2832f);
            phaseSpeed[i] = between(0.6f, 1.6f);
        } else {
            scale[i] = between(0.7f, 1.2f);
            alpha[i] = between(0.84f, 1f);
            vy[i] = between(44f, 64f) * density;
            vx[i] = between(-6f, 6f) * density;
            rotation[i] = between(-10f, 10f);
            spin[i] = between(-16f, 16f);
        }
        x[i] = between(Ui.dp(24), Math.max(Ui.dp(25), width - Ui.dp(24)));
        y[i] = scatter ? between(-Ui.dp(40), height) : between(-(baseHeight + Ui.dp(40)), -Ui.dp(36));
    }

    @Override
    protected void onSizeChanged(int w, int h, int oldw, int oldh) {
        super.onSizeChanged(w, h, oldw, oldh);
        if (w == laidWidth && h == laidHeight) return;
        laidWidth = w;
        laidHeight = h;
        for (int i = 0; i < x.length; ++i) reset(i, true);
    }

    @Override
    protected void onDraw(Canvas canvas) {
        long now = SystemClock.uptimeMillis();
        float dt = last == 0 ? FRAME_MS / 1000f : Math.min(0.1f, (now - last) / 1000f);
        last = now;
        int width = getWidth();
        int height = getHeight();
        for (int i = 0; i < x.length; ++i) {
            y[i] += vy[i] * dt;
            x[i] += vx[i] * dt;
            if (snow) {
                phase[i] += phaseSpeed[i] * dt;
                x[i] += (float) Math.sin(phase[i]) * sway[i] * dt;
            } else {
                rotation[i] += spin[i] * dt;
            }
            float size = baseHeight * scale[i];
            if (y[i] > height + size || x[i] < -size * 2 || x[i] > width + size * 2) {
                reset(i, false);
                continue;
            }
            paint.setAlpha(Math.round(alpha[i] * 255));
            canvas.save();
            canvas.translate(x[i], y[i]);
            if (snow) {
                float s = size / (SNOW_RADIUS * 2);
                canvas.scale(s, s);
                canvas.drawCircle(0, 0, SNOW_RADIUS - 3, paint);
            } else if (sprite != null) {
                canvas.rotate(rotation[i]);
                float h = size;
                float w = h * sprite.getWidth() / sprite.getHeight();
                target.set(-w / 2, -h / 2, w / 2, h / 2);
                canvas.drawBitmap(sprite, null, target, paint);
            }
            canvas.restore();
        }
    }

    private void start() {
        if (running) return;
        running = true;
        last = 0;
        postDelayed(frame, FRAME_MS);
    }

    private void stop() {
        running = false;
        removeCallbacks(frame);
    }

    @Override
    protected void onAttachedToWindow() {
        super.onAttachedToWindow();
        if (getWindowVisibility() == VISIBLE) start();
    }

    @Override
    protected void onDetachedFromWindow() {
        stop();
        super.onDetachedFromWindow();
    }

    @Override
    protected void onWindowVisibilityChanged(int visibility) {
        super.onWindowVisibilityChanged(visibility);
        if (visibility == VISIBLE) start();
        else stop();
    }

    /** the field the current theme and settings ask for, null when there is none */
    static FallField forCurrent(Context context) {
        if (Theme.current().flags.contains("boykisser")) return new FallField(context, "boykisser");
        if (Prefs.snow()) return new FallField(context, null);
        return null;
    }
}
