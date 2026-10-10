package org.rankoffline.app

import java.io.File
import javax.xml.parsers.DocumentBuilderFactory
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.w3c.dom.Element

class LocalizationResourcesTest {
    private val qualifiers = listOf("es", "de", "fr", "ru", "ja", "zh-rCN", "eu", "it")
    private val placeholder = Regex("%(?:\\d+\\$)?[-#+ 0,(]*\\d*(?:\\.\\d+)?[dfs]")

    @Test
    fun everyLocaleHasExactlyTheTranslatableBaseKeysAndCompatiblePlaceholders() {
        val base = readResources(resourceFile("values"))
        val expectedStrings = base.strings.filterValues { it.translatable }.mapValues { it.value.value }
        val expectedPlurals = base.plurals

        qualifiers.forEach { qualifier ->
            val localized = readResources(resourceFile("values-$qualifier"))
            assertEquals("String keys differ for $qualifier", expectedStrings.keys, localized.strings.keys)
            assertEquals("Plural keys differ for $qualifier", expectedPlurals.keys, localized.plurals.keys)

            expectedStrings.forEach { (key, value) ->
                assertEquals(
                    "Placeholder mismatch for $qualifier/$key",
                    placeholder.findAll(value).map { it.value }.sorted().toList(),
                    placeholder.findAll(localized.strings.getValue(key).value).map { it.value }.sorted().toList()
                )
            }
            expectedPlurals.forEach { (key, quantities) ->
                val expectedSignature = placeholderSignature(quantities.getValue("other"))
                assertTrue("Plural $qualifier/$key must define other", localized.plurals.getValue(key).containsKey("other"))
                localized.plurals.getValue(key).forEach { (quantity, value) ->
                    assertEquals(
                        "Placeholder mismatch for $qualifier/$key[$quantity]",
                        expectedSignature,
                        placeholderSignature(value)
                    )
                }
            }
        }
    }

    @Test
    fun localeConfigAndSupportedLanguageContainTheSameNineTags() {
        val document = parse(resourceRoot().resolve("xml/locales_config.xml"))
        val configured = document.getElementsByTagName("locale").asElements().map {
            it.getAttribute("android:name")
        }
        val supported = SupportedLanguage.entries.map { it.languageTag }

        assertEquals(listOf("en", "es", "de", "fr", "ru", "ja", "zh-CN", "eu", "it"), supported)
        assertEquals(supported, configured)
        assertEquals(SupportedLanguage.ENGLISH, SupportedLanguage.DEFAULT)
    }

    private fun placeholderSignature(value: String) =
        placeholder.findAll(value).map { it.value }.sorted().toList()

    private fun readResources(file: File): ResourceSet {
        val root = parse(file).documentElement
        val strings = root.getElementsByTagName("string").asElements().associate { element ->
            element.getAttribute("name") to LocalizedString(
                value = element.textContent,
                translatable = element.getAttribute("translatable") != "false"
            )
        }
        val plurals = root.getElementsByTagName("plurals").asElements().associate { plural ->
            plural.getAttribute("name") to plural.getElementsByTagName("item").asElements().associate { item ->
                item.getAttribute("quantity") to item.textContent
            }
        }
        return ResourceSet(strings, plurals)
    }

    private fun resourceFile(directory: String) = resourceRoot().resolve("$directory/strings.xml")

    private fun resourceRoot(): File = listOf(
        File("src/main/res"),
        File("app/src/main/res")
    ).firstOrNull(File::isDirectory) ?: error("Unable to locate Android resources")

    private fun parse(file: File) = DocumentBuilderFactory.newInstance().apply {
        isNamespaceAware = true
    }.newDocumentBuilder().parse(file)

    private fun org.w3c.dom.NodeList.asElements(): List<Element> =
        (0 until length).map { item(it) as Element }

    private data class LocalizedString(val value: String, val translatable: Boolean)
    private data class ResourceSet(
        val strings: Map<String, LocalizedString>,
        val plurals: Map<String, Map<String, String>>
    )
}
