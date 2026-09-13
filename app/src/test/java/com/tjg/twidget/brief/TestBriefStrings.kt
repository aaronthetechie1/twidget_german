package com.tjg.twidget.brief

import com.tjg.twidget.R
import java.io.File
import java.util.Locale
import javax.xml.parsers.DocumentBuilderFactory
import org.w3c.dom.Element

/**
 * Resolves Brief copy from the default `values/strings.xml` on disk so JVM tests
 * exercise the real English resources without an Android runtime. Resource ids
 * are mapped back to names through the generated `R` class.
 */
class TestBriefStrings(override val locale: Locale = Locale.US) : BriefStrings {
    override fun text(id: Int, vararg args: Any): String {
        val pattern = strings[name(R.string::class.java, id)]
            ?: error("Missing <string> for id $id in ${resourceDir.path}")
        return format(pattern, args)
    }

    override fun quantityText(id: Int, quantity: Int, vararg args: Any): String {
        val name = name(R.plurals::class.java, id)
        val items = plurals[name] ?: error("Missing <plurals> for $name in ${resourceDir.path}")
        val pattern = (if (quantity == 1) items["one"] else null) ?: items["other"]
            ?: error("<plurals name=\"$name\"> has no 'other' item")
        return format(pattern, args)
    }

    private fun format(pattern: String, args: Array<out Any>): String =
        if (args.isEmpty()) pattern else String.format(locale, pattern, *args)

    private fun name(owner: Class<*>, id: Int): String =
        owner.fields.firstOrNull { it.type == Int::class.javaPrimitiveType && it.getInt(null) == id }?.name
            ?: error("No ${owner.simpleName} field has id $id")

    private companion object {
        val resourceDir: File = listOf("src/main/res/values", "app/src/main/res/values")
            .map(::File)
            .firstOrNull(File::isDirectory)
            ?: error("Could not locate the default values/ resource folder from ${File(".").absolutePath}")

        val strings = mutableMapOf<String, String>()
        val plurals = mutableMapOf<String, Map<String, String>>()

        init {
            val builder = DocumentBuilderFactory.newInstance().newDocumentBuilder()
            resourceDir.listFiles { file -> file.extension == "xml" }.orEmpty().sorted().forEach { file ->
                val root = builder.parse(file).documentElement
                root.elements("string").forEach { strings[it.getAttribute("name")] = unescape(it.textContent) }
                root.elements("plurals").forEach { element ->
                    plurals[element.getAttribute("name")] = element.elements("item")
                        .associate { it.getAttribute("quantity") to unescape(it.textContent) }
                }
            }
        }

        fun Element.elements(tag: String): List<Element> {
            val nodes = getElementsByTagName(tag)
            return (0 until nodes.length).map { nodes.item(it) as Element }
        }

        /** Mirrors the escaping aapt applies to resource text. */
        fun unescape(value: String): String = value
            .trim()
            .replace("\\'", "'")
            .replace("\\\"", "\"")
            .replace("\\@", "@")
            .replace("\\?", "?")
            .replace("\\n", "\n")
    }
}
