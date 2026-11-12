package com.nutrime.app

import android.graphics.Color as AndroidColor
import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.lifecycleScope
import com.nutrime.app.data.Appearance
import com.nutrime.app.ui.NutriMeNavHost
import com.nutrime.app.ui.theme.DesignTokens
import com.nutrime.app.ui.theme.NutriMeTheme
import com.nutrime.app.ui.theme.ThemeMode
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {
    /** A link to open: from the widget, a notification, or Health Connect's privacy page. */
    private val link = kotlinx.coroutines.flow.MutableStateFlow<String?>(null)

    private fun readLink(intent: Intent?) {
        intent ?: return
        link.value = when {
            intent.action == "androidx.health.ACTION_SHOW_PERMISSIONS_RATIONALE" ||
                intent.action == "android.intent.action.VIEW_PERMISSION_USAGE" -> "nutrime://connections"
            intent.data?.scheme == "nutrime" -> intent.data.toString()
            else -> null
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        readLink(intent)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        val settings = (application as NutriMeApplication).container.settings
        if (savedInstanceState == null) readLink(intent)
        setContent {
            val appearance by settings.appearance.collectAsStateWithLifecycle(
                initialValue = Appearance(ThemeMode.SYSTEM, DesignTokens.Accent.Default)
            )
            val dark = when (appearance.mode) {
                ThemeMode.SYSTEM -> isSystemInDarkTheme()
                ThemeMode.LIGHT -> false
                ThemeMode.DARK -> true
            }
            // Status/navigation bar icons follow the app theme, not just the OS setting.
            LaunchedEffect(dark) {
                val style = if (dark) SystemBarStyle.dark(AndroidColor.TRANSPARENT)
                else SystemBarStyle.light(AndroidColor.TRANSPARENT, AndroidColor.TRANSPARENT)
                enableEdgeToEdge(statusBarStyle = style, navigationBarStyle = style)
            }
            val pending by link.collectAsStateWithLifecycle()
            NutriMeTheme(appearance.mode, appearance.accent) { NutriMeNavHost(link = pending, onLinkHandled = { link.value = null }) }
        }
    }

    override fun onStart() {
        super.onStart()
        val container = (application as NutriMeApplication).container
        lifecycleScope.launch {
            if (container.settings.current().loggedIn) container.sync.syncNow()
        }
    }
}
