package com.senko.droid;

import android.content.Context;
import android.content.res.Resources;
import android.graphics.Bitmap;
import android.graphics.BitmapShader;
import android.graphics.Canvas;
import android.graphics.ColorFilter;
import android.graphics.Paint;
import android.graphics.PixelFormat;
import android.graphics.PorterDuff;
import android.graphics.PorterDuffColorFilter;
import android.graphics.Rect;
import android.graphics.RectF;
import android.graphics.Shader;
import android.graphics.drawable.BitmapDrawable;
import android.graphics.drawable.Drawable;

/** themed interface symbols, country flags, and server artwork */
final class Icons {
    private Icons() {}

    /** a material symbol by its application name */
    static Drawable glyph(Context context, String name, int tint) {
        return resource(context, "material_glyph_" + name.replace('-', '_'), tint);
    }

    static Drawable resource(Context context, String name, int tint) {
        Resources resources = context.getResources();
        int id = resources.getIdentifier(name, "drawable", context.getPackageName());
        if (id == 0) return null;
        Drawable drawable = resources.getDrawable(id).mutate();
        if (tint != 0) drawable.setColorFilter(new PorterDuffColorFilter(tint, PorterDuff.Mode.SRC_IN));
        return drawable;
    }

    /** the flag of a two letter country code, or null when there is none */
    static Drawable flag(Context context, String code) {
        if (code == null || code.length() != 2) return null;
        Drawable flag = resource(context, "flag_" + code.toLowerCase(java.util.Locale.US), 0);
        if (flag instanceof BitmapDrawable && ((BitmapDrawable) flag).getBitmap() != null)
            return new RoundedBitmap(((BitmapDrawable) flag).getBitmap(), 0.24f);
        return flag;
    }

    /** a picture resource with rounded corners, the radius as a fraction of the shorter side */
    static Drawable rounded(Context context, int resource, float radiusFraction) {
        Drawable drawable = context.getResources().getDrawable(resource);
        if (drawable instanceof BitmapDrawable && ((BitmapDrawable) drawable).getBitmap() != null)
            return new RoundedBitmap(((BitmapDrawable) drawable).getBitmap(), radiusFraction);
        return drawable;
    }

    /** the flag with rounded corners. a shader fill, because clipPath on a view is not hardware drawn before api 18 */
    private static final class RoundedBitmap extends Drawable {
        private final Bitmap bitmap;
        private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);
        private final RectF rect = new RectF();
        private final float radiusFraction;

        RoundedBitmap(Bitmap bitmap, float radiusFraction) {
            this.bitmap = bitmap;
            this.radiusFraction = radiusFraction;
            paint.setShader(new BitmapShader(bitmap, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP));
        }

        @Override
        public void draw(Canvas canvas) {
            Rect b = getBounds();
            if (b.isEmpty()) return;
            rect.set(b);
            android.graphics.Matrix fit = new android.graphics.Matrix();
            fit.setScale(rect.width() / bitmap.getWidth(), rect.height() / bitmap.getHeight());
            fit.postTranslate(rect.left, rect.top);
            paint.getShader().setLocalMatrix(fit);
            float radius = Math.min(rect.width(), rect.height()) * radiusFraction;
            canvas.drawRoundRect(rect, radius, radius, paint);
        }

        @Override
        public int getIntrinsicWidth() {
            return bitmap.getWidth();
        }

        @Override
        public int getIntrinsicHeight() {
            return bitmap.getHeight();
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

    /**
     * an icon drawn at a fixed size in the middle of its button. a view hands its whole
     * rect to a drawable, which made every bar and row icon as big as its tap target
     */
    static Drawable inBox(Drawable icon, int sizeDp) {
        if (icon == null) return null;
        return new Centered(icon, sizeDp);
    }

    private static final class Centered extends Drawable {
        private final Drawable inner;
        private final int sizeDp;

        Centered(Drawable inner, int sizeDp) {
            this.inner = inner;
            this.sizeDp = sizeDp;
        }

        @Override
        protected void onBoundsChange(Rect bounds) {
            int half = Ui.dp(sizeDp) / 2;
            inner.setBounds(bounds.centerX() - half, bounds.centerY() - half,
                    bounds.centerX() + half, bounds.centerY() + half);
        }

        @Override
        public void draw(Canvas canvas) {
            inner.draw(canvas);
        }

        @Override
        public void setAlpha(int alpha) {
            inner.setAlpha(alpha);
        }

        @Override
        public void setColorFilter(ColorFilter filter) {
            inner.setColorFilter(filter);
        }

        @Override
        public int getOpacity() {
            return PixelFormat.TRANSLUCENT;
        }
    }

    static Drawable placeholder(Context context) {
        return resource(context, "server_placeholder", 0);
    }

    enum Shape { CHEVRON, BACK, PLUS, DOTS, CHECK, REFRESH, GAUGE }

    static Drawable shape(Context context, Shape shape, int color) {
        return resource(context, "material_shape_" + shape.name().toLowerCase(java.util.Locale.US), color);
    }
}
