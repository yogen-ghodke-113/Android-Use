package com.yogen.Android_Use.screenshot // Correct package

import android.app.*
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.PixelFormat // Needed for ImageReader format
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.Image
import android.media.ImageReader
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Handler // Needed for MediaProjection callback handler
import android.os.HandlerThread // Needed for background handler thread
import android.os.IBinder
import android.util.Base64
import android.util.DisplayMetrics
import android.util.Log
import android.view.Display // Needed for context.display
import android.view.WindowManager // Needed for windowManager
import com.yogen.Android_Use.R // Assuming R file is correctly located
import com.yogen.Android_Use.api.ApiClient // Use concrete class if getInstance returns it
//import com.yogen.Android_Use.api.ApiClientInterface // Keep interface if used elsewhere
import com.yogen.Android_Use.app.AndroidUseApplication // **ADDED IMPORT**
import kotlinx.coroutines.*
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer // Needed for image buffer access
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.reflect.KProperty // **ADDED IMPORT**
import android.app.Activity.RESULT_OK // Explicit import for clarity
import android.os.Parcelable // Needed for generic Parcelable retrieval

class ScreenCaptureService : Service() {

    companion object {
        private const val TAG = "ScreenCaptureService"
        private const val NOTIFICATION_ID = 123
        private const val NOTIFICATION_CHANNEL_ID = "ScreenCaptureChannel"
        private const val VIRTUAL_DISPLAY_NAME = "AndroidUseScreenCapture"
        const val EXTRA_RESULT_CODE = "resultCode"
        const val EXTRA_DATA = "data"
        // Ensure this matches ApiClient constant
        const val EXTRA_CORRELATION_ID = "correlation_id"
        // Add the missing action constants for broadcasts
        const val ACTION_CAPTURE = "com.yogen.Android_Use.ACTION_CAPTURE_SCREENSHOT"
        const val ACTION_SCREENSHOT_TAKEN = "com.yogen.Android_Use.ACTION_SCREENSHOT_TAKEN"
        const val EXTRA_SCREENSHOT_SUCCESS = "screenshot_success"
        const val EXTRA_SCREENSHOT_ERROR = "screenshot_error"
    }

    private lateinit var mediaProjectionManager: MediaProjectionManager
    private var mediaProjection: MediaProjection? = null
    private var virtualDisplay: VirtualDisplay? = null
    private var imageReader: ImageReader? = null
    private var windowManager: WindowManager? = null
    private var screenWidth: Int = 0
    private var screenHeight: Int = 0
    private var screenDensity: Int = 0
    @Volatile private var isProjectionRunning = false // Flag to track projection state

    // Background thread for ImageReader callbacks and projection stop callback
    private var handlerThread: HandlerThread? = null
    private var backgroundHandler: Handler? = null

    // Coroutine scope for background tasks like image processing
    // Use SupervisorJob to prevent one task failure from cancelling others
    private val serviceJob = SupervisorJob()
    private val serviceScope = CoroutineScope(Dispatchers.IO + serviceJob)

    // API Client instance - using singleton pattern via Application class
    private val apiClient: ApiClient by lazy {
        // Access applicationContext directly within the Service
        (applicationContext as? AndroidUseApplication)?.apiClient
            ?: throw IllegalStateException("ApiClient not available via Application context. Ensure initialization.")
    }

    // To store the correlation ID for the *current* screenshot request being processed
    @Volatile private var currentCorrelationId: String? = null

    // Flag to prevent concurrent screenshot processing attempts
    private val isProcessingScreenshot = AtomicBoolean(false)

    // --- Store initial projection data ---
    private var initialResultCode: Int = Activity.RESULT_CANCELED
    private var initialData: Intent? = null
    // ---

    // Flag to prevent concurrent START attempts
    private val isStartingProjection = AtomicBoolean(false)

