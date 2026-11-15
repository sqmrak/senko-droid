package com.senko.droid;

import android.content.Context;
import android.content.res.Resources;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.ColorFilter;
import android.graphics.Paint;
import android.graphics.PixelFormat;
import android.graphics.Rect;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.LayerDrawable;

/** the wallpapers some themes lay under the screens, kept as one bounded bitmap */
final class Decor {
    private Decor() {}

    private static Context app;
    private static Bitmap cached;
    private static Bitmap cachedSoft;
    private static String cachedName = "";
    private static int cachedWidth;

    static void init(Context context) {
        app = context.getApplicationContext();
    }

    /** the drawable resource of the current theme's wallpaper, null for the plain gradient themes */
    static String wallpaperName() {
        Theme.Def def = Theme.current();
        if (def.flags.contains("miside")) return "miside_bg";
        if (def.flags.contains("aero")) return Theme.isLight() ? "frutiger_bg" : "frutiger_bg_dark";
        if (def.glass()) return Theme.isLight() ? "ios26_bg_light" : "ios26_bg_dark";
        return null;
    }

    /** the page: the theme gradient, with the wallpaper over it when the theme has one */
    static synchronized Drawable page() {
        GradientDrawable gradient = new GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM,
                new int[] { Theme.color(Theme.BG), Theme.color(Theme.BG_BOTTOM) });
        String name = wallpaperName();
        if (name == null || app == null) return gradient;
        Bitmap bitmap = load(name);
        if (bitmap == null) return gradient;
        if (Theme.current().flags.contains("aero") && cachedSoft != null) {
            return new LayerDrawable(new Drawable[] { gradient, new Cover(cachedSoft) });
        }
        return new LayerDrawable(new Drawable[] { gradient, new Cover(bitmap) });
    }

    /**
     * decoded once per theme and downsampled to the screen width: the jpegs are
     * larger than a phone shows, and an api 16 device has little heap to spare
     */
    private static Bitmap load(String name) {
        Resources resources = app.getResources();
        int width = resources.getDisplayMetrics().widthPixels;
        if (cached != null && cachedName.equals(name) && cachedWidth == width) return cached;
        int id = resources.getIdentifier(name, "drawable", app.getPackageName());
        if (id == 0) return null;
        BitmapFactory.Options bounds = new BitmapFactory.Options();
        bounds.inJustDecodeBounds = true;
        BitmapFactory.decodeResource(resources, id, bounds);
        BitmapFactory.Options options = new BitmapFactory.Options();
        options.inSampleSize = 1;
        while (bounds.outWidth / (options.inSampleSize * 2) >= width) options.inSampleSize *= 2;
        options.inPreferredConfig = Bitmap.Config.RGB_565;
        Bitmap bitmap = BitmapFactory.decodeResource(resources, id, options);
        if (bitmap == null) return null;
        cached = bitmap;
        cachedSoft = null;
        if (name.equals("frutiger_bg") || name.equals("frutiger_bg_dark")) {
            // blur the aero wallpaper at reduced size to keep old devices within their heap
            cachedSoft = soften(bitmap);
        }
        cachedName = name;
        cachedWidth = width;
        return bitmap;
    }

    private static Bitmap soften(Bitmap bitmap) {
        Bitmap small = Bitmap.createScaledBitmap(bitmap, Math.max(1, bitmap.getWidth() / 24),
                Math.max(1, bitmap.getHeight() / 24), true);
        int width = small.getWidth();
        int height = small.getHeight();
        int[] pixels = new int[width * height];
        int[] blurred = new int[pixels.length];
        small.getPixels(pixels, 0, width, 0, 0, width, height);
        for (int y = 0; y < height; y++) {
            for (int x = 0; x < width; x++) {
                int red = 0, green = 0, blue = 0, count = 0;
                for (int dy = -1; dy <= 1; dy++) {
                    int row = y + dy;
                    if (row < 0 || row >= height) continue;
                    for (int dx = -1; dx <= 1; dx++) {
                        int col = x + dx;
                        if (col < 0 || col >= width) continue;
                        int color = pixels[row * width + col];
                        red += (color >> 16) & 255;
                        green += (color >> 8) & 255;
                        blue += color & 255;
                        count++;
                    }
                }
                blurred[y * width + x] = 0xff000000 | (red / count << 16)
                        | (green / count << 8) | blue / count;
            }
        }
        Bitmap result = Bitmap.createBitmap(width, height, Bitmap.Config.RGB_565);
        result.setPixels(blurred, 0, width, 0, 0, width, height);
        if (small != bitmap) small.recycle();
        return result;
    }

    /** a bitmap scaled to cover its bounds and cropped, like aspect fill */
    private static final class Cover extends Drawable {
        private final Bitmap bitmap;
        private final Paint paint = new Paint(Paint.FILTER_BITMAP_FLAG);
        private final Rect source = new Rect();

        Cover(Bitmap bitmap) {
            this.bitmap = bitmap;
        }

        @Override
        public void draw(Canvas canvas) {
            Rect bounds = getBounds();
            if (bounds.width() <= 0 || bounds.height() <= 0) return;
            float scale = Math.max((float) bounds.width() / bitmap.getWidth(),
                    (float) bounds.height() / bitmap.getHeight());
            int w = Math.min(bitmap.getWidth(), Math.round(bounds.width() / scale));
            int h = Math.min(bitmap.getHeight(), Math.round(bounds.height() / scale));
            int left = (bitmap.getWidth() - w) / 2;
            int top = (bitmap.getHeight() - h) / 2;
            source.set(left, top, left + w, top + h);
            canvas.drawBitmap(bitmap, source, bounds, paint);
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
            return PixelFormat.OPAQUE;
        }
    }
}
