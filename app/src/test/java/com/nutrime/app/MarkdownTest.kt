package com.nutrime.app

import androidx.compose.ui.graphics.Color
import com.nutrime.app.ui.assistant.MdBlock
import com.nutrime.app.ui.assistant.inlineMarkdown
import com.nutrime.app.ui.assistant.parseMarkdown
import org.junit.Assert.assertEquals
import org.junit.Test

class MarkdownTest {
    @Test
    fun blocks() {
        val md = "## Today\nYou're at **1,200 kcal**,\nwell under.\n\n- Protein: 80 g\n* Fiber: 12 g\n  - nested\n1. Eat beans\n2) Add spinach"
        assertEquals(
            listOf(
                MdBlock.Heading("Today"),
                MdBlock.Paragraph("You're at **1,200 kcal**, well under."),
                MdBlock.Item("•", "Protein: 80 g", 0),
                MdBlock.Item("•", "Fiber: 12 g", 0),
                MdBlock.Item("•", "nested", 1),
                MdBlock.Item("1.", "Eat beans", 0),
                MdBlock.Item("2.", "Add spinach", 0),
            ),
            parseMarkdown(md),
        )
    }

    @Test
    fun inline() {
        val s = inlineMarkdown("**Bold**, *italic*, _also_, `code`, 2 * 3 * 4 and snake_case_name", Color.Gray)
        assertEquals("Bold, italic, also, code, 2 * 3 * 4 and snake_case_name", s.text)
        assertEquals(4, s.spanStyles.size)
    }
}
