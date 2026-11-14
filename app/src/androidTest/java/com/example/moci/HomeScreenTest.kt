package com.example.moci

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performScrollToNode
import org.junit.Rule
import org.junit.Test

class HomeScreenTest {
    @get:Rule
    val composeRule = createAndroidComposeRule<MainActivity>()

    @Test
    fun showsImportAndReviewEntryPoints() {
        composeRule.onNodeWithText("看中文，写英文").assertIsDisplayed()
        composeRule.onNodeWithText("导入 TXT").performScrollTo().assertIsDisplayed()
        composeRule.onNodeWithText("开始默写").assertIsDisplayed()
        composeRule.onNodeWithText("设置").performClick()
        composeRule.onNodeWithText("启动时检查更新").performScrollTo().assertIsDisplayed()
        composeRule.onNodeWithText("离线英汉词典").assertIsDisplayed()
        composeRule.onNodeWithText("下载离线词典").performScrollTo().assertIsDisplayed()
        composeRule.onNodeWithText("返回").performClick()
        composeRule.onNodeWithText("学习").performClick()
        composeRule.onNodeWithText("看中文，写英文").assertIsDisplayed()
    }

    @Test
    fun exposesVocabularyStatisticsAndLearningGoalControls() {
        composeRule.onNodeWithText("我的词库").assertDoesNotExist()
        composeRule.onNodeWithText("词库").performClick()
        composeRule.onNodeWithText("添加单词").assertIsDisplayed()
        composeRule.onNodeWithText("添加单词").performClick()
        composeRule.onNodeWithText("从离线词典填入释义").assertIsNotEnabled()
        composeRule.onNodeWithText("取消").performClick()
        composeRule.onNodeWithText("导出 TXT").assertIsDisplayed()
        composeRule.onNodeWithText("统计").performClick()
        composeRule.onNodeWithText("掌握情况").assertIsDisplayed()
        composeRule.onNodeWithTag("statisticsList").performScrollToNode(hasText("学习日历"))
        composeRule.onNodeWithText("学习日历").assertIsDisplayed()
        composeRule.onNodeWithText("设置").performClick()
        composeRule.onNodeWithText("每日目标（不同单词数）").assertIsDisplayed()
        composeRule.onNodeWithText("导出完整备份").assertIsDisplayed()
        composeRule.onNodeWithText("返回").performClick()
        composeRule.onNodeWithText("学习").performClick()
    }
}