    override fun onCreate() {
        super.onCreate()
        Log.d(TAG, "Service onCreate starting.")
        mediaProjectionManager = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        windowManager = getSystemService(Context.WINDOW_SERVICE) as WindowManager
        updateScreenMetrics()
        startImageReaderThread() // Start background thread for handlers
        createNotificationChannel()
        Log.d(TAG, "Service onCreate finished.")
    }

     private fun startImageReaderThread() {
         if (handlerThread?.isAlive != true) {
             handlerThread = HandlerThread("ScreenCapture_HandlerThread").apply { start() }
             backgroundHandler = Handler(handlerThread!!.looper)
             Log.d(TAG, "Background handler thread started.")
         } else {
              Log.d(TAG, "Background handler thread already running.")
         }
     }

    private fun stopImageReaderThread() {
        Log.d(TAG, "Stopping background handler thread...")
        handlerThread?.quitSafely()
        try {
            Log.d(TAG, "Waiting up to 500ms for handler thread to join...")
            handlerThread?.join(500)
            Log.d(TAG, "Handler thread joined or timeout reached.")
        } catch (e: InterruptedException) {
            Thread.currentThread().interrupt()
            Log.e(TAG, "Interrupted while waiting for handler thread to join", e)
        }
        handlerThread = null
        backgroundHandler = null
        Log.d(TAG, "Background handler thread stopped.")
    }

