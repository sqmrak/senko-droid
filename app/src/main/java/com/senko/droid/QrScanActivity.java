package com.senko.droid;

import android.Manifest;
import android.app.Activity;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.ImageFormat;
import android.hardware.Camera;
import android.os.Build;
import android.os.Bundle;
import android.view.Gravity;
import android.view.SurfaceHolder;
import android.view.SurfaceView;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.TextView;

import com.google.zxing.BinaryBitmap;
import com.google.zxing.DecodeHintType;
import com.google.zxing.PlanarYUVLuminanceSource;
import com.google.zxing.Reader;
import com.google.zxing.Result;
import com.google.zxing.common.HybridBinarizer;
import com.google.zxing.qrcode.QRCodeReader;

import java.io.IOException;
import java.util.EnumMap;
import java.util.Map;

/**
 * reads one qr code, or a series of amnezia frames, with the camera api of
 * api 16. camera2 does not exist there, and the preview callback hands over
 * the luminance plane that zxing wants anyway
 */
public final class QrScanActivity extends Activity implements SurfaceHolder.Callback, Camera.PreviewCallback {
    static final String EXTRA_TEXT = "text";

    private static final int REQUEST_CAMERA = 7;

    private Camera camera;
    private SurfaceView surface;
    private TextView hint;
    private final Reader reader = new QRCodeReader();
    private final Map<DecodeHintType, Object> hints = new EnumMap<DecodeHintType, Object>(DecodeHintType.class);
    private long series;
    private int parts;
    private boolean finished;
    private volatile boolean decoding;
    private final java.util.concurrent.ExecutorService decoder = java.util.concurrent.Executors.newSingleThreadExecutor();
    private int previewWidth;
    private int previewHeight;

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        hints.put(DecodeHintType.TRY_HARDER, Boolean.TRUE);
        series = Engine.nativeQrNew();
        FrameLayout root = new FrameLayout(this);
        root.setBackgroundColor(0xff000000);
        surface = new SurfaceView(this);
        surface.getHolder().addCallback(this);
        // android before 3.0 shows camera frames only on a push buffers surface
        if (android.os.Build.VERSION.SDK_INT < 11) setPushBuffers(surface.getHolder());
        root.addView(surface, new FrameLayout.LayoutParams(-1, -1));
        hint = new TextView(this);
        hint.setTextColor(0xffffffff);
        hint.setBackgroundColor(0x99000000);
        hint.setGravity(Gravity.CENTER);
        hint.setPadding(Ui.dp(16), Ui.dp(12), Ui.dp(16), Ui.dp(12));
        hint.setText(Tr.t("Point the camera at a QR code"));
        FrameLayout.LayoutParams params = new FrameLayout.LayoutParams(-1, -2, Gravity.BOTTOM);
        root.addView(hint, params);
        setContentView(root);
    }

    @Override
    protected void onDestroy() {
        decoder.shutdownNow();
        try {
            // a frame still being read must not meet a freed series
            decoder.awaitTermination(2, java.util.concurrent.TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        if (series != 0) Engine.nativeQrFree(series);
        series = 0;
        super.onDestroy();
    }

    @Override
    protected void onPause() {
        release();
        super.onPause();
    }

    @Override
    public void surfaceCreated(SurfaceHolder holder) {
        if (Build.VERSION.SDK_INT >= 23 && checkSelfPermission(Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[] {Manifest.permission.CAMERA}, REQUEST_CAMERA);
            return;
        }
        open(holder);
    }

    @Override
    public void onRequestPermissionsResult(int request, String[] permissions, int[] results) {
        if (request != REQUEST_CAMERA) return;
        if (results.length > 0 && results[0] == PackageManager.PERMISSION_GRANTED) open(surface.getHolder());
        else fail("camera access is denied");
    }

    @Override
    public void surfaceChanged(SurfaceHolder holder, int format, int width, int height) {}

    @Override
    public void surfaceDestroyed(SurfaceHolder holder) {
        release();
    }

    private void open(SurfaceHolder holder) {
        if (camera != null || finished) return;
        try {
            camera = Camera.open();
            Camera.Parameters parameters = camera.getParameters();
            Camera.Size size = bestPreviewSize(parameters);
            parameters.setPreviewSize(size.width, size.height);
            previewWidth = size.width;
            previewHeight = size.height;
            fitSurface();
            parameters.setPreviewFormat(ImageFormat.NV21);
            if (parameters.getSupportedFocusModes() != null
                    && parameters.getSupportedFocusModes().contains(Camera.Parameters.FOCUS_MODE_CONTINUOUS_PICTURE))
                parameters.setFocusMode(Camera.Parameters.FOCUS_MODE_CONTINUOUS_PICTURE);
            camera.setParameters(parameters);
            camera.setDisplayOrientation(displayOrientation());
            camera.setPreviewDisplay(holder);
            camera.setPreviewCallback(this);
            camera.startPreview();
        } catch (IOException e) {
            fail("No camera available");
        } catch (RuntimeException e) {
            // Camera.open throws when the device has none or another app holds it
            fail("No camera available");
        }
    }

    /**
     * the preview whose shape is closest to the screen's, and at least 640 wide: the default size is
     * often 4:3 and filled a taller screen, which stretched the picture. a bigger frame costs decode
     * time on slow phones, so the smallest one that is big enough is taken
     */
    private Camera.Size bestPreviewSize(Camera.Parameters parameters) {
        java.util.List<Camera.Size> sizes = parameters.getSupportedPreviewSizes();
        Camera.Size fallback = parameters.getPreviewSize();
        if (sizes == null || sizes.isEmpty()) return fallback;
        android.util.DisplayMetrics metrics = getResources().getDisplayMetrics();
        // the screen is portrait and the sensor landscape, so the long side of the frame is the width
        float want = Math.max(metrics.widthPixels, metrics.heightPixels)
                / (float) Math.min(metrics.widthPixels, metrics.heightPixels);
        Camera.Size best = null;
        float bestGap = Float.MAX_VALUE;
        for (Camera.Size size : sizes) {
            if (size.width < 640 || size.width > 1920) continue;
            float gap = Math.abs(size.width / (float) size.height - want);
            boolean closer = gap < bestGap - 0.02f;
            boolean sameShapeSmaller = best != null && Math.abs(gap - bestGap) <= 0.02f
                    && size.width * size.height < best.width * best.height;
            if (best == null || closer || sameShapeSmaller) {
                best = size;
                bestGap = gap;
            }
        }
        return best == null ? fallback : best;
    }

    /** the sensor of a back camera lies on its side: this turns the preview upright for a portrait screen */
    private int displayOrientation() {
        try {
            Camera.CameraInfo info = new Camera.CameraInfo();
            Camera.getCameraInfo(0, info);
            return info.facing == Camera.CameraInfo.CAMERA_FACING_FRONT ? (360 - info.orientation) % 360 : info.orientation;
        } catch (RuntimeException e) {
            return 90;
        }
    }

    /** the surface keeps the preview's shape and sits in the middle, so nothing is stretched */
    private void fitSurface() {
        View parent = (View) surface.getParent();
        int boxW = parent.getWidth();
        int boxH = parent.getHeight();
        if (boxW <= 0 || boxH <= 0 || previewWidth <= 0 || previewHeight <= 0) return;
        // rotated by the display orientation: the frame's height is the shown width
        float shownW = previewHeight;
        float shownH = previewWidth;
        float scale = Math.min(boxW / shownW, boxH / shownH);
        FrameLayout.LayoutParams params = new FrameLayout.LayoutParams(Math.round(shownW * scale),
                Math.round(shownH * scale), Gravity.CENTER);
        surface.setLayoutParams(params);
    }

    private void release() {
        if (camera == null) return;
        camera.setPreviewCallback(null);
        camera.stopPreview();
        camera.release();
        camera = null;
    }

    private void fail(String english) {
        if (finished) return;
        finished = true;
        hint.setText(Tr.t(english));
        hint.setVisibility(View.VISIBLE);
        setResult(RESULT_CANCELED);
        hint.postDelayed(new Runnable() {
            @Override
            public void run() {
                finish();
            }
        }, 1500);
    }

    @Override
    public void onPreviewFrame(final byte[] data, Camera cam) {
        if (finished || decoding || data == null) return;
        decoding = true;
        // the preview callback runs on the main thread, and a frame with try-harder decoding takes
        // long enough on an old phone to freeze the screen. a frame that arrives meanwhile is
        // dropped by the flag
        final int width = previewWidth;
        final int height = previewHeight;
        decoder.execute(new Runnable() {
            @Override
            public void run() {
                final String text;
                try {
                    text = decode(data, width, height);
                } finally {
                    decoding = false;
                }
                if (text != null) {
                    runOnUiThread(new Runnable() {
                        @Override
                        public void run() {
                            if (!finished) accept(text);
                        }
                    });
                }
            }
        });
    }

    private String decode(byte[] data, int width, int height) {
        PlanarYUVLuminanceSource source = new PlanarYUVLuminanceSource(
                data, width, height, 0, 0, width, height, false);
        try {
            Result result = reader.decode(new BinaryBitmap(new HybridBinarizer(source)), hints);
            return result.getText();
        } catch (com.google.zxing.ReaderException e) {
            // no code in this frame, the next one is tried
            return null;
        } finally {
            reader.reset();
        }
    }

    private void accept(String text) {
        int status = Engine.nativeQrAdd(series, text);
        if (status == 0) {
            finishWith(text);
        } else if (status == 1) {
            parts++;
            hint.setText(Tr.f("Collected %s frames of the profile", String.valueOf(parts)));
        } else if (status == 3) {
            String joined = Engine.nativeQrResult(series);
            if (joined == null || joined.isEmpty()) fail("QR code is empty or unreadable");
            else finishWith(joined);
        } else if (status < 0) {
            fail("QR code is empty or unreadable");
        }
    }

    private void finishWith(String text) {
        if (finished) return;
        finished = true;
        release();
        Intent data = new Intent();
        data.putExtra(EXTRA_TEXT, text);
        setResult(RESULT_OK, data);
        finish();
    }

    @SuppressWarnings("deprecation")
    private static void setPushBuffers(android.view.SurfaceHolder holder) {
        holder.setType(android.view.SurfaceHolder.SURFACE_TYPE_PUSH_BUFFERS);
    }
}
