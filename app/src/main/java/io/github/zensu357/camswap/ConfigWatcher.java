package io.github.zensu357.camswap;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.os.Build;
import android.os.FileObserver;
import android.os.Handler;
import android.os.Looper;

import io.github.zensu357.camswap.utils.LogUtil;
import io.github.zensu357.camswap.utils.VideoManager;

/**
 * Watches for configuration changes via ContentObserver, FileObserver, and
 * BroadcastReceiver, then notifies via {@link Callback}.
 */
public final class ConfigWatcher {

    public interface Callback {
        void onMediaSourceChanged();

        void onRotationChanged(int degrees);
    }

    private final Callback callback;
    private android.database.ContentObserver configObserver;
    private FileObserver configFileObserver;

    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    /** Fingerprint of the media/feature state we last acted on. */
    private volatile String lastActedKey = null;
    private volatile int lastActedRotation = Integer.MIN_VALUE;
    private final Runnable dispatchMediaChanged = new Runnable() {
        @Override
        public void run() {
            try {
                callback.onMediaSourceChanged();
            } catch (Throwable t) {
                LogUtil.log("【CS】onMediaSourceChanged 异常: " + t);
            }
        }
    };

    private static String computeActKey(ConfigManager config) {
        // Media fingerprint + the two switches that change what the hook must do.
        return VideoManager.buildMediaStampKey(config) + "|real=" + config.getBoolean(ConfigManager.KEY_USE_REAL_CAMERA, false)
                + "|filter=" + config.isTargetFilterEnabled()
                + "|targets=" + config.getTargetPackages().hashCode();
    }

    /**
     * Compare the freshly loaded config with what we last acted on and notify at most once.
     * The ContentObserver and the broadcast both fire for one user action; coalesce them so the
     * decoder/players are not torn down twice (the second teardown used to race with the first).
     */
    private void evaluateAndNotify(String why) {
        ConfigManager config = VideoManager.getConfig();
        String newKey = computeActKey(config);
        int newRotation = config.getInt(ConfigManager.KEY_VIDEO_ROTATION_OFFSET, 0);
        boolean mediaChanged = lastActedKey == null || !newKey.equals(lastActedKey);
        boolean rotationChanged = lastActedRotation != Integer.MIN_VALUE && newRotation != lastActedRotation;
        lastActedKey = newKey;
        lastActedRotation = newRotation;
        if (mediaChanged) {
            LogUtil.log("【CS】媒体/开关变化(" + why + ")，重启播放器与解码器");
            mainHandler.removeCallbacks(dispatchMediaChanged);
            mainHandler.postDelayed(dispatchMediaChanged, 60);
        } else if (rotationChanged) {
            LogUtil.log("【CS】旋转变化(" + why + "): " + newRotation + "°");
            callback.onRotationChanged(newRotation);
        } else {
            LogUtil.log("【CS】配置更新(" + why + "): 无需重启");
        }
    }

    public ConfigWatcher(Callback callback) {
        this.callback = callback;
    }

    /**
     * Register all observers and receivers.
     * Must be called from a thread with a Looper (typically main thread).
     */
    public void init(final Context context) {
        if (configObserver != null)
            return; // already initialized

        LogUtil.log("【CS】初始化配置监听");
        ConfigManager baseline = VideoManager.getConfig();
        lastActedKey = computeActKey(baseline);
        lastActedRotation = baseline.getInt(ConfigManager.KEY_VIDEO_ROTATION_OFFSET, 0);
        configObserver = new android.database.ContentObserver(mainHandler) {
            @Override
            public void onChange(boolean selfChange) {
                super.onChange(selfChange);
                LogUtil.log("【CS】Provider 配置变更");
                VideoManager.getConfig().forceReload();
                VideoManager.updateVideoPath(false);
                evaluateAndNotify("observer");
            }
        };

        boolean observerRegistered = false;
        try {
            context.getContentResolver().registerContentObserver(IpcContract.URI_CONFIG, true, configObserver);
            observerRegistered = true;
        } catch (Exception e) {
            LogUtil.log("【CS】注册 ContentObserver 失败: " + e);
        }

        // Fallback: FileObserver when Provider unavailable
        if (!observerRegistered) {
            LogUtil.log("【CS】降级到 FileObserver 监听");
            try {
                String configDir = ConfigManager.DEFAULT_CONFIG_DIR;
                configFileObserver = new FileObserver(configDir,
                        FileObserver.MODIFY | FileObserver.CREATE | FileObserver.MOVED_TO) {
                    @Override
                    public void onEvent(int event, String path) {
                        if (path != null && path.endsWith(".json")) {
                            LogUtil.log("【CS】文件变更: " + path);
                            mainHandler.postDelayed(() -> {
                                VideoManager.getConfig().forceReload();
                                VideoManager.updateVideoPath(false);
                                evaluateAndNotify("file");
                            }, 200);
                        }
                    }
                };
                configFileObserver.startWatching();
                LogUtil.log("【CS】FileObserver 已启动: " + configDir);
            } catch (Exception e) {
                LogUtil.log("【CS】FileObserver 启动失败: " + e);
            }

            // Active Config Request via broadcast
            new Handler(Looper.getMainLooper()).postDelayed(() -> VideoManager.getConfig().requestConfig(context),
                    1000);
        }

        // BroadcastReceiver for control signals & configuration updates
        registerBroadcastReceiver(context);
    }

