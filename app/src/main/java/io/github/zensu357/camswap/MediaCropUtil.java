package io.github.zensu357.camswap;

import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Rect;
import io.github.zensu357.camswap.utils.VideoManager;

/**
 * Optional center-crop: preserve aspect ratio, fill output and crop symmetric edges.
 * The default mode is the project's existing behavior.
 */
public final class MediaCropUtil {
    private MediaCropUtil() {}

    public static boolean isCenterCrop() {
        return "center_crop".equalsIgnoreCase(
                VideoManager.getConfig().getString("media_fit_mode", ""));
    }

    public static boolean isContain() {
        return "contain".equalsIgnoreCase(
                VideoManager.getConfig().getString("media_fit_mode", ""));
    }

    /** Fit entire source inside output bounds, preserving aspect ratio, with white padding. */
    public static Bitmap contain(Bitmap source, int targetWidth, int targetHeight) {
        if (source == null || targetWidth <= 0 || targetHeight <= 0) return source;
        int sw = source.getWidth(), sh = source.getHeight();
        if (sw <= 0 || sh <= 0 || (sw == targetWidth && sh == targetHeight)) return source;
        double ratio = Math.min((double) targetWidth / sw, (double) targetHeight / sh);
        int dw = Math.max(1, (int) Math.round(sw * ratio));
        int dh = Math.max(1, (int) Math.round(sh * ratio));
        int left = (targetWidth - dw) / 2, top = (targetHeight - dh) / 2;
        Bitmap output = Bitmap.createBitmap(targetWidth, targetHeight, Bitmap.Config.ARGB_8888);
        Canvas canvas = new Canvas(output);
        canvas.drawColor(android.graphics.Color.WHITE);
        canvas.drawBitmap(source, null, new Rect(left, top, left + dw, top + dh),
                new Paint(Paint.FILTER_BITMAP_FLAG | Paint.DITHER_FLAG));
        source.recycle();
        return output;
    }

    /** Ownership of source transfers to caller on no-op, otherwise it is recycled. */
    public static Bitmap centerCrop(Bitmap source, int targetWidth, int targetHeight) {
        if (source == null || targetWidth <= 0 || targetHeight <= 0) return source;
        final int sw = source.getWidth();
        final int sh = source.getHeight();
        if (sw <= 0 || sh <= 0 || (sw == targetWidth && sh == targetHeight)) return source;

        // Calculate the SOURCE rectangle in double precision to avoid integer distortion.
        final double srcAspect = (double) sw / sh;
        final double dstAspect = (double) targetWidth / targetHeight;
        Rect src;
        if (srcAspect > dstAspect) {
            int cw = Math.max(1, Math.min(sw, (int) Math.round(sh * dstAspect)));
            int left = (sw - cw) / 2;
            src = new Rect(left, 0, left + cw, sh);
        } else {
            int ch = Math.max(1, Math.min(sh, (int) Math.round(sw / dstAspect)));
            int top = (sh - ch) / 2;
            src = new Rect(0, top, sw, top + ch);
        }
        Bitmap output = Bitmap.createBitmap(targetWidth, targetHeight, Bitmap.Config.ARGB_8888);
        Canvas canvas = new Canvas(output);
        Paint paint = new Paint(Paint.FILTER_BITMAP_FLAG | Paint.DITHER_FLAG);
        canvas.drawBitmap(source, src, new Rect(0, 0, targetWidth, targetHeight), paint);
        source.recycle();
        return output;
    }

    /** Return UV bounds for aspect-preserving center-crop after a 0/90/180/270 rotation. */
    public static float[] textureUvBounds(int srcW, int srcH, int dstW, int dstH, int rotation) {
        if (srcW <= 0 || srcH <= 0 || dstW <= 0 || dstH <= 0)
            return new float[] {0f, 0f, 1f, 1f};
        double srcAspect = (double) srcW / srcH;
        double dstAspect = (double) dstW / dstH;
        if (Math.floorMod(rotation, 180) == 90) dstAspect = 1.0d / dstAspect;
        float u0 = 0f, v0 = 0f, u1 = 1f, v1 = 1f;
        if (srcAspect > dstAspect) {
            float fraction = (float) (dstAspect / srcAspect);
            u0 = (1f - fraction) * 0.5f;
            u1 = 1f - u0;
        } else {
            float fraction = (float) (srcAspect / dstAspect);
            v0 = (1f - fraction) * 0.5f;
            v1 = 1f - v0;
        }
        return new float[] {u0, v0, u1, v1};
    }
}
