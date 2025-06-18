package com.yogen.Android_Use.ui.settings

import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.ViewModel

class SettingsViewModel : ViewModel() {

    // Define user profile data class
    data class UserProfile(
        val name: String,
        val email: String,
        val phone: String
    )

    private val _userProfile = MutableLiveData<UserProfile>()
    val userProfile: LiveData<UserProfile> = _userProfile

    init {
        // Load user profile data (in a real app, this would come from a repository)
        loadUserProfile()
    }

    private fun loadUserProfile() {
        // Simulate loading profile from a data source
        // In a real app, this would fetch data from a repository
        val profile = UserProfile(
            name = "Sudesh Pawar",
            email = "sudesh2911@gmail.com",
            phone = "+13523282480"
        )
        _userProfile.value = profile
    }

    fun signOut() {
        // Implement sign out logic
        // In a real app, this would:
        // 1. Clear user session with authentication service
        // 2. Clear local user data
        // 3. Navigate to login screen
        
        // For now, this is just a stub
    }
} 