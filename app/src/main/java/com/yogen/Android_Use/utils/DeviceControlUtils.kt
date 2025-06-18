package com.yogen.Android_Use.utils

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.media.AudioManager
import android.net.Uri
import android.os.Build
import android.provider.Settings
import android.util.Log
import kotlin.math.roundToInt

object DeviceControlUtils {

    private const val TAG = "DeviceControlUtils"

    // --- Volume Control ---

    fun getAudioStreamType(streamName: String): Int {
        return when (streamName.lowercase()) {
            "music" -> AudioManager.STREAM_MUSIC
            "ring" -> AudioManager.STREAM_RING
            "alarm" -> AudioManager.STREAM_ALARM
            "notification" -> AudioManager.STREAM_NOTIFICATION
            "system" -> AudioManager.STREAM_SYSTEM
            // Add others like STREAM_VOICE_CALL if needed
            else -> -1 // Indicate invalid type
        }
    }

    fun setVolumeLevel(context: Context, streamType: Int, levelPercent: Int): Boolean {
        val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as? AudioManager
        if (audioManager == null) {
            Log.e(TAG, "AudioManager not available")
            return false
        }
        val maxVolume = audioManager.getStreamMaxVolume(streamType)
        if (maxVolume <= 0) {
            Log.e(TAG, "Max volume for stream $streamType is invalid ($maxVolume)")
            return false
        }
        // Calculate target volume index from percentage
        val targetVolume = (maxVolume * levelPercent / 100.0).roundToInt().coerceIn(0, maxVolume)
        return try {
            // Consider using flags like FLAG_SHOW_UI or FLAG_PLAY_SOUND if desired
            audioManager.setStreamVolume(streamType, targetVolume, 0)
            Log.i(TAG, "Set volume for stream $streamType to index $targetVolume ($levelPercent%)")
            true
        } catch (e: Exception) {
            Log.e(TAG, "Exception setting volume for stream $streamType", e)
            false
        }
    }

    fun adjustVolume(context: Context, streamType: Int, direction: Int): Boolean {
        val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as? AudioManager
        if (audioManager == null) {
            Log.e(TAG, "AudioManager not available")
            return false
        }
        return try {
            // Consider using flags like FLAG_SHOW_UI or FLAG_PLAY_SOUND
            audioManager.adjustStreamVolume(streamType, direction, 0)
            val dirStr = if(direction == AudioManager.ADJUST_RAISE) "UP" else "DOWN"
            Log.i(TAG, "Adjusted volume for stream $streamType direction $dirStr")
            true
        } catch (e: Exception) {
            Log.e(TAG, "Exception adjusting volume for stream $streamType", e)
            false
        }
    }

    // --- REMOVE Brightness Control Functions ---

    // --- REMOVE Flashlight Control Functions ---
} 