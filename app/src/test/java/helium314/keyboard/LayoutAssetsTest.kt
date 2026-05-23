// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard

import helium314.keyboard.keyboard.internal.keyboard_parser.LayoutParser
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class LayoutAssetsTest {
    private val root = "src/main/assets/layouts"
    private val skippedMain = setOf("lao.json", "pcqwerty.json", "thai.json")

    @Test fun `main layouts parse and have valid row counts and top row width`() {
        val files = java.io.File("$root/main").listFiles()
            ?.filter { (it.extension == "txt" || it.extension == "json") && it.name !in skippedMain }
            ?.sortedBy { it.name }
            ?: emptyList()
        files.forEach { file ->
            val rows = parseRows(file.readText(), file.extension)
            assertTrue(rows.size in 3..6, "${file.name} has invalid row count ${rows.size}")
            assertEquals(10, rows.first().size, "${file.name} top row must have 10 keys")
        }
    }

    @Test fun `symbols layouts parse and have exactly 4 rows with 10-key top row`() {
        listOf("symbols", "more_symbols").forEach { folder ->
            val files = java.io.File("$root/$folder").listFiles()
                ?.filter { it.extension == "txt" }
                ?.sortedBy { it.name }
                ?: emptyList()
            files.forEach { file ->
                val rows = LayoutParser.parseSimpleString(file.readText())
                assertEquals(4, rows.size, "${file.name} should have exactly 4 rows")
                assertEquals(10, rows.first().size, "${file.name} top row must have 10 keys")
            }
        }
    }

    @Test fun `skipped main layouts remain at four rows`() {
        skippedMain.forEach { name ->
            val file = java.io.File("$root/main/$name")
            val rows = parseRows(file.readText(), file.extension)
            assertEquals(4, rows.size, "$name should remain 4 rows")
        }
    }

    private fun parseRows(content: String, extension: String) =
        if (extension == "json") LayoutParser.parseJsonString(content)
        else LayoutParser.parseSimpleString(content)
}
