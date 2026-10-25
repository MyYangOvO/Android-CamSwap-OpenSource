package io.github.zensu357.camswap.ui

import android.app.Application
import android.content.Context
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import android.widget.Toast
import io.github.zensu357.camswap.ConfigManager
import io.github.zensu357.camswap.R
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

data class MainUiState(
    val isModuleDisabled: Boolean = false,
    val useRealCamera: Boolean = false,
    val playVideoSound: Boolean = false,
    val forcePrivateDir: Boolean = false,
    val enableQrScanCompat: Boolean = false,
    val disableToast: Boolean = false,
    val enableRandomPlay: Boolean = false,
    val enableMicHook: Boolean = false,
    val micHookMode: String = "mute",
    val enablePhotoFake: Boolean = false,
    val photoRotationOffset: Int = 0,
    val mediaFitMode: String = "original",

    val notificationControlEnabled: Boolean = false,
    val overlayControlEnabled: Boolean = false,
    val hasPermission: Boolean = false,
    val isXposedActive: Boolean = false,
    val targetAppsCount: Int = 0,
    val originalVideoName: String? = null,
    val latestVersion: String? = null,

    // Stream mode
    val mediaSourceType: String = ConfigManager.MEDIA_SOURCE_LOCAL,
    val streamUrl: String = "",
    val streamAutoReconnect: Boolean = true,
    val streamLocalFallback: Boolean = true,
    val streamTransportHint: String = "auto",
    val streamTimeoutMs: Long = 8000L
)

class MainViewModel(application: Application) : AndroidViewModel(application) {
    private val configManager = ConfigManager()
    private val _uiState = MutableStateFlow(MainUiState())
    val uiState: StateFlow<MainUiState> = _uiState.asStateFlow()

    init {
        configManager.setContext(application.applicationContext)
        loadConfig()
        checkLatestVersion()
    }

    fun loadConfig() {
        viewModelScope.launch(Dispatchers.IO) {
            configManager.reload()
            
            _uiState.update { currentState ->
                currentState.copy(
                    isModuleDisabled = configManager.getBoolean(ConfigManager.KEY_DISABLE_MODULE, false),
                    useRealCamera = configManager.getBoolean(ConfigManager.KEY_USE_REAL_CAMERA, false),
                    playVideoSound = configManager.getBoolean(ConfigManager.KEY_PLAY_VIDEO_SOUND, false),
                    forcePrivateDir = configManager.getBoolean(ConfigManager.KEY_FORCE_PRIVATE_DIR, false),
                    enableQrScanCompat = configManager.getBoolean(ConfigManager.KEY_ENABLE_QR_SCAN_COMPAT, false),
                    disableToast = configManager.getBoolean(ConfigManager.KEY_DISABLE_TOAST, false),
                    enableRandomPlay = configManager.getBoolean(ConfigManager.KEY_ENABLE_RANDOM_PLAY, false),
                    enableMicHook = configManager.getBoolean(ConfigManager.KEY_ENABLE_MIC_HOOK, false),
                    micHookMode = configManager.getString(ConfigManager.KEY_MIC_HOOK_MODE, ConfigManager.MIC_MODE_MUTE),
                                         enablePhotoFake = configManager.getBoolean(ConfigManager.KEY_ENABLE_PHOTO_FAKE, false),
                     photoRotationOffset = configManager.getInt(ConfigManager.KEY_PHOTO_ROTATION_OFFSET, 0),
                     mediaFitMode = configManager.getString(ConfigManager.KEY_MEDIA_FIT_MODE, "original"),
                    notificationControlEnabled = configManager.getBoolean(ConfigManager.KEY_NOTIFICATION_CONTROL_ENABLED, false),
                    overlayControlEnabled = configManager.getBoolean(ConfigManager.KEY_OVERLAY_CONTROL_ENABLED, false),
                    targetAppsCount = configManager.targetPackages.size,
                    originalVideoName = configManager.getString(ConfigManager.KEY_ORIGINAL_VIDEO_NAME, null),
                    // Stream config
                    mediaSourceType = configManager.getString(ConfigManager.KEY_MEDIA_SOURCE_TYPE, ConfigManager.MEDIA_SOURCE_LOCAL),
                    streamUrl = configManager.getString(ConfigManager.KEY_STREAM_URL, ""),
                    streamAutoReconnect = configManager.getBoolean(ConfigManager.KEY_STREAM_AUTO_RECONNECT, true),
                    streamLocalFallback = configManager.getBoolean(ConfigManager.KEY_STREAM_LOCAL_FALLBACK, true),
                    streamTransportHint = configManager.getString(ConfigManager.KEY_STREAM_TRANSPORT_HINT, "auto"),
                    streamTimeoutMs = configManager.getLong(ConfigManager.KEY_STREAM_TIMEOUT_MS, 8000L)
                )
            }
        }
    }

