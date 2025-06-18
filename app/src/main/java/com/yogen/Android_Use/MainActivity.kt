package com.yogen.Android_Use

import android.Manifest
import android.accessibilityservice.AccessibilityServiceInfo
import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.media.projection.MediaProjectionManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.util.Log
import android.view.Menu
import android.view.MenuItem
import android.view.accessibility.AccessibilityManager
import android.widget.Toast
import androidx.activity.viewModels
import androidx.lifecycle.LifecycleOwner
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.view.GravityCompat
import androidx.drawerlayout.widget.DrawerLayout
import androidx.lifecycle.ViewModelProvider
import androidx.localbroadcastmanager.content.LocalBroadcastManager
import androidx.navigation.NavController
import androidx.navigation.findNavController
import androidx.navigation.ui.AppBarConfiguration
import androidx.navigation.ui.navigateUp
import androidx.navigation.ui.setupActionBarWithNavController
import androidx.navigation.ui.setupWithNavController
import androidx.navigation.ui.NavigationUI
import com.google.android.material.navigation.NavigationView
import com.google.android.material.snackbar.Snackbar
import com.yogen.Android_Use.accessibility.AndroidUseAccessibilityService
import com.yogen.Android_Use.accessibility.service_files.ACTION_ACCESSIBILITY_CONNECTED
import com.yogen.Android_Use.accessibility.service_files.ACTION_ACCESSIBILITY_DISCONNECTED
import com.yogen.Android_Use.api.ApiClient
import com.yogen.Android_Use.databinding.ActivityMainBinding
import com.yogen.Android_Use.dialogs.AccessibilityPermissionDialog
import com.yogen.Android_Use.screenshot.ScreenCaptureService
import com.yogen.Android_Use.ui.chat.ChatViewModel
import com.yogen.Android_Use.utils.AccessibilityUtils
import kotlinx.coroutines.*
import java.util.UUID
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.flow.collectLatest
import com.yogen.Android_Use.app.AndroidUseApplication
import com.yogen.Android_Use.utils.DeviceMetricsHolder

// Define TAG constant for logging
private const val TAG = "MainActivity"

class MainActivity : AppCompatActivity() {

    private lateinit var appBarConfiguration: AppBarConfiguration
    private lateinit var binding: ActivityMainBinding
    private lateinit var navController: NavController
    private val chatViewModel: ChatViewModel by lazy {
        (application as AndroidUseApplication).chatViewModel
    }

    // Screenshot variables
    private var mediaProjectionManager: MediaProjectionManager? = null
    private var screenCaptureIntent: Intent? = null
    private var screenCaptureResultCode: Int = Activity.RESULT_CANCELED

