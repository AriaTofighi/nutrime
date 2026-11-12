package com.nutrime.app.ui.profile

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavController
import com.nutrime.app.BuildConfig
import com.nutrime.app.ui.Logo
import com.nutrime.app.ui.SubPage
import com.nutrime.app.ui.appContainer
import com.nutrime.app.ui.components.ButtonKind
import com.nutrime.app.ui.components.ButtonSize
import com.nutrime.app.ui.components.NButton
import com.nutrime.app.ui.components.NCard
import com.nutrime.app.ui.components.NIconButton
import com.nutrime.app.ui.components.NTextField
import com.nutrime.app.ui.components.Segmented
import com.nutrime.app.ui.theme.DesignTokens.Space
import com.nutrime.app.ui.theme.Lucide
import com.nutrime.app.ui.theme.NutriTheme
import kotlinx.coroutines.launch
import java.text.DateFormat
import java.util.Date

@Composable
fun AccountScreen(nav: NavController) {
    val c = appContainer()
    val account by c.settings.account.collectAsStateWithLifecycle(initialValue = null)
    val syncing by c.sync.running.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()
    var email by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    var showPassword by remember { mutableStateOf(false) }
    var register by remember { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf<String?>(null) }
    val colors = NutriTheme.colors

    LaunchedEffect(account?.email) {
        if (email.isEmpty()) email = account?.email.orEmpty()
    }

    fun auth() = scope.launch {
        busy = true
        message = null
        val url = BuildConfig.SYNC_SERVER_URL
        try {
            val r = if (register) c.syncClient.register(url, email.trim(), password) else c.syncClient.login(url, email.trim(), password)
            c.settings.setLogin(url, r.email, r.token, r.userId)
            password = ""
            message = c.sync.syncNow() ?: "Signed in and synced"
        } catch (e: Exception) {
            message = e.message ?: "Could not reach the server"
        } finally {
            busy = false
        }
    }

    SubPage("Sync & account", onBack = { nav.popBackStack() }) { padding ->
        val a = account ?: return@SubPage
        Column(
            Modifier.fillMaxSize().padding(padding).verticalScroll(rememberScrollState()).padding(top = Space.S1, bottom = Space.S4),
        ) {
            if (a.loggedIn) {
                NCard {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Box(Modifier.size(38.dp).clip(CircleShape).background(colors.accentSoft), contentAlignment = Alignment.Center) {
                            Text(a.email.orEmpty().take(1).uppercase(), style = NutriTheme.type.cardTitle, color = colors.accent)
                        }
                        Spacer(Modifier.width(Space.S3))
                        Column(Modifier.weight(1f)) {
                            Text(a.email.orEmpty(), style = NutriTheme.type.bodyMedium, color = colors.text)
                        }
                    }
                    Spacer(Modifier.height(Space.S4))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(if (a.lastError != null) Lucide.CloudOff else Lucide.Cloud, null, tint = if (a.lastError != null) colors.danger else colors.accent, modifier = Modifier.size(15.dp))
                        Spacer(Modifier.width(6.dp))
                        Text(
                            a.lastError ?: if (a.lastSync > 0) "Last synced " + DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT).format(Date(a.lastSync)) else "Not synced yet",
                            style = NutriTheme.type.small, color = if (a.lastError != null) colors.danger else colors.textSecondary,
                        )
                    }
                    Spacer(Modifier.height(Space.S4))
                    Row(horizontalArrangement = Arrangement.spacedBy(Space.S2)) {
                        NButton("Sync now", { scope.launch { message = c.sync.syncNow() ?: "Sync complete" } }, Modifier.weight(1f), kind = ButtonKind.Primary, icon = Lucide.RefreshCw, loading = syncing)
                        NButton("Sign out", { scope.launch { c.settings.logout(); message = "Signed out. Your data stays on this device." } }, Modifier.weight(1f), icon = Lucide.LogOut)
                    }
                }
            } else {
                Column(Modifier.fillMaxWidth().padding(vertical = Space.S6), horizontalAlignment = Alignment.CenterHorizontally) {
                    Logo(44.dp)
                    Spacer(Modifier.height(Space.S3))
                    Text(if (register) "Create your account" else "Welcome back", style = NutriTheme.type.pageTitle, color = colors.text)
                }
                NCard {
                    Segmented(listOf(false to "Sign in", true to "Create account"), register, { register = it }, fill = true)
                    Spacer(Modifier.height(Space.S4))
                    Column(verticalArrangement = Arrangement.spacedBy(Space.S3)) {
                        NTextField(email, { email = it }, Modifier.fillMaxWidth(), label = "Email", keyboardType = KeyboardType.Email)
                        NTextField(
                            password, { password = it }, Modifier.fillMaxWidth(), label = "Password", keyboardType = KeyboardType.Password,
                            visualTransformation = if (showPassword) VisualTransformation.None else PasswordVisualTransformation(),
                            trailing = { NIconButton(if (showPassword) Lucide.EyeOff else Lucide.Eye, if (showPassword) "Hide password" else "Show password", { showPassword = !showPassword }, size = 32.dp) },
                        )
                        NButton(
                            if (register) "Create account" else "Sign in", { auth() },
                            kind = ButtonKind.Primary, size = ButtonSize.Lg, fill = true, loading = busy,
                            enabled = email.isNotBlank() && password.length >= 8,
                        )
                    }
                }
            }
            message?.let {
                NCard { Text(it, style = NutriTheme.type.small, color = colors.textSecondary) }
            }
        }
    }
}
