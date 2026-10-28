package io.github.zensu357.camswap;

import android.annotation.SuppressLint;
import android.graphics.ImageFormat;
import android.graphics.Rect;
import android.media.Image;
import android.media.MediaCodec;
import android.media.MediaCodecInfo;
import android.media.MediaExtractor;
import android.media.MediaFormat;
import android.media.MediaMetadataRetriever;
import android.util.Log;
import android.view.Surface;

import java.io.File;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.Arrays;

import io.github.zensu357.camswap.utils.LogUtil;

public class VideoToFrames implements Runnable {
    private static final String TAG = "VideoToFrames";
    private static final long DEFAULT_TIMEOUT_US = 10000;

    private static final int COLOR_FormatI420 = 1;
    private static final int COLOR_FormatNV21 = 2;

    private final int decodeColorFormat = MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420Flexible;

    private LinkedBlockingQueue<byte[]> mQueue;
    private volatile OutputImageFormat outputImageFormat;
    private volatile boolean stopDecode = true;
    private final Object lifecycleLock = new Object();
    private static final long STOP_TIMEOUT_MS = 2000L;
    private int mVideoRotation = 0;

    private String videoFilePath;
    private Throwable throwable;
    private Thread childThread;
    private volatile Surface play_surf;

    private Callback callback;

    public interface Callback {
        void onFinishDecode();

        void onDecodeFrame(int index);
    }

    public void setCallback(Callback callback) {
        this.callback = callback;
    }

    public void setEnqueue(LinkedBlockingQueue<byte[]> queue) {
        mQueue = queue;
    }

    // 设置输出位置，没啥用
    public void setSaveFrames(String dir, OutputImageFormat imageFormat) throws IOException {
        outputImageFormat = imageFormat;

    }

    public void set_surfcae(Surface player_surface) {
        if (player_surface != null) {
            play_surf = player_surface;
        }
    }

    public void stopDecode() {
        if (!stopDecodeAndWaitForShutdown()) {
            LogUtil.log("【CS】【decoder】停止超时；为避免新旧解码线程并行，不启动新的解码任务");
        }
    }

    public boolean stopDecodeAndWaitForShutdown() {
        return stopDecodeAndWait(STOP_TIMEOUT_MS);
    }

    private boolean stopDecodeAndWait(long timeoutMs) {
        final Thread worker;
        synchronized (lifecycleLock) {
            stopDecode = true;
            worker = childThread;
            if (worker == null) return true;
            worker.interrupt();
        }

        if (worker == Thread.currentThread()) {
            return false;
        }

        long deadline = System.currentTimeMillis() + Math.max(0L, timeoutMs);
        while (worker.isAlive()) {
            long remaining = deadline - System.currentTimeMillis();
            if (remaining <= 0L) break;
            try {
                worker.join(remaining);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return false;
            }
        }

        boolean stopped = !worker.isAlive();
        synchronized (lifecycleLock) {
            if (stopped && childThread == worker) childThread = null;
        }
        return stopped;
    }

    public void reset(String videoFilePath) throws Throwable {
        if (!stopDecodeAndWait(STOP_TIMEOUT_MS)) {
            throw new IllegalStateException("Previous decoder thread did not stop; refusing overlapping reset");
        }
        synchronized (lifecycleLock) {
            this.videoFd = null;
            this.videoPfd = null;
            this.videoFilePath = videoFilePath;
            this.throwable = null;
            startWorkerLocked();
        }
    }

    public void reset(android.os.ParcelFileDescriptor pfd) throws Throwable {
        if (pfd == null) throw new IllegalArgumentException("pfd == null");
        if (!stopDecodeAndWait(STOP_TIMEOUT_MS)) {
            throw new IllegalStateException("Previous decoder thread did not stop; refusing overlapping reset");
        }
        synchronized (lifecycleLock) {
            this.videoFilePath = null;
            this.videoPfd = pfd;
            this.videoFd = pfd.getFileDescriptor();
            this.throwable = null;
            startWorkerLocked();
        }
    }

    public void decode(String videoFilePath) throws Throwable {
        synchronized (lifecycleLock) {
            if (childThread != null && childThread.isAlive()) return;
            this.videoFilePath = videoFilePath;
            this.videoFd = null;
            this.videoPfd = null;
            this.throwable = null;
            startWorkerLocked();
        }
    }

