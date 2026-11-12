package com.nutrime.app

import android.graphics.Bitmap
import androidx.compose.ui.test.hasAnyDescendant
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.performScrollToIndex
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.performTextInput
import androidx.test.core.app.ApplicationProvider
import com.nutrime.app.data.remote.ChatBlock
import com.nutrime.app.data.remote.ChatMessage
import com.nutrime.app.data.remote.Proposal
import com.nutrime.app.data.remote.ProposalItem
import com.nutrime.app.model.Meal
import com.nutrime.app.model.Profile
import com.nutrime.app.model.Sex
import kotlinx.coroutines.runBlocking
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File
import java.time.LocalDate

/** Drives the real UI under Robolectric and saves screenshots to build/screenshots. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "w400dp-h860dp-xhdpi")
class ScreenshotTest {
    @get:Rule
    val rule = createAndroidComposeRule<MainActivity>()

    private val dir = File(System.getProperty("screenshotDir") ?: "build/screenshots").apply { mkdirs() }

    private fun shot(name: String) {
        rule.waitForIdle()
        // Draw the window's view hierarchy directly; Compose's captureToImage() waits for a
        // redraw that Robolectric's paused looper never delivers.
        rule.runOnUiThread {
            val view = rule.activity.window.decorView
            val bmp = Bitmap.createBitmap(view.width, view.height, Bitmap.Config.ARGB_8888)
            view.draw(android.graphics.Canvas(bmp))
            File(dir, "$name.png").outputStream().use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
        }
    }

    private fun click(text: String) {
        rule.onAllNodes(hasClickAction() and (hasText(text) or hasAnyDescendant(hasText(text))), useUnmergedTree = true)
            .onFirst().performSemanticsAction(SemanticsActions.OnClick)
    }

    private fun waitForText(text: String) {
        rule.waitUntil(10_000) { rule.onAllNodesWithText(text, substring = true).fetchSemanticsNodes().isNotEmpty() }
    }

    @Test
    fun walkThroughApp() {
        val c = (ApplicationProvider.getApplicationContext<NutriMeApplication>()).container
        runBlocking {
            c.profiles.save(Profile(sex = Sex.FEMALE, birthYear = 1992, heightCm = 168.0, weightKg = 64.0, goalKgPerWeek = -0.25, configured = true))
            val today = LocalDate.now()
            suspend fun log(q: String, meal: Meal, grams: Double, date: LocalDate = today) {
                val f = c.foods.searchLocal(q).first()
                c.diary.add(date, meal, f, com.nutrime.app.model.Serving("g", 1.0), grams)
            }
            for (d in 0L..6L) {
                val date = today.minusDays(d)
                log("oats", Meal.BREAKFAST, 60.0 + d * 5, date)
                log("milk whole", Meal.BREAKFAST, 200.0, date)
                log("bananas raw", Meal.BREAKFAST, 118.0, date)
                log("chicken breast roasted", Meal.LUNCH, 150.0, date)
                log("rice white cooked", Meal.LUNCH, 180.0, date)
                log("broccoli raw", Meal.DINNER, 120.0 - d * 10, date)
                log("salmon atlantic cooked", Meal.DINNER, 140.0, date)
                if (d % 2 == 0L) log("almonds", Meal.SNACKS, 30.0, date)
                c.weights.add(date, 64.0 + d * 0.15, null)
            }
            // Foods eaten only on earlier days, so today's gaps have something to suggest.
            log("egg whole hard-boiled", Meal.BREAKFAST, 100.0, today.minusDays(2))
            log("spinach raw", Meal.LUNCH, 85.0, today.minusDays(3))
        }
        waitForText("Remaining")
        shot("01-diary")

        // Swipe the summary: macros, then nutrient gaps.
        runCatching {
            val pager = rule.onAllNodes(androidx.compose.ui.test.hasScrollToIndexAction())[1]
            pager.performScrollToIndex(1); shot("01b-summary-macros")
            pager.performScrollToIndex(2); shot("01c-summary-gaps")
            pager.performScrollToIndex(0)
        }.onFailure { println("pager swipe skipped: ${it.message}") }

        // Tap the summary for every nutrient of the day.
        click("Consumed")
        waitForText("Vitamin A")
        shot("01d-nutrients")
        rule.activityRule.scenario.onActivity { it.onBackPressedDispatcher.onBackPressed() }
        waitForText("Remaining")

        rule.onNodeWithContentDescription("Add food").performSemanticsAction(SemanticsActions.OnClick)
        waitForText("Quick add calories")
        shot("02-add-sheet")
        click("Add food")
        waitForText("Oats")
        shot("02a-search-recent")
        rule.onNode(hasSetTextAction()).performTextInput("chicken breast")
        waitForText("Chicken, broilers")
        shot("02-search")

        rule.onAllNodesWithText("Chicken, broilers", substring = true).onFirst().performSemanticsAction(SemanticsActions.OnClick)
        waitForText("Nutrition")
        shot("03-food-detail")
        rule.activityRule.scenario.onActivity { it.onBackPressedDispatcher.onBackPressed() }
        rule.activityRule.scenario.onActivity { it.onBackPressedDispatcher.onBackPressed() }

        click("Trends")
        waitForText("Daily average")
        shot("04-trends")
        click("Weight")
        waitForText("Current")
        shot("04b-trends-weight")
        click("Nutrients")
        waitForText("Targets met")
        shot("04c-trends-nutrients")
        click("Calories")

        click("Profile")
        waitForText("Daily target")
        shot("05-profile")
        click("You")
        waitForText("Birth year")
        shot("05b-profile-you")
        click("App")
        waitForText("Appearance")
        shot("05c-profile-app")
        click("Targets")

        click("Foods")
        waitForText("New recipe")
        shot("06-my-foods")

        // Dark theme, from the same design tokens.
        runBlocking { c.settings.setAppearance(mode = com.nutrime.app.ui.theme.ThemeMode.DARK) }
        click("Diary")
        waitForText("Remaining")
        shot("07-diary-dark")
        click("Trends")
        waitForText("Daily average")
        shot("08-trends-dark")
        click("Profile")
        waitForText("Daily target")
        shot("09-profile-dark")
    }

    @Test
    fun assistant() {
        val c = (ApplicationProvider.getApplicationContext<NutriMeApplication>()).container
        runBlocking { c.settings.setLogin(BuildConfig.SYNC_SERVER_URL, "me@example.com", "token", "user") }
        waitForText("Today")
        rule.onNodeWithContentDescription("Assistant").performSemanticsAction(SemanticsActions.OnClick)
        waitForText("Ask about your nutrition")
        shot("10-assistant-empty")

        c.assistant.showForTest(
            com.nutrime.app.data.AssistantState(
                conversationId = "c1",
                title = "Breakfast",
                messages = listOf(
                    ChatMessage("user", listOf(ChatBlock.Text("I had 2 eggs and a slice of toast with butter for breakfast"))),
                    ChatMessage("assistant", listOf(
                        ChatBlock.Text("Here's what I found:"),
                        ChatBlock.Tool("search_foods", "Searching foods for “egg whole cooked”"),
                        ChatBlock.Tool("search_foods", "Searching foods for “bread whole wheat toasted”"),
                        ChatBlock.ProposalCard(Proposal("p1", "Log 3 items · 341 kcal", listOf(
                            ProposalItem("Egg, whole, cooked, hard-boiled", "100 g · 155 kcal · Breakfast"),
                            ProposalItem("Bread, whole-wheat, toasted", "1 × slice (28 g) · 88 kcal · Breakfast"),
                            ProposalItem("Butter, salted", "14 g · 100 kcal · Breakfast"),
                        ), "pending")),
                        ChatBlock.Text("Tap **Confirm** to add them. That puts you at **1,120 kcal** with *28 g protein* so far:\n\n- Protein is on track\n- Fiber is low, at 4 g of 25 g"),
                    )),
                    ChatMessage("user", listOf(ChatBlock.Text("What am I low on today?"))),
                    ChatMessage("assistant", listOf(ChatBlock.Tool("get_day", "Reading your diary"))),
                ),
                streaming = true,
            )
        )
        waitForText("Log 3 items")
        shot("11-assistant-chat")
    }
}