    private fun updateScreenMetrics() {
        val metrics = DisplayMetrics()
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                val display = getSystemService(DisplayManager::class.java)?.getDisplay(Display.DEFAULT_DISPLAY)
                display?.getRealMetrics(metrics)
            } else {
                @Suppress("DEPRECATION")
                windowManager?.defaultDisplay?.getRealMetrics(metrics)
            }
            screenWidth = metrics.widthPixels
            screenHeight = metrics.heightPixels
            screenDensity = metrics.densityDpi
            Log.d(TAG, "Screen metrics updated: ${screenWidth}x$screenHeight @ ${screenDensity}dpi")
        } catch (e: Exception) {
            Log.e(TAG, "Error updating screen metrics", e)
            // Use default or last known values? Handle error appropriately.
        }
    }


    // Handle both initial start and subsequent requests via intent
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        Log.d(TAG, "onStartCommand received action: ${intent?.action}, flags: $flags, startId: $startId")

        val notification = createNotification()
        startForeground(NOTIFICATION_ID, notification)
        Log.d(TAG, "Service started in foreground.")

        val correlationIdFromIntent = intent?.getStringExtra(EXTRA_CORRELATION_ID)
        if (correlationIdFromIntent != null) {
            // Store the latest request ID. takeScreenshot will use this captured value.
            currentCorrelationId = correlationIdFromIntent
            Log.d(TAG, "Received capture request with correlationId: $currentCorrelationId")
        }

        val resultCode = intent?.getIntExtra(EXTRA_RESULT_CODE, Activity.RESULT_CANCELED)
        val data: Intent? = intent?.safeGetParcelableExtra(EXTRA_DATA) // Use safe retrieval

        var needsProjectionStart = false
        if (resultCode == RESULT_OK && data != null) {
             Log.i(TAG, "Valid projection start data found in intent.")
             // Store the valid data if we haven't already or if projection stopped
             if (initialData == null || !isProjectionRunning) {
                 Log.d(TAG, "Storing initial projection data.")
                 initialResultCode = resultCode
                 initialData = data // Store the valid intent data
             }
             needsProjectionStart = true
         } else if (intent?.hasExtra(EXTRA_RESULT_CODE) == true) {
             // We received projection data, but it wasn't RESULT_OK
             Log.e(TAG, "MediaProjection permission not granted or data missing (resultCode=$resultCode). Stopping service if not running.")
              // If we had a correlation ID for this failed attempt, notify the server
              apiClient.sendScreenshotResult(null, correlationIdFromIntent, false, "MediaProjection permission denied or failed.")
             if (!isProjectionRunning) { // Only stop if we failed during initial startup
                 stopSelf()
                 return START_NOT_STICKY
             }
              // If projection was already running, denial might be for a re-request, don't stop.
         }


        // --- Start Projection or Trigger Screenshot ---
        if (needsProjectionStart && !isProjectionRunning) {
            // Got valid start data, and projection isn't running
            if (isStartingProjection.compareAndSet(false, true)) {
                 Log.i(TAG, "Attempting to start projection with stored initial data...")
                 // Use the stored initial data, NOT the potentially old data from this specific intent
                 startProjection(initialResultCode, initialData)
                 isStartingProjection.set(false) // Release lock after attempt (success or fail)
             } else {
                 Log.w(TAG, "Projection start already in progress, ignoring duplicate start trigger.")
             }
        } else if (correlationIdFromIntent != null) {
            // We have a capture request (new or existing projection)
            if (isProjectionRunning) {
                 Log.d(TAG,"Projection running, triggering takeScreenshot for request ID: $correlationIdFromIntent")
                 takeScreenshot()
             } else {
                 Log.w(TAG, "Capture request $correlationIdFromIntent received, but projection is not running. Waiting for start.")
                 // Send error back immediately? Or rely on timeout?
                 // Sending error is safer.
                 apiClient.sendScreenshotResult(null, correlationIdFromIntent, false, "Projection not active. Cannot capture.")
                 // Do NOT reset currentCorrelationId here, keep the latest requested ID.
             }
        } else {
             Log.d(TAG, "onStartCommand finished without needing to start projection or take screenshot.")
         }


        return START_STICKY
    }

    // Helper for safe Parcelable retrieval
    inline fun <reified T : Parcelable> Intent.safeGetParcelableExtra(key: String): T? = when {
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU -> getParcelableExtra(key, T::class.java)
        else -> @Suppress("DEPRECATION") getParcelableExtra(key) as? T
    }


    private fun startProjection(resultCode: Int, data: Intent?) {
        // --- Critical Guard: Ensure this specific data hasn't been used for the *current* projection ---
        // Check if mediaProjection is null. If not null, projection is considered active/being set up.
        if (mediaProjection != null) {
             Log.w(TAG, "startProjection called, but mediaProjection instance already exists. Ignoring.")
             isStartingProjection.set(false) // Release lock if acquired
             // If a screenshot was requested, trigger it now that we know projection exists (or is being set up)
              if(currentCorrelationId != null) {
                  Log.d(TAG, "Projection exists/starting, triggering takeScreenshot for pending ID $currentCorrelationId")
                  takeScreenshot()
              }
             return
         }

        if (data == null) {
            Log.e(TAG, "startProjection called with null data intent. Cannot proceed.")
            apiClient.sendScreenshotResult(null, currentCorrelationId, false, "Internal error: Projection start data missing.")
            isStartingProjection.set(false) // Release lock
            stopSelf() // Can't start without data
            return
        }

        Log.d(TAG, "Attempting to get MediaProjection instance...")

        try {
            // --- Call getMediaProjection ONLY if mediaProjection is null ---
            mediaProjection = mediaProjectionManager.getMediaProjection(resultCode, data)

            if (mediaProjection == null) {
                Log.e(TAG, "Failed to get MediaProjection instance.")
                apiClient.sendScreenshotResult(null, currentCorrelationId, false, "Failed to get MediaProjection.")
                initialData = null // Invalidate stored data if it failed
                initialResultCode = Activity.RESULT_CANCELED
                isStartingProjection.set(false)
                stopSelf()
                return
            }
            Log.i(TAG, "MediaProjection obtained successfully: $mediaProjection")

            startImageReaderThread()
            val handler = backgroundHandler ?: throw IllegalStateException("Background handler thread not running")
            mediaProjection?.registerCallback(mediaProjectionCallback, handler)
            Log.d(TAG, "MediaProjection callback registered.")

            // --- Setup ImageReader and VirtualDisplay ONLY if they don't exist ---
            updateScreenMetrics()
            if (screenWidth <= 0 || screenHeight <= 0) {
                throw IllegalStateException("Invalid screen dimensions ($screenWidth x $screenHeight)")
            }

            if (imageReader == null) {
                 Log.d(TAG, "Creating ImageReader...")
                 imageReader = ImageReader.newInstance(screenWidth, screenHeight, PixelFormat.RGBA_8888, 2)
                 Log.d(TAG, "ImageReader created for ${screenWidth}x$screenHeight.")
             } else {
                 Log.w(TAG, "ImageReader already exists. Reusing.")
                 // Maybe close and recreate if dimensions changed drastically? For now, reuse.
             }

            if (virtualDisplay == null) {
                Log.d(TAG, "Creating VirtualDisplay...")
                virtualDisplay = mediaProjection?.createVirtualDisplay(
                    VIRTUAL_DISPLAY_NAME,
                    screenWidth, screenHeight, screenDensity,
                    DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
                    imageReader?.surface, null, handler
                )
                if (virtualDisplay == null) {
                     Log.e(TAG, "Failed to create Virtual Display.")
                     throw IllegalStateException("Failed to create VirtualDisplay")
                 }
                 Log.i(TAG, "VirtualDisplay created successfully.")
                 isProjectionRunning = true // Set flag ONLY after VD is created
            } else {
                 Log.w(TAG, "VirtualDisplay already exists. Projection likely already running.")
                 // If VD exists, projection should be considered running
                 isProjectionRunning = true
            }
             // --- END GUARD ---


            Log.i(TAG, "Projection start sequence completed. isProjectionRunning = $isProjectionRunning")
            // Trigger capture if an ID was waiting for the projection to start
            if (currentCorrelationId != null) {
                Log.d(TAG, "Projection started/verified, initiating screenshot capture for stored ID: $currentCorrelationId")
                takeScreenshot()
            }

        } catch (e: Exception) {
            Log.e(TAG, "Exception during projection start: ${e.message}", e)
            apiClient.sendScreenshotResult(null, currentCorrelationId, false, "Error starting projection: ${e.message}")
            initialData = null // Invalidate data on error
            initialResultCode = Activity.RESULT_CANCELED
            stopProjectionInternals() // Clean up any partial setup
            stopSelf()
        } finally {
             isStartingProjection.set(false) // Release lock regardless of outcome
         }
    }


    // MediaProjection callback
    private val mediaProjectionCallback = object : MediaProjection.Callback() {
        override fun onStop() {
            super.onStop()
            Log.w(TAG, "MediaProjection.Callback onStop triggered!")
            // --- Add Check: Only cleanup if projection was actually running ---
            // Use a local copy of mediaProjection for safety in case it's nulled elsewhere concurrently
             val currentProjection = mediaProjection
             if (currentProjection != null) { // Check if we actually had a projection
                 Log.w(TAG,"Projection stopped externally. Cleaning up service.")
                 // Unregister callback *from the instance that stopped*
                 try {
                      currentProjection.unregisterCallback(this)
                      Log.d(TAG, "MediaProjection callback unregistered in onStop.")
                  } catch (e: Exception) {
                      Log.w(TAG, "Error unregistering callback in onStop", e)
                  }
                  // Now perform cleanup
                  stopServiceAndCleanup()
              } else {
                  Log.d(TAG, "MediaProjection.Callback onStop triggered, but mediaProjection was already null.")
                   // Ensure flag is false if callback is somehow called after cleanup
                   isProjectionRunning = false
              }
        }
    }

     // Centralized function to stop service and cleanup projection
     private fun stopServiceAndCleanup() {
         Log.i(TAG, "Stopping service and cleaning up projection resources.")
         stopProjectionInternals()
         stopSelf() // Request the service to stop itself
     }


    // Centralized internal cleanup logic for projection resources
     private fun stopProjectionInternals() {
         if (!isProjectionRunning && mediaProjection == null && virtualDisplay == null && imageReader == null) {
             Log.d(TAG, "stopProjectionInternals called, but resources seem already released.")
             return // Avoid redundant cleanup
         }
         Log.d(TAG, "stopProjectionInternals: Releasing projection resources...")
         isProjectionRunning = false // Set flag false early

         // Release resources in reverse order of creation, with null checks and error handling
         try {
             virtualDisplay?.release()
         } catch (e: Exception) { Log.e(TAG, "Error releasing VirtualDisplay", e) }
         virtualDisplay = null

         try {
             imageReader?.close() // Close image reader
         } catch (e: Exception) { Log.e(TAG, "Error closing ImageReader", e) }
         imageReader = null

         // Unregister callback *before* stopping projection if possible
         try {
             mediaProjection?.unregisterCallback(mediaProjectionCallback)
             Log.d(TAG, "MediaProjection callback unregistered.")
         } catch (e: Exception) {
             // This might happen if projection already stopped, log warning
             Log.w(TAG, "Error unregistering MediaProjection callback (maybe already stopped?)", e)
         }

         try {
             mediaProjection?.stop() // Stop the projection session
             Log.d(TAG, "MediaProjection stopped.")
         } catch (e: Exception) {
             Log.e(TAG, "Error stopping MediaProjection", e)
         }
         mediaProjection = null // Clear the reference

         Log.d(TAG, "Projection resources released.")
     }

     // Public method to request projection stop (not typically needed externally)
     private fun stopProjection() {
         Log.d(TAG, "stopProjection called externally (usually unnecessary). Initiating cleanup...")
         stopProjectionInternals()
         // Stop the foreground service state if it was running
         stopForeground(STOP_FOREGROUND_REMOVE)
         Log.d(TAG, "Service removed from foreground (if running).")
     }


    // Call this method when a screenshot is requested
     fun takeScreenshot() {
         // --- FIX: Prevent concurrent execution ---
         if (!isProcessingScreenshot.compareAndSet(false, true)) {
             Log.w(TAG, "[$currentCorrelationId] Already processing a screenshot. Ignoring duplicate trigger.")
             // Optionally: Queue the request or notify server of busy state?
             // For now, just ignore. The caller (onStartCommand) should handle the new ID.
             return
         }
         // --- END FIX ---

         val captureCorrelationId = currentCorrelationId // Capture the ID for this specific attempt
         Log.d(TAG, "takeScreenshot starting processing for correlationId: $captureCorrelationId")

        if (!isProjectionRunning || mediaProjection == null || imageReader == null || backgroundHandler == null) {
            Log.e(TAG, "Cannot take screenshot: Service not ready.")
            Log.e(TAG, "State: isRunning=$isProjectionRunning, projection=$mediaProjection, reader=$imageReader, handler=$backgroundHandler")
            apiClient.sendScreenshotResult(null, captureCorrelationId, false, "Screen capture service not ready.")
            // Don't resetCorrelationIdIfNeeded here if the service isn't ready, let the caller retry?
            // Or reset it? Let's reset for now to avoid holding onto a failed ID.
             resetCorrelationIdIfNeeded(captureCorrelationId)
            isProcessingScreenshot.set(false) // Release the lock
            return
        }

        // Launch image processing in a background coroutine within the service's scope
        serviceScope.launch {
            var image: Image? = null
            var success = false
            var message = "Screenshot capture failed."
            var base64Image: String? = null
            var bitmap: Bitmap? = null // For recycling in finally

            try {
                Log.d(TAG, "[$captureCorrelationId] Acquiring image in coroutine...")
                // Add a small delay - sometimes needed right after projection start
                for (attempt in 1..3) {
                    Log.d(TAG, "[$captureCorrelationId] Attempt $attempt to acquire image...")
                    try {
                        Log.d(TAG, "[$captureCorrelationId] Calling acquireLatestImage (attempt $attempt)...")
                        image = imageReader?.acquireLatestImage()
                        Log.d(TAG, "[$captureCorrelationId] acquireLatestImage returned (attempt $attempt). Image is ${if (image == null) "null" else "not null"}")
                        if (image != null) {
                            Log.d(TAG, "[$captureCorrelationId] Image acquired (format: ${image.format}, TS: ${image.timestamp})")
                            break
                        } else {
                            Log.w(TAG, "[$captureCorrelationId] acquireLatestImage returned null (attempt $attempt). Retrying after delay.")
                            if (attempt < 3) {
                                val waitMs = if (attempt == 1) 250L else 500L
                                Log.d(TAG, "[$captureCorrelationId] Starting ${waitMs}ms delay before retry...")
                                delay(waitMs)
                                Log.d(TAG, "[$captureCorrelationId] Finished ${waitMs}ms delay.")
                            }
                        }
                    } catch (e: Exception) {
                        Log.e(TAG, "[$captureCorrelationId] Error during acquireLatestImage (attempt $attempt)", e)
                        throw e
                    }
                }

                if (image == null) {
                    throw IllegalStateException("Failed to acquire image frame after 3 attempts.")
                }

                val planes = image.planes
                 if (planes.isEmpty() || planes[0].buffer == null) {
                     throw IllegalStateException("Acquired image has no planes or buffer.")
                 }
                val buffer: ByteBuffer = planes[0].buffer
                val pixelStride: Int = planes[0].pixelStride
                val rowStride: Int = planes[0].rowStride
                // Calculate padding needed per row
                 val rowPaddingBytes = rowStride - pixelStride * screenWidth
                 Log.d(TAG, "[$captureCorrelationId] Image details: PxStride=$pixelStride, RowStride=$rowStride, RowPaddingBytes=$rowPaddingBytes, BufferCap=${buffer.capacity()}")

                // Basic validation
                 val expectedSize = screenHeight * rowStride
                 if (buffer.capacity() < expectedSize) {
                      throw IllegalStateException("Buffer capacity (${buffer.capacity()}) is less than expected size ($expectedSize)")
                 }

                 // Create bitmap, copy pixels, handle potential padding
                 // Use a bitmap config less prone to issues if ARGB_8888 fails on some devices
                 val config = Bitmap.Config.ARGB_8888
                 bitmap = Bitmap.createBitmap(screenWidth + rowPaddingBytes / pixelStride, screenHeight, config)
                 bitmap.copyPixelsFromBuffer(buffer)
                 Log.d(TAG, "[$captureCorrelationId] copyPixelsFromBuffer completed.")

                // IMPORTANT: Close the image as soon as buffer is copied
                 image.close()
                 image = null // Nullify to prevent closing again in finally

                // Crop the padding if necessary
                if (rowPaddingBytes > 0) {
                     Log.d(TAG, "[$captureCorrelationId] Cropping bitmap padding...")
                    val croppedBitmap = Bitmap.createBitmap(bitmap, 0, 0, screenWidth, screenHeight)
                    bitmap.recycle() // Recycle the padded one immediately
                    bitmap = croppedBitmap // Use the cropped one
                    Log.d(TAG, "[$captureCorrelationId] Bitmap cropped to $screenWidth x $screenHeight")
                }

                // Convert to Base64
                val outputStream = ByteArrayOutputStream()
                bitmap.compress(Bitmap.CompressFormat.JPEG, 80, outputStream) // Compress as JPEG, quality 80
                base64Image = Base64.encodeToString(outputStream.toByteArray(), Base64.NO_WRAP) // Use NO_WRAP

                success = true
                message = "Screenshot captured successfully."
                Log.i(TAG, "[$captureCorrelationId] Bitmap converted to Base64 JPEG (Size: ${base64Image?.length ?: 0})")

            } catch (e: Exception) {
                Log.e(TAG, "[$captureCorrelationId] Error during screenshot processing coroutine", e)
                success = false
                message = "Error capturing/processing screenshot: ${e.message}"
            } finally {
                Log.d(TAG, "[${captureCorrelationId ?: "N/A"}] Entering screenshot finally block in coroutine.")
                // Ensure image is closed if something went wrong before explicit close
                 try { image?.close() } catch (e: Exception) { Log.w(TAG, "Error closing image in finally block", e) }

                // Send the result (success or failure) back WITH the captured correlation ID
                apiClient.sendScreenshotResult(base64Image, captureCorrelationId, success, message)
                Log.i(TAG,"[$captureCorrelationId] Sent screenshot result via ApiClient (Success: $success).")

                // Recycle the final bitmap if it exists
                 try { bitmap?.recycle() } catch (e: Exception) { Log.w(TAG, "Error recycling bitmap in finally block", e) }

                // Reset the service's currentCorrelationId *only if* it still matches the ID we processed for this capture.
                resetCorrelationIdIfNeeded(captureCorrelationId)

                // --- FIX: Release the processing lock ---
                isProcessingScreenshot.set(false)
                Log.d(TAG, "[$captureCorrelationId] Released screenshot processing lock.")
                // --- END FIX ---

                 Log.d(TAG, "[${captureCorrelationId ?: "N/A"}] Exiting screenshot finally block in coroutine.")
             }
        }
    }

    // Helper to safely reset the currentCorrelationId only if it matches the processed ID
     private fun resetCorrelationIdIfNeeded(processedId: String?) {
         if (processedId != null && currentCorrelationId == processedId) {
             Log.d(TAG, "Resetting service's currentCorrelationId (was: $currentCorrelationId)")
             currentCorrelationId = null
         } else if (processedId != null) {
             // This means a new request likely arrived while the previous one (processedId) was running.
             // The service's currentCorrelationId already holds the *new* ID. Don't reset it.
             Log.w(TAG, "Not resetting currentCorrelationId ($currentCorrelationId) because processed ID ($processedId) doesn't match (likely a newer request has already updated it).")
         }
     }


    override fun onDestroy() {
        Log.w(TAG, "Service onDestroy called.")
        stopProjectionInternals() // Ensure resources are cleaned up
        stopImageReaderThread() // Stop background thread
        serviceJob.cancel() // Cancel the coroutine scope and its children
        super.onDestroy()
        Log.d(TAG, "Service destroyed.")
    }


    override fun onBind(intent: Intent?): IBinder? {
        return null // Not using binding
    }

    // --- Notification Handling ---
    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                NOTIFICATION_CHANNEL_ID,
                "Screen Capture Service", // User visible name
                NotificationManager.IMPORTANCE_LOW // Low importance for foreground service
            )
            channel.description = "Background service for screen capture" // User visible description
            val manager = getSystemService(NotificationManager::class.java)
            manager.createNotificationChannel(channel)
             Log.d(TAG, "Notification channel created.")
         }
     }

    private fun createNotification(): Notification {
        val builder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            Notification.Builder(this, NOTIFICATION_CHANNEL_ID)
        } else {
            @Suppress("DEPRECATION")
            Notification.Builder(this)
        }

        // Basic notification
        return builder
            .setContentTitle(getString(R.string.app_name)) // Use app name
            .setContentText("Screen capture service active") // More accurate text
            .setSmallIcon(R.drawable.ic_launcher_foreground) // Use app launcher icon (ensure it exists)
            .setOngoing(true) // Make it persistent
            .setForegroundServiceBehavior(Notification.FOREGROUND_SERVICE_IMMEDIATE) // Show immediately if possible
            .build()
    }
}