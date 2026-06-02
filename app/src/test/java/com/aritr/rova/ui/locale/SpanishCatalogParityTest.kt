package com.aritr.rova.ui.locale

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.w3c.dom.Element
import java.io.File
import javax.xml.parsers.DocumentBuilderFactory

/**
 * Pure-JVM structural parity guard for the staged Spanish catalog (ADR-0023
 * §Staged Locale Before Reveal). Verifies STRUCTURE only — every English key is
 * translated, no extra keys, format-arg multiset preserved, plural categories
 * present. Translation *quality/polish* is the deferred native-review gate, not
 * this test. Defense-in-depth behind lint MissingTranslation/ExtraTranslation.
 */
class SpanishCatalogParityTest {

    private fun resFile(rel: String): File {
        val a = File("src/main/res/$rel")
        if (a.exists()) return a
        val b = File("app/src/main/res/$rel")
        check(b.exists()) { "Resource not found: $rel (cwd=${File(".").absolutePath})" }
        return b
    }

    private fun parse(rel: String): Element {
        val f = resFile(rel)
        val doc = DocumentBuilderFactory.newInstance()
            .apply { isNamespaceAware = false }
            .newDocumentBuilder()
            .parse(f)
        return doc.documentElement
    }

    /** All `%n$x`, `%x` format specifiers (incl. precision/width forms like `%1$.1f`) in a string, as an order-independent multiset. */
    private fun formatArgs(text: String): Map<String, Int> {
        // %% is a literal percent — strip before scanning.
        val cleaned = text.replace("%%", "")
        // %[argIndex$][flags/width/precision]conversion — the middle class captures
        // forms like %1$.1f (precision) and %1$,d (grouping); excludes % so it can't
        // run past a literal-percent boundary or into the next specifier.
        val rx = Regex("%(\\d+\\$)?[^a-zA-Z%]*[a-zA-Z]")
        return rx.findAll(cleaned).map { it.value }.groupingBy { it }.eachCount()
    }

    private fun stringEntries(rootRel: String): Map<String, String> {
        val root = parse(rootRel)
        val nodes = root.getElementsByTagName("string")
        val out = LinkedHashMap<String, String>()
        for (i in 0 until nodes.length) {
            val el = nodes.item(i) as Element
            if (el.getAttribute("translatable") == "false") continue
            out[el.getAttribute("name")] = el.textContent
        }
        return out
    }

    @Test
    fun stringNameSetsMatch() {
        val en = stringEntries("values/strings.xml").keys
        val es = stringEntries("values-es/strings.xml").keys
        val missing = en - es
        val extra = es - en
        assertTrue("Missing Spanish translations: $missing", missing.isEmpty())
        assertTrue("Extra Spanish keys not in English: $extra", extra.isEmpty())
    }

    @Test
    fun stringFormatArgsMatchPerKey() {
        val en = stringEntries("values/strings.xml")
        val es = stringEntries("values-es/strings.xml")
        val mismatches = en.keys.filter { k ->
            es.containsKey(k) && formatArgs(en.getValue(k)) != formatArgs(es.getValue(k))
        }.associateWith { k -> "en=${formatArgs(en.getValue(k))} es=${formatArgs(es.getValue(k))}" }
        assertEquals("Format-arg multiset drift: $mismatches", emptyMap<String, String>(), mismatches)
    }

    @Test
    fun pluralCatalogsMatch() {
        val en = parse("values/plurals.xml").getElementsByTagName("plurals")
        val es = parse("values-es/plurals.xml").getElementsByTagName("plurals")
        fun names(n: org.w3c.dom.NodeList) = (0 until n.length)
            .map { (n.item(it) as Element).getAttribute("name") }.toSet()
        assertEquals("Plural name sets differ", names(en), names(es))
    }

    @Test
    fun spanishPluralsHaveOneAndOther() {
        val es = parse("values-es/plurals.xml").getElementsByTagName("plurals")
        val bad = mutableListOf<String>()
        for (i in 0 until es.length) {
            val p = es.item(i) as Element
            val items = p.getElementsByTagName("item")
            val qty = (0 until items.length)
                .map { (items.item(it) as Element).getAttribute("quantity") }.toSet()
            if (!qty.containsAll(setOf("one", "other"))) bad += p.getAttribute("name")
        }
        assertTrue("Spanish plurals missing one/other: $bad", bad.isEmpty())
    }
}