    private void startWorkerLocked() {
        stopDecode = false;
        Thread worker = new Thread(this, "CamSwap-VideoDecoder");
        worker.setPriority(Thread.NORM_PRIORITY - 1);
        childThread = worker;
        worker.start();
    }

    /** Publish only while this decoder is still the active producer. */
    private boolean publishFrameIfRunning(byte[] frame, int width, int height) {
        synchronized (lifecycleLock) {
            if (stopDecode || childThread != Thread.currentThread()) return false;
            HookMain.publishFrame(frame, width, height);
            return true;
        }
    }

    private java.io.FileDescriptor videoFd;
    private android.os.ParcelFileDescriptor videoPfd;

    public void decode(android.os.ParcelFileDescriptor pfd) throws Throwable {
        if (pfd == null) throw new IllegalArgumentException("pfd == null");
        synchronized (lifecycleLock) {
            if (childThread != null && childThread.isAlive()) return;
            this.videoPfd = pfd;
            this.videoFd = pfd.getFileDescriptor();
            this.videoFilePath = null;
            this.throwable = null;
            startWorkerLocked();
        }
    }

    public void decode(java.io.FileDescriptor fd) throws Throwable {
        if (fd == null || !fd.valid()) throw new IllegalArgumentException("Invalid file descriptor");
        synchronized (lifecycleLock) {
            if (childThread != null && childThread.isAlive()) return;
            this.videoFd = fd;
            this.videoPfd = null;
            this.videoFilePath = null;
            this.throwable = null;
            startWorkerLocked();
        }
    }

    @Override
    public void run() {
        Thread thisWorker = Thread.currentThread();
        try {
            java.io.FileDescriptor fd;
            String path;
            synchronized (lifecycleLock) {
                fd = videoFd;
                path = videoFilePath;
            }
            if (stopDecode) return;
            if (fd != null) {
                videoDecode(fd);
            } else if (path != null && !path.trim().isEmpty()) {
                videoDecode(path);
            } else {
                throw new IllegalStateException("No video source configured");
            }
        } catch (Throwable t) {
            throwable = t;
            LogUtil.log("【CS】【decoder】解码线程异常: " + t);
        } finally {
            synchronized (lifecycleLock) {
                stopDecode = true;
                if (childThread == thisWorker) childThread = null;
            }
        }
    }

