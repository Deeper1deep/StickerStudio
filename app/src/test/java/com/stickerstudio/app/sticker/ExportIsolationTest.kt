package com.stickerstudio.app.sticker

import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Test

/** Guards the rule that the Export section is reachable only from its own top-level entry. */
class ExportIsolationTest {

    private val screens = File("src/main/java/com/stickerstudio/app/ui/screens")
    private val forbidden = Regex("""ExportScreen|ExportViewModel|ExportQueue|SelectionOrder|Routes\.EXPORT|R\.string\.export_(?!zip)""")

    @Test fun otherScreensDoNotTouchTheExportSection() {
        val names = listOf("PackDetailScreen.kt", "CreateScreen.kt", "EditorScreen.kt", "EditorViewModel.kt", "AnimatedScreen.kt", "HomeScreen.kt")
        for (n in names) {
            val f = File(screens, n)
            assertTrue("missing ${f.absolutePath}", f.isFile)
            val hit = forbidden.find(f.readText())
            assertTrue("$n references the export section: ${hit?.value}", hit == null)
        }
    }

    @Test fun exportScreenDoesNotDependOnPackManagementScreens() {
        val text = File(screens, "ExportScreen.kt").readText()
        assertTrue(!text.contains("PackDetailScreen") && !text.contains("HomeScreen") && !text.contains("EditorScreen"))
    }
}