    private void registerBroadcastReceiver(final Context context) {
        try {
            BroadcastReceiver receiver = new BroadcastReceiver() {
                @Override
                public void onReceive(Context ctx, Intent intent) {
                    String action = intent.getAction();
                    if (IpcContract.ACTION_UPDATE_CONFIG.equals(action)) {
                        handleConfigUpdate(intent);
                    }
                }
            };
            IntentFilter filter = new IntentFilter();
            filter.addAction(IpcContract.ACTION_UPDATE_CONFIG);
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                context.registerReceiver(receiver, filter, Context.RECEIVER_EXPORTED);
            } else {
                context.registerReceiver(receiver, filter);
            }
            LogUtil.log("【CS】广播接收器已注册 (RECEIVER_EXPORTED)");
        } catch (Exception e) {
            LogUtil.log("【CS】注册广播接收器失败: " + e);
        }
    }

    private void handleConfigUpdate(Intent intent) {
        ConfigManager config = VideoManager.getConfig();
        String configJson = intent.getStringExtra(IpcContract.EXTRA_CONFIG_JSON);
        if (configJson == null)
            return;

        // A late/out-of-order broadcast must never roll the config back to an older media selection.
        if (!config.updateConfigFromJSON(configJson)) {
            return;
        }
        // The broadcast carries no provider-side media stamp; pull the authoritative snapshot
        // (provider is always fresh now) so the change key is complete and we restart only once.
        try {
            config.forceReload();
        } catch (Throwable t) {
            LogUtil.log("【CS】广播后刷新配置失败: " + t);
        }

        // Pull the video bytes through the Binder only when the media actually changed (or we have no
        // private copy yet); doing it on every broadcast is wasteful and widens the race window.
        boolean keyChanged = lastActedKey == null || !computeActKey(config).equals(lastActedKey);
        boolean haveBundle = intent.hasExtra(IpcContract.EXTRA_VIDEO_BUNDLE);
        if (haveBundle && (keyChanged || !hasPrivateCopy())) {
            extractVideoFromBinder(intent);
        }

        VideoManager.updateVideoPath(false);
        evaluateAndNotify("broadcast");
    }

    private boolean hasPrivateCopy() {
        try {
            android.content.Context c = HookMain.toast_content;
            if (c == null) return false;
            java.io.File f = new java.io.File(c.getFilesDir(), "vcam_private.mp4");
            return f.exists() && f.length() > 0;
        } catch (Throwable t) {
            return false;
        }
    }

    private void extractVideoFromBinder(Intent intent) {
        android.os.Bundle bundle = intent.getBundleExtra(IpcContract.EXTRA_VIDEO_BUNDLE);
        if (bundle == null) {
            return;
        }
        android.os.IBinder binder = bundle.getBinder(IpcContract.EXTRA_VIDEO_BINDER);
        if (binder == null) {
            LogUtil.log("【CS】video_binder 为 null");
            return;
        }
        android.os.Parcel data = android.os.Parcel.obtain();
        android.os.Parcel reply = android.os.Parcel.obtain();
        try {
            binder.transact(1, data, reply, 0);
            reply.readException();
            int hasFd = reply.readInt();
            if (hasFd != 0) {
                android.os.ParcelFileDescriptor pfd = android.os.ParcelFileDescriptor.CREATOR.createFromParcel(reply);
                if (pfd != null) {
                    LogUtil.log("【CS】Binder 视频 FD 已获取，拷贝到私有目录");
                    VideoManager.copyToPrivateDir(pfd);
                    pfd.close();
                } else {
                    LogUtil.log("【CS】Binder PFD 为 null");
                }
            }
        } catch (Exception e) {
            LogUtil.log("【CS】Binder 获取 FD 失败: " + e);
        } finally {
            data.recycle();
            reply.recycle();
        }
    }
}