    @SuppressLint("WrongConstant")
    public void videoDecode(java.io.FileDescriptor fd) throws IOException {
        LogUtil.log("【CS】【decoder】开始解码(FD)");
        MediaMetadataRetriever retriever = new MediaMetadataRetriever();
        try {
            retriever.setDataSource(fd);
            String rotation = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_ROTATION);
            if (rotation != null) {
                mVideoRotation = Integer.parseInt(rotation);
                LogUtil.log("【CS】【decoder】视频旋转角度: " + mVideoRotation);
            }
        } catch (Exception e) {
            LogUtil.log("【CS】【decoder】获取视频旋转角度失败: " + e.getMessage());
        } finally {
            try {
                retriever.release();
            } catch (IOException e) {
                e.printStackTrace();
            }
        }
        MediaExtractor extractor = null;
        MediaCodec decoder = null;
        try {
            extractor = new MediaExtractor();
            extractor.setDataSource(fd);
            int trackIndex = selectTrack(extractor);
            if (trackIndex < 0) {
                LogUtil.log("【CS】【decoder】未发现视频轨道(FD)");
                return;
            }
            extractor.selectTrack(trackIndex);
            MediaFormat mediaFormat = extractor.getTrackFormat(trackIndex);
            String mime = mediaFormat.getString(MediaFormat.KEY_MIME);
            decoder = MediaCodec.createDecoderByType(mime);
            showSupportedColorFormat(decoder.getCodecInfo().getCapabilitiesForType(mime));
            if (isColorFormatSupported(decodeColorFormat, decoder.getCodecInfo().getCapabilitiesForType(mime))) {
                mediaFormat.setInteger(MediaFormat.KEY_COLOR_FORMAT, decodeColorFormat);
                LogUtil.log("【CS】【decoder】设置解码颜色格式为 " + decodeColorFormat);
            } else {
                LogUtil.log("【CS】【decoder】无法设置解码颜色格式, 颜色格式类型 " + decodeColorFormat + " 不支持");
                LogUtil.log("【CS】【decoder】unable to set decode color format, color format type " + decodeColorFormat
                        + " not supported");
            }
            // 解码第一帧
            decodeFramesToImage(decoder, extractor, mediaFormat);
            decoder.stop();
            while (!stopDecode) {
                extractor.seekTo(0, 0);
                // 解码后续帧
                decodeFramesToImage(decoder, extractor, mediaFormat);
                decoder.stop();
            }
        } catch (Exception e) {
            LogUtil.log("【CS】【decoder】【videoFD】" + e.toString());
        } finally {
            if (decoder != null) {
                try {
                    decoder.stop();
                } catch (IllegalStateException ignored) {
                }
                decoder.release();
                decoder = null;
            }
            if (extractor != null) {
                extractor.release();
                extractor = null;
            }
            if (videoPfd != null) {
                try {
                    videoPfd.close();
                } catch (IOException e) {
                    e.printStackTrace();
                }
                videoPfd = null;
            }
        }
    }

    @SuppressLint("WrongConstant")
    public void videoDecode(String videoFilePath) throws IOException {
        LogUtil.log("【CS】【decoder】开始解码(" + videoFilePath + ")");
        MediaMetadataRetriever retriever = new MediaMetadataRetriever();
        try {
            retriever.setDataSource(videoFilePath);
            String rotation = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_ROTATION);
            if (rotation != null) {
                mVideoRotation = Integer.parseInt(rotation);
                LogUtil.log("【CS】【decoder】视频旋转角度: " + mVideoRotation);
            }
        } catch (Exception e) {
            LogUtil.log("【CS】【decoder】获取视频旋转角度失败: " + e.getMessage());
        } finally {
            try {
                retriever.release();
            } catch (IOException e) {
                e.printStackTrace();
            }
        }
        MediaExtractor extractor = null;
        MediaCodec decoder = null;
        try {
            File videoFile = new File(videoFilePath);
            extractor = new MediaExtractor();
            extractor.setDataSource(videoFilePath);
            int trackIndex = selectTrack(extractor);
            if (trackIndex < 0) {
                LogUtil.log("【CS】【decoder】未发现视频轨道(" + videoFilePath + ")");
                return;
            }
            extractor.selectTrack(trackIndex);
            MediaFormat mediaFormat = extractor.getTrackFormat(trackIndex);
            String mime = mediaFormat.getString(MediaFormat.KEY_MIME);
            decoder = MediaCodec.createDecoderByType(mime);
            showSupportedColorFormat(decoder.getCodecInfo().getCapabilitiesForType(mime));
            if (isColorFormatSupported(decodeColorFormat, decoder.getCodecInfo().getCapabilitiesForType(mime))) {
                mediaFormat.setInteger(MediaFormat.KEY_COLOR_FORMAT, decodeColorFormat);
                LogUtil.log("【CS】【decoder】设置解码颜色格式为 " + decodeColorFormat);
            } else {
                LogUtil.log("【CS】【decoder】无法设置解码颜色格式，颜色格式类型 " + decodeColorFormat + " 不支持");
                LogUtil.log("【CS】【decoder】unable to set decode color format, color format type " + decodeColorFormat
                        + " not supported");
            }
            decodeFramesToImage(decoder, extractor, mediaFormat);
            decoder.stop();
            while (!stopDecode) {
                extractor.seekTo(0, 0);
                decodeFramesToImage(decoder, extractor, mediaFormat);
                decoder.stop();
            }
        } catch (Exception e) {
            LogUtil.log("【CS】【decoder】【videofile】" + e.toString());
        } finally {
            if (decoder != null) {
                try {
                    decoder.stop();
                } catch (IllegalStateException ignored) {
                }
                decoder.release();
                decoder = null;
            }
            if (extractor != null) {
                extractor.release();
                extractor = null;
            }
        }
    }

    private void showSupportedColorFormat(MediaCodecInfo.CodecCapabilities caps) {
        StringBuilder sb = new StringBuilder("supported color format: ");
        for (int c : caps.colorFormats) {
            sb.append(c).append("\t");
        }
        LogUtil.log("【CS】" + sb.toString());
    }

    private boolean isColorFormatSupported(int colorFormat, MediaCodecInfo.CodecCapabilities caps) {
        for (int c : caps.colorFormats) {
            if (c == colorFormat) {
                return true;
            }
        }
        return false;
    }

    private void decodeFramesToImage(MediaCodec decoder, MediaExtractor extractor, MediaFormat mediaFormat) {
        boolean is_first = false;
        long startWhen = 0;
        MediaCodec.BufferInfo info = new MediaCodec.BufferInfo();

        // 计算初始旋转角度决定是否需要内存解码模式
        int manualOffset = 0;
        try {
            manualOffset = HookMain.getConfig().getInt(ConfigManager.KEY_VIDEO_ROTATION_OFFSET, 0);
        } catch (Exception ignored) {
        }
        int initialEffectiveRotation = (mVideoRotation + manualOffset + 360) % 360;
        boolean needSoftDecode = (initialEffectiveRotation != 0) || outputImageFormat != null;

        // 如果需要旋转或需要填充 data_buffer，则不配置 Surface（走内存解码）
        Surface configSurface = needSoftDecode ? null : play_surf;
        decoder.configure(mediaFormat, configSurface, null, 0);

        boolean sawInputEOS = false;
        boolean sawOutputEOS = false;
        decoder.start();
        final int width = mediaFormat.getInteger(MediaFormat.KEY_WIDTH);
        final int height = mediaFormat.getInteger(MediaFormat.KEY_HEIGHT);
        int outputFrameCount = 0;
        while (!sawOutputEOS && !stopDecode) {
            if (!sawInputEOS) {
                int inputBufferId = decoder.dequeueInputBuffer(DEFAULT_TIMEOUT_US);
                if (inputBufferId >= 0) {
                    ByteBuffer inputBuffer = decoder.getInputBuffer(inputBufferId);
                    int sampleSize = extractor.readSampleData(inputBuffer, 0);
                    if (sampleSize < 0) {
                        decoder.queueInputBuffer(inputBufferId, 0, 0, 0L, MediaCodec.BUFFER_FLAG_END_OF_STREAM);
                        sawInputEOS = true;
                    } else {
                        long presentationTimeUs = extractor.getSampleTime();
                        decoder.queueInputBuffer(inputBufferId, 0, sampleSize, presentationTimeUs, 0);
                        extractor.advance();
                    }
                }
            }
            int outputBufferId = decoder.dequeueOutputBuffer(info, DEFAULT_TIMEOUT_US);
            if (outputBufferId >= 0) {
                if ((info.flags & MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) {
                    sawOutputEOS = true;
                }
                boolean doRender = (info.size != 0);
                if (doRender) {
                    outputFrameCount++;
                    if (callback != null) {
                        callback.onDecodeFrame(outputFrameCount);
                    }
                    if (!is_first) {
                        startWhen = System.currentTimeMillis();
                        is_first = true;
                    }
                    if (configSurface == null) {
                        // 内存解码模式：获取帧数据，做旋转处理
                        Image image = decoder.getOutputImage(outputBufferId);
                        if (image != null) {
                            byte[] rawData = null;
                            byte[] processedData = null;
                            try {
                                // 动态重新计算旋转角度（配置可能被通知栏按钮实时更新）
                                int currentManualOffset = 0;
                                try {
                                    currentManualOffset = HookMain.getConfig().getInt(
                                            ConfigManager.KEY_VIDEO_ROTATION_OFFSET, 0);
                                } catch (Exception ignored) {
                                }
                                int effectiveRotation = (mVideoRotation + currentManualOffset + 360) % 360;

                                boolean needSwapDimensions = (effectiveRotation == 90 || effectiveRotation == 270);
                                int finalWidth = needSwapDimensions ? height : width;
                                int finalHeight = needSwapDimensions ? width : height;

                                rawData = getDataFromImage(image, COLOR_FormatNV21);
                                processedData = rawData;

                                // 1. Rotate only when needed.
                                if (effectiveRotation != 0) {
                                    processedData = rotateNV21(rawData, width, height, effectiveRotation);
                                }

                                // 2. Scale to the camera's active preview dimensions when configured.
                                if (targetWidth > 0 && targetHeight > 0
                                        && (finalWidth != targetWidth || finalHeight != targetHeight)) {
                                    byte[] scaledData = cropAndScaleNV21(processedData, finalWidth, finalHeight,
                                            targetWidth, targetHeight);
                                    if (processedData != rawData) BytePool.release(processedData);
                                    processedData = scaledData;
                                    finalWidth = targetWidth;
                                    finalHeight = targetHeight;
                                }

                                // If stop was requested while this frame was converting, don't leak it downstream.
                                if (!stopDecode) {
                                    // Async queue consumers own a copy, not the scratch array we recycle below.
                                    if (mQueue != null) {
                                        try {
                                            mQueue.put(Arrays.copyOf(processedData, processedData.length));
                                        } catch (InterruptedException e) {
                                            Thread.currentThread().interrupt();
                                            LogUtil.log("【CS】【decoder】帧队列等待被中断");
                                        }
                                    }

                                    if (outputImageFormat != null
                                            && publishFrameIfRunning(processedData, finalWidth, finalHeight)) {
                                        Camera1Handler.notifyPreviewFrameBufferReady();
                                    }

                                    // Rendering is synchronous, so the scratch array remains valid until this returns.
                                    if (play_surf != null && !stopDecode) {
                                        try {
                                            renderNV21ToSurface(processedData, finalWidth, finalHeight, play_surf);
                                        } catch (Exception e) {
                                            LogUtil.log("【CS】渲染到Surface失败: " + e);
                                        }
                                    }
                                }
                            } finally {
                                if (processedData != null && processedData != rawData) {
                                    BytePool.release(processedData);
                                }
                                if (rawData != null) BytePool.release(rawData);
                                image.close();
                            }
                        }
                    }
                    long sleepTime = info.presentationTimeUs / 1000 - (System.currentTimeMillis() - startWhen);
                    if (sleepTime > 0) {
                        try {
                            Thread.sleep(sleepTime);
                        } catch (InterruptedException e) {
                            LogUtil.log("【CS】" + e.toString());
                            LogUtil.log("【CS】线程延迟出错");
                        }
                    }
                    decoder.releaseOutputBuffer(outputBufferId, configSurface != null);
                }
            }
        }
        if (callback != null) {
            callback.onFinishDecode();
        }
    }

    private volatile int targetWidth = 0;
    private volatile int targetHeight = 0;

    public void setTargetSize(int w, int h) {
        this.targetWidth = w;
        this.targetHeight = h;
    }

    private byte[] cropAndScaleNV21(byte[] src, int srcW, int srcH, int dstW, int dstH) {
        float srcAspect = (float) srcW / srcH;
        float dstAspect = (float) dstW / dstH;

        int cropW = srcW;
        int cropH = srcH;
        int cropX = 0;
        int cropY = 0;

        if (srcAspect > dstAspect) {
            // Source is wider than target -> Crop horizontal
            cropW = (int) (srcH * dstAspect);
            cropW = cropW & ~1; // Ensure even
            cropX = (srcW - cropW) / 2;
            cropX = cropX & ~1; // Ensure even
        } else if (srcAspect < dstAspect) {
            // Source is taller than target -> Crop vertical
            cropH = (int) (srcW / dstAspect);
            cropH = cropH & ~1; // Ensure even
            cropY = (srcH - cropH) / 2;
            cropY = cropY & ~1; // Ensure even
        }

        byte[] dst = new byte[dstW * dstH * 3 / 2];

        int scaleX_fp = (cropW << 16) / dstW;
        int scaleY_fp = (cropH << 16) / dstH;

        // Scale Y using Bilinear Interpolation
        for (int y = 0; y < dstH; y++) {
            float sy = cropY + y * (float) cropH / dstH;
            int y0 = (int) sy;
            int y1 = Math.min(y0 + 1, cropY + cropH - 1);
            float dy = sy - y0;
            float dy1 = 1.0f - dy;

            int dstRowOffset = y * dstW;
            int srcRowOffset0 = y0 * srcW;
            int srcRowOffset1 = y1 * srcW;

            for (int x = 0; x < dstW; x++) {
                float sx = cropX + x * (float) cropW / dstW;
                int x0 = (int) sx;
                int x1 = Math.min(x0 + 1, cropX + cropW - 1);
                float dx = sx - x0;
                float dx1 = 1.0f - dx;

                int p00 = src[srcRowOffset0 + x0] & 0xFF;
                int p01 = src[srcRowOffset0 + x1] & 0xFF;
                int p10 = src[srcRowOffset1 + x0] & 0xFF;
                int p11 = src[srcRowOffset1 + x1] & 0xFF;

                float val = (p00 * dx1 + p01 * dx) * dy1 + (p10 * dx1 + p11 * dx) * dy;
                dst[dstRowOffset + x] = (byte) (int) val;
            }
        }

        // Scale UV using Nearest Neighbor (sufficient for chroma and faster)
        int srcUVStart = srcW * srcH;
        int dstUVStart = dstW * dstH;
        for (int y = 0; y < dstH / 2; y++) {
            int sy = (cropY / 2) + ((y * scaleY_fp) >> 16);
            int srcRowOffset = srcUVStart + sy * srcW;
            int dstRowOffset = dstUVStart + y * dstW;
            for (int x = 0; x < dstW / 2; x++) {
                int sx = (cropX / 2) + ((x * scaleX_fp) >> 16);
                int srcColOffset = sx * 2;
                dst[dstRowOffset + x * 2] = src[srcRowOffset + srcColOffset];
                dst[dstRowOffset + x * 2 + 1] = src[srcRowOffset + srcColOffset + 1];
            }
        }
        return dst;
    }

    /**
     * 将 NV21 数据转为 Bitmap 并渲染到指定 Surface。
     * 用于 Camera2 reader 路径在旋转后手动渲染帧。
     */
    private void renderNV21ToSurface(byte[] nv21Data, int width, int height, Surface surface) {
        android.graphics.YuvImage yuvImage = new android.graphics.YuvImage(
                nv21Data, android.graphics.ImageFormat.NV21, width, height, null);
        java.io.ByteArrayOutputStream baos = new java.io.ByteArrayOutputStream();
        yuvImage.compressToJpeg(new android.graphics.Rect(0, 0, width, height), 90, baos);
        byte[] jpegData = baos.toByteArray();
        android.graphics.Bitmap bitmap = android.graphics.BitmapFactory.decodeByteArray(jpegData, 0, jpegData.length);

        if (bitmap != null) {
            android.graphics.Canvas canvas = surface.lockCanvas(null);
            if (canvas != null) {
                // 缩放 bitmap 以填充 canvas
                android.graphics.Rect destRect = new android.graphics.Rect(0, 0, canvas.getWidth(), canvas.getHeight());
                canvas.drawBitmap(bitmap, null, destRect, null);
                surface.unlockCanvasAndPost(canvas);
            }
            bitmap.recycle();
        }
    }

    private byte[] rotateNV21(byte[] input, int width, int height, int rotation) {
        byte[] output = new byte[input.length];
        int frameSize = width * height;
        boolean swap = (rotation == 90 || rotation == 270);

        // 90 degrees CW
        if (rotation == 90) {
            int k = 0;
            // Rotate Y
            for (int x = 0; x < width; x++) {
                for (int y = height - 1; y >= 0; y--) {
                    output[k++] = input[y * width + x];
                }
            }
            // Rotate UV
            for (int x = 0; x < width; x += 2) {
                for (int y = height / 2 - 1; y >= 0; y--) {
                    output[k++] = input[frameSize + y * width + x];
                    output[k++] = input[frameSize + y * width + x + 1];
                }
            }
            return output;
        }

        // 180 degrees
        if (rotation == 180) {
            int k = 0;
            // Rotate Y
            for (int i = frameSize - 1; i >= 0; i--) {
                output[k++] = input[i];
            }
            // Rotate UV
            for (int i = input.length - 2; i >= frameSize; i -= 2) {
                output[k++] = input[i]; // V
                output[k++] = input[i + 1]; // U
            }
            return output;
        }

        // 270 degrees CW (or 90 CCW)
        if (rotation == 270) {
            int k = 0;
            // Rotate Y
            for (int x = width - 1; x >= 0; x--) {
                for (int y = 0; y < height; y++) {
                    output[k++] = input[y * width + x];
                }
            }
            // Rotate UV
            for (int x = width - 2; x >= 0; x -= 2) {
                for (int y = 0; y < height / 2; y++) {
                    output[k++] = input[frameSize + y * width + x];
                    output[k++] = input[frameSize + y * width + x + 1];
                }
            }
            return output;
        }

        return input;
    }

    private static int selectTrack(MediaExtractor extractor) {
        int numTracks = extractor.getTrackCount();
        for (int i = 0; i < numTracks; i++) {
            MediaFormat format = extractor.getTrackFormat(i);
            String mime = format.getString(MediaFormat.KEY_MIME);
            if (mime.startsWith("video/")) {
                return i;
            }
        }
        return -1;
    }

    private static boolean isImageFormatSupported(Image image) {
        int format = image.getFormat();
        switch (format) {
            case ImageFormat.YUV_420_888:
            case ImageFormat.NV21:
            case ImageFormat.YV12:
                return true;
        }
        return false;
    }

    private static byte[] getDataFromImage(Image image, int colorFormat) {
        if (colorFormat != COLOR_FormatI420 && colorFormat != COLOR_FormatNV21) {
            throw new IllegalArgumentException("only support COLOR_FormatI420 " + "and COLOR_FormatNV21");
        }
        if (!isImageFormatSupported(image)) {
            throw new RuntimeException("can't convert Image to byte array, format " + image.getFormat());
        }
        Rect crop = image.getCropRect();
        int format = image.getFormat();
        int width = crop.width();
        int height = crop.height();
        Image.Plane[] planes = image.getPlanes();
        byte[] data = BytePool.acquire(width * height * ImageFormat.getBitsPerPixel(format) / 8);
        byte[] rowData = new byte[planes[0].getRowStride()];
        int channelOffset = 0;
        int outputStride = 1;
        for (int i = 0; i < planes.length; i++) {
            switch (i) {
                case 0:
                    channelOffset = 0;
                    outputStride = 1;
                    break;
                case 1:
                    if (colorFormat == COLOR_FormatI420) {
                        channelOffset = width * height;
                        outputStride = 1;
                    } else if (colorFormat == COLOR_FormatNV21) {
                        channelOffset = width * height + 1;
                        outputStride = 2;
                    }
                    break;
                case 2:
                    if (colorFormat == COLOR_FormatI420) {
                        channelOffset = (int) (width * height * 1.25);
                        outputStride = 1;
                    } else if (colorFormat == COLOR_FormatNV21) {
                        channelOffset = width * height;
                        outputStride = 2;
                    }
                    break;
            }
            ByteBuffer buffer = planes[i].getBuffer();
            int rowStride = planes[i].getRowStride();
            int pixelStride = planes[i].getPixelStride();
            int shift = (i == 0) ? 0 : 1;
            int w = width >> shift;
            int h = height >> shift;
            buffer.position(rowStride * (crop.top >> shift) + pixelStride * (crop.left >> shift));
            for (int row = 0; row < h; row++) {
                int length;
                if (pixelStride == 1 && outputStride == 1) {
                    length = w;
                    buffer.get(data, channelOffset, length);
                    channelOffset += length;
                } else {
                    length = (w - 1) * pixelStride + 1;
                    buffer.get(rowData, 0, length);
                    for (int col = 0; col < w; col++) {
                        data[channelOffset] = rowData[col * pixelStride];
                        channelOffset += outputStride;
                    }
                }
                if (row < h - 1) {
                    buffer.position(buffer.position() + rowStride - length);
                }
            }
        }
        return data;
    }

}

enum OutputImageFormat {
    I420("I420"),
    NV21("NV21"),
    JPEG("JPEG");

    private final String friendlyName;

    OutputImageFormat(String friendlyName) {
        this.friendlyName = friendlyName;
    }

    public String toString() {
        return friendlyName;
    }
}
