package com.yogen.Android_Use.ui.settings

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.fragment.app.Fragment
import androidx.lifecycle.ViewModelProvider
import com.yogen.Android_Use.R

class SettingsFragment : Fragment() {

    private lateinit var viewModel: SettingsViewModel
    private lateinit var tvProfileName: TextView
    private lateinit var tvProfileInitials: TextView
    private lateinit var tvEmail: TextView
    private lateinit var tvPhone: TextView
    private lateinit var layoutUpgradePro: LinearLayout
    private lateinit var layoutDataControls: LinearLayout
    private lateinit var layoutNotifications: LinearLayout
    private lateinit var layoutAbout: LinearLayout
    private lateinit var layoutSignOut: LinearLayout

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        viewModel = ViewModelProvider(this)[SettingsViewModel::class.java]
        val root = inflater.inflate(R.layout.fragment_settings, container, false)

        // Initialize views
        tvProfileName = root.findViewById(R.id.tvProfileName)
        tvProfileInitials = root.findViewById(R.id.tvProfileInitials)
        tvEmail = root.findViewById(R.id.tvEmail)
        tvPhone = root.findViewById(R.id.tvPhone)
        layoutUpgradePro = root.findViewById(R.id.layoutUpgradePro)
        layoutDataControls = root.findViewById(R.id.layoutDataControls)
        layoutNotifications = root.findViewById(R.id.layoutNotifications)
        layoutAbout = root.findViewById(R.id.layoutAbout)
        layoutSignOut = root.findViewById(R.id.layoutSignOut)

        setupClickListeners()
        observeViewModel()

        return root
    }

    private fun setupClickListeners() {
        // Set up click listeners for various options
        layoutUpgradePro.setOnClickListener {
            Toast.makeText(context, "Upgrade to Pro coming soon!", Toast.LENGTH_SHORT).show()
        }

        layoutDataControls.setOnClickListener {
            Toast.makeText(context, "Data Controls coming soon!", Toast.LENGTH_SHORT).show()
        }

        layoutNotifications.setOnClickListener {
            Toast.makeText(context, "Notification settings coming soon!", Toast.LENGTH_SHORT).show()
        }

        layoutAbout.setOnClickListener {
            Toast.makeText(context, "About page coming soon!", Toast.LENGTH_SHORT).show()
        }

        layoutSignOut.setOnClickListener {
            // Handle sign out (would typically call authentication service)
            viewModel.signOut()
            Toast.makeText(context, "Signed out!", Toast.LENGTH_SHORT).show()
        }
    }

    private fun observeViewModel() {
        // Observe user profile data
        viewModel.userProfile.observe(viewLifecycleOwner) { profile ->
            tvProfileName.text = profile.name
            tvProfileInitials.text = getInitials(profile.name)
            tvEmail.text = profile.email
            tvPhone.text = profile.phone
        }
    }

    private fun getInitials(name: String): String {
        return name.split(" ")
            .filter { it.isNotEmpty() }
            .take(2)
            .map { it.first().uppercase() }
            .joinToString("")
    }
} 