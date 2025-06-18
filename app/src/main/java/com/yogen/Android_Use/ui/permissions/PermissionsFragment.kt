package com.yogen.Android_Use.ui.permissions

import android.app.Activity.RESULT_OK
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import androidx.fragment.app.viewModels
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.navigation.fragment.findNavController
import com.yogen.Android_Use.R
import com.yogen.Android_Use.app.AndroidUseApplication
import com.yogen.Android_Use.databinding.FragmentPermissionsBinding
import com.yogen.Android_Use.utils.AccessibilityUtils
import kotlinx.coroutines.launch
import com.yogen.Android_Use.ui.chat.ChatViewModel

// Import Material Components R class if needed for colors
// import com.google.android.material.R as MaterialR

class PermissionsFragment : Fragment() {

    private var _binding: FragmentPermissionsBinding? = null
    private val binding get() = _binding!!
    private val viewModel: PermissionsViewModel by viewModels()
    private lateinit var chatViewModel: ChatViewModel

    private lateinit var accessibilityResultLauncher: ActivityResultLauncher<Intent>
    private lateinit var screenCaptureResultLauncher: ActivityResultLauncher<Intent>

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // --- Get the Application-scoped ChatViewModel --- 
        val application = requireActivity().application as AndroidUseApplication
        // Use the application context as the ViewModelStoreOwner
        // This ensures we get the singleton instance created with the custom factory
        chatViewModel = ViewModelProvider(application)[ChatViewModel::class.java]
        Log.d("PermissionsFragment", "onCreate: Got ChatViewModel instance: $chatViewModel")
        // -------------------------------------------------

        // Launcher for Accessibility Settings result (we don't get a direct result,
        // so we re-check the status when the fragment resumes)
        accessibilityResultLauncher = registerForActivityResult(
            ActivityResultContracts.StartActivityForResult()
        ) {
            // No direct result, check status in onResume
            Log.d("PermissionsFragment", "Returned from Accessibility Settings")
        }

        // Launcher for Screen Capture permission result
        screenCaptureResultLauncher = registerForActivityResult(
            ActivityResultContracts.StartActivityForResult()
        ) { result ->
            if (result.resultCode == RESULT_OK) {
                Log.i("PermissionsFragment", "Screen Capture permission granted by user.")
                // Optionally, you could start the projection immediately if needed,
                // but for this flow, we just record that permission was granted this session.
                viewModel.updateScreenCapturePermissionStatus(true)
            } else {
                Log.w("PermissionsFragment", "Screen Capture permission denied by user.")
                viewModel.updateScreenCapturePermissionStatus(false)
                // Show a message to the user? (Optional)
            }
        }
    }

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentPermissionsBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        setupButtonClickListeners()
        observeUiState()
    }

    override fun onResume() {
        super.onResume()
        // Re-check accessibility status when returning to the fragment
        viewModel.checkInitialPermissions()
    }

    private fun setupButtonClickListeners() {
        binding.buttonGrantAccessibility.setOnClickListener {
            try {
                val intent = viewModel.getAccessibilitySettingsIntent()
                accessibilityResultLauncher.launch(intent)
            } catch (e: Exception) {
                Log.e("PermissionsFragment", "Could not open Accessibility Settings", e)
                // Show error to user?
            }
        }

        binding.buttonGrantScreenCapture.setOnClickListener {
            val intent = viewModel.getScreenCaptureIntent(requireContext())
            if (intent != null) {
                screenCaptureResultLauncher.launch(intent)
            } else {
                Log.e("PermissionsFragment", "MediaProjectionManager not available")
                // Show error to user?
            }
        }

        binding.buttonNextToChat.setOnClickListener {
            if (viewModel.uiState.value.isAccessibilityServiceEnabled && viewModel.uiState.value.hasScreenCapturePermission) {
                Log.i("PermissionsFragment", "Permissions OK. Triggering connection attempt via ChatViewModel.")
                chatViewModel.attemptAutoConnect()
            } else {
                Log.w("PermissionsFragment", "Next clicked but permissions not fully granted. Cannot connect yet.")
            }
            
            findNavController().navigate(R.id.action_permissionsFragment_to_chatFragment)
        }
    }

    private fun observeUiState() {
        viewLifecycleOwner.lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                viewModel.uiState.collect { state ->
                    updateAccessibilityStatus(state.isAccessibilityServiceEnabled)
                    updateScreenCaptureStatus(state.hasScreenCapturePermission)

                    // Enable Next button only if both permissions are granted/appear active
                    binding.buttonNextToChat.isEnabled = state.isAccessibilityServiceEnabled && state.hasScreenCapturePermission
                }
            }
        }
    }

    private fun updateAccessibilityStatus(isEnabled: Boolean) {
        if (isEnabled) {
            binding.statusAccessibility.text = "Status: Granted"
            binding.statusAccessibility.setTextColor(ContextCompat.getColor(requireContext(), R.color.yogen_green))
            binding.buttonGrantAccessibility.isEnabled = false // Disable button if granted
            binding.buttonGrantAccessibility.text = "Accessibility Granted"
        } else {
            binding.statusAccessibility.text = "Status: Not Granted"
            // Use Material Design color reference
            binding.statusAccessibility.setTextColor(ContextCompat.getColor(requireContext(), com.google.android.material.R.color.design_default_color_error))
            binding.buttonGrantAccessibility.isEnabled = true
            binding.buttonGrantAccessibility.text = "Grant Accessibility"
        }
    }

    private fun updateScreenCaptureStatus(isGranted: Boolean) {
         if (isGranted) {
            binding.statusScreenCapture.text = "Status: Granted (This Session)"
            binding.statusScreenCapture.setTextColor(ContextCompat.getColor(requireContext(), R.color.yogen_green))
            binding.buttonGrantScreenCapture.isEnabled = false // Disable button if granted for this session
            binding.buttonGrantScreenCapture.text = "Screen Capture Granted"
        } else {
            binding.statusScreenCapture.text = "Status: Not Granted"
            // Use Material Design color reference
            binding.statusScreenCapture.setTextColor(ContextCompat.getColor(requireContext(), com.google.android.material.R.color.design_default_color_error))
            binding.buttonGrantScreenCapture.isEnabled = true
            binding.buttonGrantScreenCapture.text = "Grant Screen Capture"
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
} 