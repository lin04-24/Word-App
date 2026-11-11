package com.example.moci

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import org.junit.Rule
import org.junit.Test

class HomeScreenTest {
    @get:Rule
    val composeRule = createAndroidComposeRule<MainActivity>()

    @Test
    fun showsImportAndReviewEntryPoints() {
        composeRule.onNodeWithText("看中文，写英文").assertIsDisplayed()
        composeRule.onNodeWithText("导入 TXT").assertIsDisplayed()
        composeRule.onNodeWithText("开始默写").assertIsDisplayed()
    }
}
