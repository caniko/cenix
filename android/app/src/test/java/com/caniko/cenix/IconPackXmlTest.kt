package com.caniko.cenix

import android.util.Xml
import java.io.StringReader
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = android.app.Application::class)
class IconPackXmlTest {
    private fun parse(xml: String) = IconPackXml.parse(Xml.newPullParser().apply { setInput(StringReader(xml)) })

    @Test fun mappingsExpandActivitiesWithoutInventingPackageFallbacks() {
        val result = parse("""<resources>
            <item component="ComponentInfo{app.pkg/.Main}" drawable="main"/>
            <item component="ComponentInfo{app.pkg/.Other}" drawable="other"/>
            <item component="ComponentInfo{app.pkg/.Main}" drawable="duplicate"/>
            <item component="ComponentInfo{app.pkg/.Bad}" drawable="../outside"/>
            <calendar component="ComponentInfo{app.pkg/.Calendar}" prefix="day_"/>
        </resources>""")
        assertEquals(mapOf("app.pkg/app.pkg.Main" to "main", "app.pkg/app.pkg.Other" to "other"), result.mappings)
        assertFalse(result.mappings.containsKey("app.pkg"))
    }

    @Test fun drawableCatalogIsCompleteBeyondOld400Limit() {
        val result = parse("<resources><category title=\"Apps\"/>" +
            (0..1200).joinToString("") { "<item drawable=\"icon_$it\"/>" } + "<item drawable=\"icon_0\"/></resources>")
        assertEquals(1201, result.drawables.size)
        assertTrue(result.drawables.contains("icon_1200"))
        assertTrue(result.mappings.isEmpty())
        assertEquals(1201, IconPickerDialog.matching(result.drawables.sorted(), "").size)
        assertEquals(listOf("icon_1200"), IconPickerDialog.matching(result.drawables.sorted(), " ICON_1200 "))
    }

    @Test fun missingPackAndInvalidNamesFailClosedWithoutPackageLookup() {
        val context = androidx.test.core.app.ApplicationProvider.getApplicationContext<android.content.Context>()
        val packs = com.caniko.cenix.IconPackManager(context)
        assertNull(packs.drawable("com.example.absent", "icon"))
        assertNull(packs.drawable("com.example.absent", "../outside"))
        assertTrue(packs.catalog("com.example.absent").isEmpty())
        assertTrue(packs.mapping("com.example.absent").isEmpty())
    }

    @Test fun byteAndParserPathsAgree() {
        // In unit tests (no native lib) parseBytes takes the Kotlin fallback; with the
        // host .so loaded it takes the Rust path. Either way both entries must agree.
        val vectors = listOf(
            """<resources><item component="ComponentInfo{app.pkg/.Main}" drawable="main"/>""" +
                """<item drawable="plain"/><calendar component="ComponentInfo{app.pkg/.C}" prefix="d_"/></resources>""",
            "<resources><category title=\"Apps\"/><item drawable=\"icon_1\"/><item drawable=\"icon_2\"/></resources>",
            "<resources><!-- c --><item drawable=\"ok\"/><![CDATA[<item drawable=\"no\"/>]]></resources>",
        )
        vectors.forEach { xml ->
            val bytes = xml.toByteArray(Charsets.UTF_8)
            assertEquals(parse(xml), IconPackXml.parseBytes(bytes))
            assertEquals(parse(xml), IconPackXml.parseFallback(bytes))
        }
        assertThrows(Exception::class.java) {
            IconPackXml.parseBytes("<resources><item".toByteArray(Charsets.UTF_8))
        }
    }

    @Test fun singleRootContractMatchesRust() {
        for (xml in listOf("<resources/><resources/>", "<resources/>trailing", "  <resources/>  ")) {
            val bytes = xml.toByteArray(Charsets.UTF_8)
            if (xml.contains("trailing") || xml.contains("/><")) {
                assertThrows(Exception::class.java) { parse(xml) }
                assertThrows(Exception::class.java) { IconPackXml.parseBytes(bytes) }
            } else {
                // Whitespace around the single root stays acceptable on both paths.
                assertEquals(parse(xml), IconPackXml.parseBytes(bytes))
            }
        }
    }

    @Test fun malformedDeepAndDoctypeDocumentsFailClosed() {
        for (xml in listOf("<resources><item", "<resources>" + "<x>".repeat(20) + "</x>".repeat(20) + "</resources>",
            "<!DOCTYPE resources [<!ENTITY x SYSTEM 'file:///secret'>]><resources/>")) {
            assertThrows(Exception::class.java) { parse(xml) }
        }
    }
}
