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
    private final Handler refreshHandler = new Handler(Looper.getMainLooper());
    private static final long SWITCH_SETTLE_MS = 180L;
    private String lastAppliedMediaKey;
    private int lastAppliedRotation;

    // ContentObserver, file changes, and broadcasts can report the SAME write.
    // Coalesce them and reload the latest state once rather than restarting a
    // decoder three times while another thread is filling the QR cache.
    private final Runnable applyPendingChange = new Runnable() {
        @Override public void run() {
            try {
                ConfigManager config = VideoManager.getConfig();
                config.forceReload();
                String mediaKey = getMediaKey(config);
                int rotation = config.getInt(ConfigManager.KEY_VIDEO_ROTATION_OFFSET, 0);
                if (!mediaKey.equals(lastAppliedMediaKey)) {
                    lastAppliedMediaKey = mediaKey;
                    lastAppliedRotation = rotation;
                    VideoManager.updateVideoPath(false);
                    callback.onMediaSourceChanged();
                    LogUtil.log("【CS】【HotSwap】媒体源切换已提交");
                } else if (rotation != lastAppliedRotation) {
                    lastAppliedRotation = rotation;
                    callback.onRotationChanged(rotation);
                }
            } catch (Throwable error) {
                LogUtil.log("【CS】【HotSwap】配置同步失败: " + error);
            }
        }
    };

    private static String getMediaKey(ConfigManager c) {
        return c.getString(ConfigManager.KEY_SELECTED_VIDEO, "") + "|"
                + c.getString(ConfigManager.KEY_SELECTED_IMAGE, "") + "|"
                + c.getString(ConfigManager.KEY_REPLACE_MODE, ConfigManager.REPLACE_MODE_VIDEO) + "|"
                + c.getBoolean(ConfigManager.KEY_FORCE_PRIVATE_DIR, false) + "|"
                + c.getString(ConfigManager.KEY_MEDIA_SOURCE_TYPE, ConfigManager.MEDIA_SOURCE_LOCAL) + "|"
                + c.getString(ConfigManager.KEY_STREAM_URL, "") + "|"
                + c.getBoolean(ConfigManager.KEY_ENABLE_QR_SCAN_COMPAT, false) + "|"
                + c.getBoolean(ConfigManager.KEY_USE_REAL_CAMERA, false) + "|"
                + c.getString(ConfigManager.KEY_MEDIA_FIT_MODE, "");
    }

    private void scheduleRefresh() {
        refreshHandler.removeCallbacks(applyPendingChange);
        refreshHandler.postDelayed(applyPendingChange, SWITCH_SETTLE_MS);
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
        // Establish baseline: settings-only broadcasts should not restart video.
        ConfigManager initial = VideoManager.getConfig();
        initial.forceReload();
        lastAppliedMediaKey = getMediaKey(initial);
        lastAppliedRotation = initial.getInt(ConfigManager.KEY_VIDEO_ROTATION_OFFSET, 0);
        configObserver = new android.database.ContentObserver(new Handler(Looper.getMainLooper())) {
            @Override
            public void onChange(boolean selfChange) {
                super.onChange(selfChange);
                LogUtil.log("【CS】Provider 配置变更");
                scheduleRefresh();
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
                            scheduleRefresh();
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

        // A broadcast is a second signal for the same persisted change. The
        // provider/file reload in applyPendingChange wins over any old broadcast.
        config.updateConfigFromJSON(configJson);
        if (config.getBoolean(ConfigManager.KEY_FORCE_PRIVATE_DIR, false)
                && !getMediaKey(config).equals(lastAppliedMediaKey)
                && intent.hasExtra(IpcContract.EXTRA_VIDEO_BUNDLE)) {
            extractVideoFromBinder(intent);
        }
        scheduleRefresh();
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
