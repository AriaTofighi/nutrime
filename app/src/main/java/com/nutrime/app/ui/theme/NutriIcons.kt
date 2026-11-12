package com.nutrime.app.ui.theme

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.addPathNodes
import androidx.compose.ui.unit.dp

/**
 * NutriMe's own icons, drawn on Lucide's 24×24 stroked grid so they sit well next to them.
 * The web app has the same shapes in web/src/components/icons.tsx.
 */
object NutriIcons {
    /** The assistant: an apple that is also a chat bubble, with a leaf. */
    val Assistant: ImageVector by lazy {
        icon(
            "assistant",
            "M12 7.4c1.2-.9 2.5-1.4 3.9-1.4 2.9 0 4.6 2.5 4.6 5.8 0 4.7-3.6 8.7-8.5 8.7-1.5 0-2.9-.4-4.1-1L4 20.6l1-3.6C4 15.6 3.5 13.8 3.5 11.8 3.5 8.5 5.2 6 8.1 6c1.4 0 2.7.5 3.9 1.4Z",
            "M12 7.4V5.5",
            "M12 5.5c0-1.9 1.4-3.2 3.5-3.2 0 1.9-1.4 3.2-3.5 3.2Z",
        )
    }

    /** Recipes: a mixing bowl with a spoon. */
    val Recipe: ImageVector by lazy {
        icon(
            "recipe",
            "M3.5 12h17a8.5 8.5 0 0 1-17 0Z",
            "M13.5 12l4.6-6.6",
            "M18.1 5.4c-.6-.9-.3-2.2.7-2.8s2.2-.4 2.7.5c.6.9.3 2.2-.7 2.8s-2.2.4-2.7-.5Z",
        )
    }

    private fun icon(name: String, vararg paths: String): ImageVector =
        ImageVector.Builder(name, 24.dp, 24.dp, 24f, 24f).apply {
            paths.forEach { d ->
                addPath(
                    pathData = addPathNodes(d),
                    stroke = SolidColor(Color.Black),
                    strokeLineWidth = 2f,
                    strokeLineCap = StrokeCap.Round,
                    strokeLineJoin = StrokeJoin.Round,
                )
            }
        }.build()
}
