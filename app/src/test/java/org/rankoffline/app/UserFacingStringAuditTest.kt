package org.rankoffline.app

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Test

class UserFacingStringAuditTest {
    @Test
    fun composeDoesNotIntroduceNewHardcodedTextOrAccessibilityLabels() {
        val source = locate("src/main/java/org/rankoffline/app/MainActivity.kt").readText()
        val textLiterals = Regex("Text\\(\\s*\"([^\"]*)\"")
            .findAll(source)
            .map { it.groupValues[1] }
            .filterNot { literal ->
                literal == "RankOffline" ||
                    literal == "${'$'}{rank}." ||
                    literal == "%.2f" ||
                    literal == "${'$'}{category.weight.toInt()}%" ||
                    literal == "${'$'}value — ${'$'}{scoreLabel(value.toDouble())}"
            }
            .toList()
        val accessibilityLiterals = Regex("contentDescription\\s*=\\s*\"([^\"]+)\"")
            .findAll(source)
            .map { it.groupValues[1] }
            .toList()

        assertEquals("Move new user-facing Compose text to string resources", emptyList<String>(), textLiterals)
        assertEquals("Move new accessibility labels to string resources", emptyList<String>(), accessibilityLiterals)
    }

    private fun locate(relativePath: String): File = listOf(
        File(relativePath),
        File("app/$relativePath")
    ).firstOrNull(File::isFile) ?: error("Unable to locate $relativePath")
}