    fun setModuleDisabled(disabled: Boolean) {
        viewModelScope.launch(Dispatchers.IO) {
            configManager.setBoolean(ConfigManager.KEY_DISABLE_MODULE, disabled)
            _uiState.update { it.copy(isModuleDisabled = disabled) }
        }
    }

    fun setUseRealCamera(enabled: Boolean) {
        viewModelScope.launch(Dispatchers.IO) {
            if (configManager.setBooleanChecked(ConfigManager.KEY_USE_REAL_CAMERA, enabled)) {
                _uiState.update { it.copy(useRealCamera = enabled) }
            } else {
                withContext(Dispatchers.Main) {
                    Toast.makeText(getApplication<Application>(), R.string.settings_real_camera_save_failed, Toast.LENGTH_LONG).show()
                }
            }
        }
    }

    fun setPlayVideoSound(enabled: Boolean) {
        viewModelScope.launch(Dispatchers.IO) {
            configManager.setBoolean(ConfigManager.KEY_PLAY_VIDEO_SOUND, enabled)
            _uiState.update { it.copy(playVideoSound = enabled) }
        }
    }

    fun setEnableQrScanCompat(enabled: Boolean) {
        viewModelScope.launch(Dispatchers.IO) {
            configManager.setBoolean(ConfigManager.KEY_ENABLE_QR_SCAN_COMPAT, enabled)
            _uiState.update { it.copy(enableQrScanCompat = enabled) }
        }
    }

    fun setForcePrivateDir(enabled: Boolean) {
        viewModelScope.launch(Dispatchers.IO) {
            configManager.setBoolean(ConfigManager.KEY_FORCE_PRIVATE_DIR, enabled)
            _uiState.update { it.copy(forcePrivateDir = enabled) }
        }
    }

    fun setDisableToast(disabled: Boolean) {
        viewModelScope.launch(Dispatchers.IO) {
            configManager.setBoolean(ConfigManager.KEY_DISABLE_TOAST, disabled)
            _uiState.update { it.copy(disableToast = disabled) }
        }
    }

    fun setEnableRandomPlay(enabled: Boolean) {
        viewModelScope.launch(Dispatchers.IO) {
            configManager.setBoolean(ConfigManager.KEY_ENABLE_RANDOM_PLAY, enabled)
            _uiState.update { it.copy(enableRandomPlay = enabled) }
        }
    }

    fun setEnableMicHook(enabled: Boolean) {
        viewModelScope.launch(Dispatchers.IO) {
            configManager.setBoolean(ConfigManager.KEY_ENABLE_MIC_HOOK, enabled)
            _uiState.update { it.copy(enableMicHook = enabled) }
        }
    }

    fun setMicHookMode(mode: String) {
        viewModelScope.launch(Dispatchers.IO) {
            configManager.setString(ConfigManager.KEY_MIC_HOOK_MODE, mode)
            _uiState.update { it.copy(micHookMode = mode) }
        }
    }

    fun setEnablePhotoFake(enabled: Boolean) {
        viewModelScope.launch(Dispatchers.IO) {
            configManager.setBoolean(ConfigManager.KEY_ENABLE_PHOTO_FAKE, enabled)
            _uiState.update { it.copy(enablePhotoFake = enabled) }
        }
    }