    // Activity Result Launcher for Media Projection permission
    private val requestMediaProjection = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        if (result.resultCode == Activity.RESULT_OK && result.data != null) {
            Log.i(TAG, "Media Projection permission granted.")
            screenCaptureResultCode = result.resultCode
            screenCaptureIntent = result.data
            // Start service WITH the result data & potentially pending correlation ID
            startScreenCaptureService(pendingCorrelationIdForCapture)
            pendingCorrelationIdForCapture = null // Clear pending ID after use
        } else {
            Log.e(TAG, "Media Projection permission denied.")
            Snackbar.make(binding.root, "Screen capture permission is required for the app to function.", Snackbar.LENGTH_LONG).show()
            // Optionally, send an error back if a request was pending
             pendingCorrelationIdForCapture?.let {
                 // No need to send screenshot result here if listener is handled by Application
             }
             pendingCorrelationIdForCapture = null
        }
    }

    // Activity Result Launcher for Notification permission (Android 13+)
    private val requestNotificationPermission = registerForActivityResult(ActivityResultContracts.RequestPermission()) { isGranted ->
        if (isGranted) {
            Log.i(TAG, "Notification permission granted.")
        } else {
            Log.w(TAG, "Notification permission denied.")
            Snackbar.make(binding.root, "Notification permission is recommended for service status.", Snackbar.LENGTH_LONG).show()
        }
    }

    // Temporarily store correlation ID if capture is requested before permission is granted
    private var pendingCorrelationIdForCapture: String? = null

    // --- Broadcast Receiver for Screenshot Requests --- //
    private val screenshotRequestReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            Log.d(TAG, "Received broadcast for action: ${intent?.action}")
            if (intent?.action == ApiClient.ACTION_REQUEST_SCREENSHOT) {
                val correlationId = intent.getStringExtra(ApiClient.EXTRA_CORRELATION_ID)
                if (correlationId != null) {
                    Log.d(TAG, "Extracted screenshot correlationId from broadcast: $correlationId")
                    captureScreenshotNow(correlationId)
                } else {
                    Log.w(TAG, "Received screenshot request broadcast without correlationId.")
                }
            }
        }
    }

    private var accessibilityDialog: AccessibilityPermissionDialog? = null
    private val accessibilityStateReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            when (intent.action) {
                ACTION_ACCESSIBILITY_CONNECTED -> {
                    Log.d(TAG, "Accessibility service connected.")
                    accessibilityDialog?.checkPermissionAndDismiss()
                }
                ACTION_ACCESSIBILITY_DISCONNECTED -> {
                    Log.d(TAG, "Accessibility service disconnected.")
                }
            }
        }
    }
    private val accessibilityPermissionChecker = Handler(Looper.getMainLooper())
    private val checkAccessibilityRunnable = object : Runnable {
        override fun run() {
            if (accessibilityDialog?.isShowing == true) {
                if (AccessibilityUtils.isAccessibilityServiceEnabled(this@MainActivity)) {
                    accessibilityDialog?.dismiss()
                } else {
                    accessibilityPermissionChecker.postDelayed(this, 1500)
                }
            }
        }
    }

    // Implement the ConnectionListener interface directly
    private val connectionListener = object : ApiClient.ConnectionListener {
        override fun onConnected() {
            Log.i(TAG, "ConnectionListener: Connected")
            runOnUiThread { chatViewModel.onConnectionEstablished() }
        }

        override fun onDisconnected(reason: String) {
            Log.w(TAG, "ConnectionListener: Disconnected - $reason")
            runOnUiThread { chatViewModel.onConnectionLost(reason) }
        }

        override fun onError(error: String) {
            Log.e(TAG, "ConnectionListener: Error - $error")
            runOnUiThread { chatViewModel.onConnectionError(error) }
        }

        override fun onStatusUpdate(message: String) {
            Log.d(TAG, "ConnectionListener: Status Update - $message")
            runOnUiThread { chatViewModel.addServerStatusMessage(message) }
        }

        override fun onClarificationRequest(question: String) {
            Log.d(TAG, "ConnectionListener: Clarification Request - $question")
            runOnUiThread { chatViewModel.addClarificationRequest(question) }
        }

        override fun onDialogResponse(message: String) {
            Log.d(TAG, "ConnectionListener: Dialog Response - '$message'")
            runOnUiThread { chatViewModel.addAssistantChatMessage(message) }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        // --- Get and Store Screen Metrics --- //
        updateDeviceMetrics()
        // ------------------------------------ //

        setSupportActionBar(binding.appBarMain.toolbar)
        binding.appBarMain.fab.hide()

        val drawerLayout: DrawerLayout = binding.drawerLayout
        val navView: NavigationView = binding.navView
        navController = findNavController(R.id.nav_host_fragment_content_main)

        appBarConfiguration = AppBarConfiguration(setOf(R.id.nav_chat), drawerLayout)
        setupActionBarWithNavController(navController, appBarConfiguration)
        navView.setupWithNavController(navController)

        mediaProjectionManager = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager

        registerReceivers()

        observeViewModel()
        Log.d(TAG, "MainActivity onCreate completed.")

        // Pass the listener to the ApiClient instance
        // We need to ensure ApiClient is initialized *after* this listener is ready
        // This is better handled in the Application class now
        // (application as AndroidUseApplication).apiClient.setListener(connectionListener)
    }

    private fun openAccessibilitySettings() {
        Toast.makeText(this, "Please enable 'Android-Use Agent' service", Toast.LENGTH_LONG).show()
        try {
            val intent = Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)
            startActivity(intent)
        } catch (e: ActivityNotFoundException){
            Log.e(TAG,"Could not open Accessibility Settings", e)
            Toast.makeText(this, "Could not open Accessibility Settings.", Toast.LENGTH_SHORT).show()
        }
    }

    private fun requestScreenCapturePermission() {
        if (mediaProjectionManager == null) { Log.e(TAG, "MPM is null"); return }
        Log.i(TAG, "Requesting screen capture permission...")
        try {
            requestMediaProjection.launch(mediaProjectionManager!!.createScreenCaptureIntent())
        } catch (e: Exception) {
            Log.e(TAG, "Error launching screen capture intent", e)
            // No need to send screenshot result here if listener is handled by Application
            pendingCorrelationIdForCapture?.let {
                // No need to send screenshot result here if listener is handled by Application
            }
            pendingCorrelationIdForCapture = null
        }
    }

    private fun startScreenCaptureService(correlationId: String?) {
        Log.i(TAG, "Starting/Initializing ScreenCaptureService with correlationId: $correlationId")
        val serviceIntent = Intent(this, ScreenCaptureService::class.java).apply {
            if (screenCaptureResultCode == Activity.RESULT_OK && screenCaptureIntent != null) {
                putExtra(ScreenCaptureService.EXTRA_RESULT_CODE, screenCaptureResultCode)
                putExtra(ScreenCaptureService.EXTRA_DATA, screenCaptureIntent)
                Log.d(TAG, "Including projection start data in service intent.")
            }
            correlationId?.let { putExtra(ScreenCaptureService.EXTRA_CORRELATION_ID, it) }
        }

        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                startForegroundService(serviceIntent)
                Log.d(TAG, "Started foreground service.")
            } else {
                startService(serviceIntent)
                Log.d(TAG, "Started background service (pre-Oreo).")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to start ScreenCaptureService", e)
             // No need to send screenshot result here if listener is handled by Application
              if (pendingCorrelationIdForCapture == correlationId) {
                  pendingCorrelationIdForCapture = null
              }
        }
    }

    private fun stopScreenCaptureService() {
        Log.i(TAG, "Stopping ScreenCaptureService.")
        val serviceIntent = Intent(this, ScreenCaptureService::class.java)
        stopService(serviceIntent)
        screenCaptureResultCode = Activity.RESULT_CANCELED
        screenCaptureIntent = null
    }

    private fun checkAccessibilityPermission() {
        if (!AccessibilityUtils.isAccessibilityServiceEnabled(this)) {
            showAccessibilityPermissionDialog()
        }
    }

    private fun showAccessibilityPermissionDialog() {
        if (accessibilityDialog?.isShowing == true) return // Avoid showing multiple dialogs
        accessibilityDialog = AccessibilityPermissionDialog(this).apply {
        }
        try {
            accessibilityDialog?.show()
            accessibilityPermissionChecker.removeCallbacks(checkAccessibilityRunnable)
            accessibilityPermissionChecker.postDelayed(checkAccessibilityRunnable, 1000)
        } catch (e: Exception) {
            Log.e(TAG, "Error showing accessibility dialog", e)
        }
    }

    private fun captureScreenshotNow(correlationId: String) {
        Log.d(TAG, "MainActivity captureScreenshotNow called with correlationId: $correlationId")
        if (screenCaptureIntent != null && screenCaptureResultCode == Activity.RESULT_OK) {
            Log.d(TAG, "Screenshot permission already granted. Starting service (if needed) and telling it to capture with correlationId: $correlationId")
            startScreenCaptureService(correlationId)
        } else {
            Log.w(TAG, "Screenshot permission needed. Requesting... Storing pending correlationId: $correlationId")
            pendingCorrelationIdForCapture = correlationId
            requestScreenCapturePermission()
        }
    }

    override fun onResume() {
        super.onResume()
        Log.d(TAG, "[CONNECTION_DEBUG] onResume started.")
        checkAndRequestCorePermissions()
        Log.d(TAG, "[CONNECTION_DEBUG] onResume: Permissions OK, preparing to call attemptAutoConnect()")
        chatViewModel.attemptAutoConnect()
        Log.d(TAG, "[CONNECTION_DEBUG] onResume: Called attemptAutoConnect()")
    }

    override fun onPause() {
        super.onPause()
        accessibilityPermissionChecker.removeCallbacks(checkAccessibilityRunnable)
    }

    override fun onDestroy() {
        super.onDestroy()
        Log.d(TAG, "MainActivity onDestroy started.")
        stopScreenCaptureService()
        unregisterReceivers()
        accessibilityDialog?.dismiss()
        Log.d(TAG, "MainActivity onDestroy finished.")
    }

    private fun registerReceivers() {
        val accessibilityFilter = IntentFilter().apply {
            addAction(ACTION_ACCESSIBILITY_CONNECTED)
            addAction(ACTION_ACCESSIBILITY_DISCONNECTED)
        }
        LocalBroadcastManager.getInstance(this).registerReceiver(accessibilityStateReceiver, accessibilityFilter)
        val requestFilter = IntentFilter(ApiClient.ACTION_REQUEST_SCREENSHOT)
        LocalBroadcastManager.getInstance(this).registerReceiver(screenshotRequestReceiver, requestFilter)
        Log.d(TAG, "Registered receivers")
    }

    private fun unregisterReceivers() {
        try {
            LocalBroadcastManager.getInstance(this).unregisterReceiver(accessibilityStateReceiver)
            LocalBroadcastManager.getInstance(this).unregisterReceiver(screenshotRequestReceiver)
        } catch (e: Exception) { Log.w(TAG, "Error unregistering receivers", e) }
    }

    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        menuInflater.inflate(R.menu.main, menu)
        return true
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        return when (item.itemId) {
            R.id.action_settings -> {
                try { findNavController(R.id.nav_host_fragment_content_main).navigate(R.id.nav_settings) }
                catch (e: Exception) { Log.e(TAG, "Navigation failed", e) }
                true
            }
            else -> super.onOptionsItemSelected(item)
        }
    }

    override fun onSupportNavigateUp(): Boolean {
        return navController.navigateUp(appBarConfiguration)
                || super.onSupportNavigateUp()
    }

    private fun observeViewModel() {
        // Observe ViewModel state using lifecycleScope
        lifecycleScope.launch {
            chatViewModel.isConnected.collectLatest { isConnected ->
                // Optional: Update MainActivity specific UI based on connection
                Log.d(TAG, "[MAIN_CONN_STATE] Observed connection status: ${if (isConnected) "Connected" else "Disconnected"}")
                // Example: Maybe change toolbar color or show/hide a status icon
            }
        }
        lifecycleScope.launch {
            chatViewModel.errorMessage.collectLatest { error ->
                error?.let {
                    Snackbar.make(binding.root, "Error: $it", Snackbar.LENGTH_LONG).show()
                    chatViewModel.clearErrorMessage()
                }
            }
        }
        // No need to observe messages here, Fragment handles it.
    }

    private fun checkAndRequestCorePermissions() {
        Log.d(TAG, "Checking core permissions...")
        if (!AccessibilityUtils.isAccessibilityServiceEnabled(this)) {
            Log.w(TAG, "Accessibility Service not enabled. Requesting...")
            AccessibilityUtils.openAccessibilitySettings(this)
        } else {
            Log.d(TAG, "Accessibility Service already enabled.")
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
                Log.w(TAG, "Notification permission not granted. Requesting...")
                requestNotificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
            } else {
                Log.d(TAG, "Notification permission already granted.")
            }
        }
        Log.d(TAG, "Core permission check finished.")
    }

    // --- Helper function to get and store metrics ---
    private fun updateDeviceMetrics() {
        // Use the reliable method within an Activity context
        val windowManager = getSystemService(Context.WINDOW_SERVICE) as? android.view.WindowManager
        if (windowManager == null) {
            Log.e(TAG, "Failed to get WindowManager for screen metrics.")
            return
        }

        val width: Int
        val height: Int

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            val bounds = windowManager.currentWindowMetrics.bounds
            width = bounds.width()
            height = bounds.height()
        } else {
            @Suppress("DEPRECATION")
            val displayMetrics = android.util.DisplayMetrics()
            @Suppress("DEPRECATION")
            windowManager.defaultDisplay?.getRealMetrics(displayMetrics)
            width = displayMetrics.widthPixels
            height = displayMetrics.heightPixels
        }

        if (width > 0 && height > 0) {
            DeviceMetricsHolder.updateMetrics(width, height)
        } else {
             Log.e(TAG, "Failed to get valid screen dimensions.")
        }
    }
    // ----------------------------------------------

    companion object {
        // Define TAG constant - MOVED TO TOP OF CLASS
        // private const val TAG = "MainActivity"
    }
}