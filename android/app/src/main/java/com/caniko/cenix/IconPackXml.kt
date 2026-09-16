package com.caniko.cenix

import android.content.ComponentName
import org.xmlpull.v1.XmlPullParser

internal data class IconPackIndex(val mappings: Map<String, String>, val drawables: Set<String>)

internal object IconPackXml {
    const val MAX_BYTES = 4 * 1024 * 1024
    private const val MAX_EVENTS = 200_000
    private const val MAX_ITEMS = 50_000

    fun resourceName(value: String?): String? = value?.takeIf {
        it.length in 1..256 && it.all { c -> c.isLetterOrDigit() && c.code < 128 || c == '_' }
    }

    // Indexing policy is canonical in Rust (cenix-core iconpack module). The
    // pure-Kotlin parser below exists solely for omitNative builds and emergency
    // mode (ADR 0009). Both paths share test vectors with the Rust suite.
    fun parseBytes(bytes: ByteArray): IconPackIndex {
        require(bytes.size <= MAX_BYTES) { "icon XML size" }
        return try {
            com.caniko.cenix.uniffi.parseIconpackIndex(bytes).let { index ->
                IconPackIndex(
                    index.mappings.associate { it.component to it.drawable },
                    index.drawables.toSet(),
                )
            }
        } catch (e: com.caniko.cenix.uniffi.IconPackException) {
            // Native input rejection is authoritative: retrying another parser
            // could only admit what Rust refused.
            throw IllegalArgumentException("icon XML rejected", e)
        } catch (_: Throwable) {
            // Native library unavailable (omitNative/emergency): fall back.
            parseFallback(bytes)
        }
    }

    internal fun parseFallback(bytes: ByteArray): IconPackIndex {
        require(bytes.size <= MAX_BYTES) { "icon XML size" }
        return parse(android.util.Xml.newPullParser().apply {
            setInput(java.io.ByteArrayInputStream(bytes), "UTF-8")
        })
    }

    fun parse(parser: XmlPullParser): IconPackIndex {
        val mappings = linkedMapOf<String, String>()
        val drawables = linkedSetOf<String>()
        var events = 0
        var items = 0
        var roots = 0
        var event = parser.eventType
        while (event != XmlPullParser.END_DOCUMENT) {
            require(++events <= MAX_EVENTS && parser.depth <= 8) { "icon XML bounds" }
            require(event != XmlPullParser.DOCDECL) { "icon XML doctype" }
            if (event == XmlPullParser.START_TAG) {
                require(parser.attributeCount <= 32) { "icon XML attributes" }
                // Mirror the Rust single-root document contract.
                if (parser.depth <= 1) require(++roots <= 1) { "icon XML roots" }
                if (parser.name == "item") {
                    require(++items <= MAX_ITEMS) { "icon XML items" }
                    val name = resourceName(parser.getAttributeValue(null, "drawable"))
                    if (name != null) {
                        drawables.add(name)
                        val raw = parser.getAttributeValue(null, "component")
                        if (raw != null && raw.length <= 512) {
                            ComponentName.unflattenFromString(raw.removePrefix("ComponentInfo{").removeSuffix("}"))
                                ?.let { mappings.putIfAbsent(it.flattenToString(), name) }
                        }
                    }
                }
            }
            if (event == XmlPullParser.TEXT && parser.depth == 0 && parser.text.isNotBlank()) {
                throw IllegalArgumentException("icon XML text outside root")
            }
            event = parser.nextToken()
        }
        require(roots == 1) { "icon XML root" }
        return IconPackIndex(mappings, drawables)
    }
}