    fun setNotificationControlEnabled(enabled: Boolean) {
        viewModelScope.launch(Dispatchers.IO) {
            configManager.setBoolean(ConfigManager.KEY_NOTIFICATION_CONTROL_ENABLED, enabled)
            _uiState.update { it.copy(notificationControlEnabled = enabled) }
        }
    }

    fun setOverlayControlEnabled(enabled: Boolean) {
        viewModelScope.launch(Dispatchers.IO) {
            configManager.setBoolean(ConfigManager.KEY_OVERLAY_CONTROL_ENABLED, enabled)
            _uiState.update { it.copy(overlayControlEnabled = enabled) }
        }
    }

    /** Save a photo-only rotation; preview and QR orientation do not change. */
    fun setPhotoRotationOffset(degrees: Int) {
        if (degrees !in listOf(0, 90, 180, 270)) return
        // Update screen immediately, then persist in the background.
        _uiState.update { it.copy(photoRotationOffset = degrees) }
        viewModelScope.launch(Dispatchers.IO) {
            configManager.setInt(ConfigManager.KEY_PHOTO_ROTATION_OFFSET, degrees)
        }
    }

    fun setMediaFitMode(mode: String) {
        if (mode !in listOf("original", "center_crop", "contain")) return
        _uiState.update { it.copy(mediaFitMode = mode) }
        viewModelScope.launch(Dispatchers.IO) {
            configManager.setString(ConfigManager.KEY_MEDIA_FIT_MODE, mode)
        }
    }

    // ---- Stream config setters ----

    fun setMediaSourceType(type: String) {
        viewModelScope.launch(Dispatchers.IO) {
            configManager.setString(ConfigManager.KEY_MEDIA_SOURCE_TYPE, type)
            _uiState.update { it.copy(mediaSourceType = type) }
        }
    }

    fun setStreamUrl(url: String) {
        viewModelScope.launch(Dispatchers.IO) {
            configManager.setString(ConfigManager.KEY_STREAM_URL, url)
            _uiState.update { it.copy(streamUrl = url) }
        }
    }

    fun setStreamAutoReconnect(enabled: Boolean) {
        viewModelScope.launch(Dispatchers.IO) {
            configManager.setBoolean(ConfigManager.KEY_STREAM_AUTO_RECONNECT, enabled)
            _uiState.update { it.copy(streamAutoReconnect = enabled) }
        }
    }

    fun setStreamLocalFallback(enabled: Boolean) {
        viewModelScope.launch(Dispatchers.IO) {
            configManager.setBoolean(ConfigManager.KEY_STREAM_LOCAL_FALLBACK, enabled)
            _uiState.update { it.copy(streamLocalFallback = enabled) }
        }
    }

    fun setStreamTransportHint(hint: String) {
        viewModelScope.launch(Dispatchers.IO) {
            configManager.setString(ConfigManager.KEY_STREAM_TRANSPORT_HINT, hint)
            _uiState.update { it.copy(streamTransportHint = hint) }
        }
    }

    fun setStreamTimeoutMs(timeout: Long) {
        viewModelScope.launch(Dispatchers.IO) {
            configManager.setLong(ConfigManager.KEY_STREAM_TIMEOUT_MS, timeout)
            _uiState.update { it.copy(streamTimeoutMs = timeout) }
        }
    }



    fun updatePermissionStatus(hasPermission: Boolean) {
        _uiState.update { it.copy(hasPermission = hasPermission) }
    }

    fun updateXposedStatus(isActive: Boolean) {
        _uiState.update { it.copy(isXposedActive = isActive) }
    }

    fun setLanguage(context: Context, language: String) {
        io.github.zensu357.camswap.utils.LocaleHelper.setLocale(context, language)
        // Restart app to apply changes
        val intent = context.packageManager.getLaunchIntentForPackage(context.packageName)
        intent?.addFlags(android.content.Intent.FLAG_ACTIVITY_CLEAR_TOP or android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
        context.startActivity(intent)
    }

    private fun checkLatestVersion() {
        _uiState.update { it.copy(latestVersion = null) }
    }
}
