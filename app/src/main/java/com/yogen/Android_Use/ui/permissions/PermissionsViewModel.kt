package com.yogen.Android_Use.ui.permissions

import android.app.Application
import android.content.Context
import android.content.Intent
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.provider.Settings
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.yogen.Android_Use.utils.AccessibilityUtils
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class PermissionsUiState(
    val isAccessibilityServiceEnabled: Boolean = false,
    val hasScreenCapturePermission: Boolean = false // We track if the intent was successful, not long term perm
)

class PermissionsViewModel(application: Application) : AndroidViewModel(application) {

    private val _uiState = MutableStateFlow(PermissionsUiState())
    val uiState: StateFlow<PermissionsUiState> = _uiState.asStateFlow()

    init {
        checkInitialPermissions()
    }

    fun checkInitialPermissions() {
        viewModelScope.launch {
            val context = getApplication<Application>().applicationContext
            val accessibilityEnabled = AccessibilityUtils.isAccessibilityServiceEnabled(context)
            // Note: Screen Capture permission is granted temporarily via MediaProjection API,
            // so we don't have a persistent status to check here initially.
            // We only know if the user *granted* it during the request flow.
            _uiState.update {
                it.copy(isAccessibilityServiceEnabled = accessibilityEnabled)
            }
        }
    }

    // Function called when user grants/denies screen capture via ActivityResult
    fun updateScreenCapturePermissionStatus(granted: Boolean) {
         _uiState.update {
             it.copy(hasScreenCapturePermission = granted)
         }
    }

    // --- Intent Getters for Fragment ---

    fun getAccessibilitySettingsIntent(): Intent {
        return Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)
    }

    fun getScreenCaptureIntent(context: Context): Intent? {
        val mediaProjectionManager = context.getSystemService(Context.MEDIA_PROJECTION_SERVICE) as? MediaProjectionManager
        return mediaProjectionManager?.createScreenCaptureIntent()
    }
} 