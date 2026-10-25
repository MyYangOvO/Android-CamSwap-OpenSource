package io.github.zensu357.camswap;

import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Matrix;
import java.io.ByteArrayOutputStream;
import io.github.zensu357.camswap.utils.LogUtil;

/**
 * Optional correction applied ONLY to synthetic still JPEG output.
 * Keeps preview and YUV/QR scanning rotation unchanged.
 * Values: 0, 90, 180, 270 (clockwise degrees in Android bitmap coordinates).
 */
public final class PhotoRotationUtil {
    private PhotoRotationUtil() { }

    private static int configuredDegrees() {
        int value = 0;
        try {
            value = HookMain.getConfig().getInt("photo_rotation_offset", 0);
        } catch (Throwable t) {
            LogUtil.log("【CS】读取照片旋转配置失败: " + t);
        }
        int angle = ((value % 360) + 360) % 360;
        if (angle != 0 && angle != 90 && angle != 180 && angle != 270) {
            LogUtil.log("【CS】photo_rotation_offset 仅支持 0/90/180/270: " + value);
            return 0;
        }
        return angle;
    }

    /** Takes ownership of source: recycled if a rotated bitmap is returned. */
    public static Bitmap rotateBitmapIfNeeded(Bitmap source) {
        if (source == null) return null;
        int degrees = configuredDegrees();
        if (degrees == 0) return source;
        try {
            Matrix matrix = new Matrix();
            matrix.postRotate(degrees);
            Bitmap result = Bitmap.createBitmap(source, 0, 0,
                    source.getWidth(), source.getHeight(), matrix, true);
            if (result != source) source.recycle();
            LogUtil.log("【CS】照片 JPEG 像素旋转: " + degrees + "°");
            return result;
        } catch (Throwable t) {
            LogUtil.log("【CS】照片像素旋转失败, 保留原始照片: " + t);
            return source;
        }
    }

    /** Camera1 produces a JPEG byte array, so decode, rotate and recompress only when enabled. */
    public static byte[] rotateJpegIfNeeded(byte[] jpeg) {
        if (jpeg == null || jpeg.length == 0 || configuredDegrees() == 0) return jpeg;
        Bitmap input = null;
        Bitmap result = null;
        try {
            input = BitmapFactory.decodeByteArray(jpeg, 0, jpeg.length);
            if (input == null) return jpeg;
            result = rotateBitmapIfNeeded(input);
            if (result != input) input = null; // rotateBitmapIfNeeded recycled it.
            ByteArrayOutputStream output = new ByteArrayOutputStream();
            if (!result.compress(Bitmap.CompressFormat.JPEG, 90, output)) return jpeg;
            return output.toByteArray();
        } catch (Throwable t) {
            LogUtil.log("【CS】Camera1 JPEG 旋转失败, 保留原始数据: " + t);
            return jpeg;
        } finally {
            if (result != null && !result.isRecycled()) result.recycle();
            if (input != null && input != result && !input.isRecycled()) input.recycle();
        }
    }
}
