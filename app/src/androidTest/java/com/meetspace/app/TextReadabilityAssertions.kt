package com.meetspace.app

import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.text.MultiParagraph
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.unit.Constraints
import org.junit.Assert.*

/** Use an unmerged Text node so its bounds are the text box, rather than its containing button. */
internal fun assertReadableTextAtActualBounds(node: SemanticsNodeInteraction, expected: String, singleLine: Boolean = false) {
    node.assertIsDisplayed().assertTextEquals(expected)
    val results = mutableListOf<TextLayoutResult>()
    node.performSemanticsAction(SemanticsActions.GetTextLayoutResult) { it(results) }
    assertEquals("Expected one measured text layout for $expected", 1, results.size)
    val reported = results.single()
    assertEquals("Expected the complete label", expected, reported.layoutInput.text.text)
    assertEquals("Measure the actual unmerged Text node", node.fetchSemanticsNode().size, reported.size)

    // Compose 1.9.4 ParagraphLayoutCache.slowCreateTextLayoutResultOrNull recreates MultiParagraph
    // using the parent's maximum constraints, but keeps the original intrinsic-width layoutSize.
    // didOverflowWidth then compares these different boxes. Reuse its exact text/font intrinsics,
    // maxLines and overflow mode at the actual Text size instead of relaxing overflow assertions.
    // Source: Google Maven foundation-android-1.9.4-sources.jar, ParagraphLayoutCache.kt:366–402.
    val paragraph = MultiParagraph(
        intrinsics = reported.multiParagraph.intrinsics,
        constraints = Constraints(maxWidth = reported.size.width, maxHeight = reported.size.height),
        maxLines = reported.layoutInput.maxLines,
        overflow = reported.layoutInput.overflow,
    )
    val actual = TextLayoutResult(reported.layoutInput, paragraph, reported.size)
    val description = "$expected: actual=${actual.size}, paragraph=${paragraph.width}×${paragraph.height}, " +
        "lines=${actual.lineCount}, reportedParagraph=${reported.multiParagraph.width}×${reported.multiParagraph.height}, " +
        "parent=${reported.layoutInput.constraints}"
    assertFalse("Clipped text: $description", actual.hasVisualOverflow)
    assertTrue("Missing lines: $description", actual.lineCount > 0)
    if (singleLine) assertEquals("A short control label must stay on one line: $description", 1, actual.lineCount)
    assertEquals("The full text must fit: $description", expected.length, actual.getLineEnd(actual.lineCount - 1, visibleEnd = false))
    repeat(actual.lineCount) { line ->
        assertFalse("Ellipsized line $line: $description", actual.isLineEllipsized(line))
        assertTrue("Line $line exceeds the left edge: $description", actual.getLineLeft(line) >= 0f)
        assertTrue("Line $line exceeds the right edge: $description", actual.getLineRight(line) <= actual.size.width)
    }
}
