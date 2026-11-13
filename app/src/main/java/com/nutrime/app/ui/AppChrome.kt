package com.nutrime.app.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.nutrime.app.ui.components.NIconButton
import com.nutrime.app.ui.theme.DesignTokens.Space
import com.nutrime.app.ui.theme.Lucide
import com.nutrime.app.ui.theme.NutriTheme

/** The NutriMe mark (web/src/components/Logo.tsx): accent square with a partly filled ring. */
@Composable
fun Logo(size: Dp = 26.dp) {
    val c = NutriTheme.colors
    Canvas(Modifier.size(size)) {
        val u = this.size.width / 32f
        drawRoundRect(c.accent, cornerRadius = androidx.compose.ui.geometry.CornerRadius(9 * u))
        val r = 8.5f * u
        val tl = Offset(16 * u - r, 16 * u - r)
        drawCircle(c.onAccent.copy(alpha = 0.35f), r, Offset(16 * u, 16 * u), style = Stroke(3.2f * u))
        // From 12 o'clock clockwise to about 7 o'clock, like the SVG path.
        drawArc(c.onAccent, -90f, 251f, false, tl, Size(2 * r, 2 * r), style = Stroke(3.2f * u, cap = StrokeCap.Round))
    }
}

data class TabItem(val route: String, val label: String, val icon: ImageVector)

/** Bottom tab bar: two tabs, the add button in the thumb's natural spot, two tabs. */
@Composable
fun TabBar(tabs: List<TabItem>, current: String?, onSelect: (String) -> Unit, onAdd: () -> Unit) {
    val c = NutriTheme.colors
    Column(Modifier.background(c.surface)) {
        Box(Modifier.fillMaxWidth().height(1.dp).background(c.border))
        Row(Modifier.fillMaxWidth().navigationBarsPadding().height(60.dp), verticalAlignment = Alignment.CenterVertically) {
            @Composable
            fun RowScope.tab(tab: TabItem) {
                val on = tab.route == current
                val color = if (on) c.accent else c.textSecondary
                Column(
                    Modifier.weight(1f).fillMaxSize().clickable { onSelect(tab.route) },
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center,
                ) {
                    Icon(tab.icon, tab.label, tint = color, modifier = Modifier.size(20.dp))
                    Spacer(Modifier.height(4.dp))
                    Text(tab.label, style = NutriTheme.type.caption.copy(fontWeight = NutriTheme.type.bodyMedium.fontWeight), color = color)
                }
            }
            tabs.take(2).forEach { tab(it) }
            Box(Modifier.weight(1f), contentAlignment = Alignment.Center) {
                Box(
                    Modifier.size(46.dp).clip(androidx.compose.foundation.shape.CircleShape).background(c.accent).clickable(onClick = onAdd),
                    contentAlignment = Alignment.Center,
                ) { Icon(Lucide.Plus, "Add food", tint = c.onAccent, modifier = Modifier.size(22.dp)) }
            }
            tabs.drop(2).forEach { tab(it) }
        }
    }
}

/**
 * Scaffold for pushed screens: back button, title, optional actions, scrollable content in [content].
 * Pages of cards sit on the canvas; [plain] pages (search, chat) keep one flat surface and a hairline.
 */
@Composable
fun SubPage(
    title: String,
    onBack: () -> Unit,
    actions: @Composable RowScope.() -> Unit = {},
    snackbar: SnackbarHostState? = null,
    bottomBar: @Composable () -> Unit = {},
    plain: Boolean = false,
    content: @Composable (PaddingValues) -> Unit,
) {
    val c = NutriTheme.colors
    val bg = if (plain) c.surface else c.bgSunken
    // Edge-to-edge: the content keeps clear of the navigation bar (or the bottom bar, when there is one),
    // and the whole page stays above the keyboard. Children that pad for these insets themselves get 0.
    Scaffold(
        modifier = Modifier.imePadding(),
        containerColor = bg,
        contentWindowInsets = WindowInsets.navigationBars.only(WindowInsetsSides.Bottom + WindowInsetsSides.Horizontal),
        topBar = {
            Column(Modifier.background(bg).statusBarsPadding()) {
                Row(Modifier.fillMaxWidth().height(52.dp).padding(horizontal = Space.S2), verticalAlignment = Alignment.CenterVertically) {
                    NIconButton(Lucide.ArrowLeft, "Back", onBack, tint = c.text)
                    Spacer(Modifier.width(Space.S1))
                    Text(title, style = NutriTheme.type.cardTitle.copy(fontSize = NutriTheme.type.title.fontSize), color = c.text, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                    Row(horizontalArrangement = Arrangement.spacedBy(Space.S1), verticalAlignment = Alignment.CenterVertically, content = actions)
                    Spacer(Modifier.width(Space.S2))
                }
                if (plain) Box(Modifier.fillMaxWidth().height(1.dp).background(c.border))
            }
        },
        bottomBar = bottomBar,
        snackbarHost = { if (snackbar != null) SnackbarHost(snackbar) },
        content = content,
    )
}

/** Primary action docked above the keyboard/navigation bar on editor screens. */
@Composable
fun BottomAction(content: @Composable RowScope.() -> Unit) {
    val c = NutriTheme.colors
    Column(Modifier.background(c.surface)) {
        Box(Modifier.fillMaxWidth().height(1.dp).background(c.border))
        Row(
            Modifier.fillMaxWidth().navigationBarsPadding().padding(horizontal = Space.S4, vertical = Space.S3),
            horizontalArrangement = Arrangement.spacedBy(Space.S2),
            verticalAlignment = Alignment.CenterVertically,
            content = content,
        )
    }
}
