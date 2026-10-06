package dev.applesideload.core

import org.w3c.dom.Element
import org.w3c.dom.Node
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.util.Date
import javax.xml.parsers.DocumentBuilderFactory

/**
 * A property list value.
 *
 * Everything Apple's device services exchange is a property list: usbmux
 * speaks XML plists, lockdown speaks XML plists, installation_proxy answers
 * in them, an app's Info.plist is one in binary form. There is no standard
 * plist support on Android, so this is a complete reader and writer for both
 * encodings rather than a wrapper around something.
 */
sealed class Plist {
    data class Str(val value: String) : Plist()
    data class Num(val value: Long) : Plist()
    data class Real(val value: Double) : Plist()
    data class Bool(val value: Boolean) : Plist()
    data class Data(val value: ByteArray) : Plist() {
        override fun equals(other: Any?): Boolean =
            other is Data && value.contentEquals(other.value)

        override fun hashCode(): Int = value.contentHashCode()
    }

    data class Stamp(val epochSeconds: Double) : Plist() {
        val date: Date get() = Date(((epochSeconds + APPLE_EPOCH) * 1000).toLong())
    }

    data class Arr(val value: List<Plist>) : Plist()
    data class Dict(val value: Map<String, Plist>) : Plist()

    // Convenience accessors. Reading a plist is mostly asking for one key and
    // being honest about it not being there.
    operator fun get(key: String): Plist? = (this as? Dict)?.value?.get(key)
    val asString: String? get() = (this as? Str)?.value
    val asLong: Long? get() = (this as? Num)?.value ?: (this as? Real)?.value?.toLong()
    val asInt: Int? get() = asLong?.toInt()
    val asBool: Boolean? get() = (this as? Bool)?.value
    val asData: ByteArray? get() = (this as? Data)?.value
    val asList: List<Plist>? get() = (this as? Arr)?.value
    val asDict: Map<String, Plist>? get() = (this as? Dict)?.value

    companion object {
        /** Seconds between 1 January 1970 and 1 January 2001. */
        const val APPLE_EPOCH = 978_307_200.0

        fun dict(vararg pairs: Pair<String, Plist>): Dict = Dict(linkedMapOf(*pairs))
        fun of(value: String): Str = Str(value)
        fun of(value: Long): Num = Num(value)
        fun of(value: Int): Num = Num(value.toLong())
        fun of(value: Boolean): Bool = Bool(value)
        fun of(value: ByteArray): Data = Data(value)
    }
}

/** Reads either encoding, deciding by the magic rather than by the caller. */
object PlistReader {
    private val BPLIST = "bplist00".toByteArray(Charsets.US_ASCII)

    fun parse(bytes: ByteArray): Plist {
        if (bytes.size >= 8 && bytes.copyOfRange(0, 8).contentEquals(BPLIST)) {
            return BinaryPlist.parse(bytes)
        }
        return XmlPlist.parse(bytes)
    }
}

/**
 * XML property lists.
 *
 * usbmux and lockdown both use this form, so it has to be exact: a missing
 * element or a stray newline inside a <data> block is a protocol error, not a
 * cosmetic problem.
 */
object XmlPlist {
    fun parse(bytes: ByteArray): Plist {
        val factory = DocumentBuilderFactory.newInstance()
        factory.isNamespaceAware = false
        // The input is a device response, not a document we control, so no
        // external entity is ever resolved.
        runCatching {
            factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", false)
            factory.setFeature("http://xml.org/sax/features/external-general-entities", false)
            factory.setFeature("http://xml.org/sax/features/external-parameter-entities", false)
        }
        factory.isExpandEntityReferences = false
        val document = factory.newDocumentBuilder().parse(ByteArrayInputStream(bytes))
        val root = document.documentElement
            ?: throw PlistException("the document has no root element")
        val first = firstElement(root) ?: throw PlistException("the plist is empty")
        return node(first)
    }

    fun write(value: Plist): ByteArray = buildString {
        append("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n")
        append("<!DOCTYPE plist PUBLIC \"-//Apple//DTD PLIST 1.0//EN\" ")
        append("\"http://www.apple.com/DTDs/PropertyList-1.0.dtd\">\n")
        append("<plist version=\"1.0\">\n")
        appendValue(value, 1)
        append("</plist>\n")
    }.toByteArray(Charsets.UTF_8)

    private fun StringBuilder.appendValue(value: Plist, depth: Int) {
        val pad = "\t".repeat(depth)
        when (value) {
            is Plist.Str -> append("$pad<string>${escape(value.value)}</string>\n")
            is Plist.Num -> append("$pad<integer>${value.value}</integer>\n")
            is Plist.Real -> append("$pad<real>${value.value}</real>\n")
            is Plist.Bool -> append(if (value.value) "$pad<true/>\n" else "$pad<false/>\n")
            is Plist.Data -> {
                val encoded = Base64.encode(value.value)
                append("$pad<data>\n")
                encoded.chunked(68).forEach { append("$pad$it\n") }
                append("$pad</data>\n")
            }
            is Plist.Stamp -> append("$pad<date>${IsoDate.format(value.date)}</date>\n")
            is Plist.Arr -> {
                if (value.value.isEmpty()) {
                    append("$pad<array/>\n")
                } else {
                    append("$pad<array>\n")
                    value.value.forEach { appendValue(it, depth + 1) }
                    append("$pad</array>\n")
                }
            }
            is Plist.Dict -> {
                if (value.value.isEmpty()) {
                    append("$pad<dict/>\n")
                } else {
                    append("$pad<dict>\n")
                    value.value.forEach { (key, item) ->
                        append("$pad\t<key>${escape(key)}</key>\n")
                        appendValue(item, depth + 1)
                    }
                    append("$pad</dict>\n")
                }
            }
        }
    }

    private fun node(element: Element): Plist = when (element.tagName) {
        "string" -> Plist.Str(element.textContent ?: "")
        "integer" -> Plist.Num(
            (element.textContent ?: "0").trim().toLongOrNull()
                ?: throw PlistException("an integer element did not hold an integer")
        )
        "real" -> Plist.Real((element.textContent ?: "0").trim().toDoubleOrNull() ?: 0.0)
        "true" -> Plist.Bool(true)
        "false" -> Plist.Bool(false)
        "data" -> Plist.Data(Base64.decode(element.textContent ?: ""))
        "date" -> Plist.Stamp(IsoDate.parseToAppleSeconds(element.textContent?.trim() ?: ""))
        "array" -> Plist.Arr(children(element).map { node(it) })
        "dict" -> {
            val out = LinkedHashMap<String, Plist>()
            val items = children(element)
            var index = 0
            while (index < items.size) {
                val keyNode = items[index]
                if (keyNode.tagName != "key") {
                    throw PlistException("a dict entry is missing its key")
                }
                val valueNode = items.getOrNull(index + 1)
                    ?: throw PlistException("the key ${keyNode.textContent} has no value")
                out[keyNode.textContent ?: ""] = node(valueNode)
                index += 2
            }
            Plist.Dict(out)
        }
        else -> throw PlistException("unknown plist element <${element.tagName}>")
    }

    private fun children(element: Element): List<Element> {
        val out = ArrayList<Element>()
        var child: Node? = element.firstChild
        while (child != null) {
            if (child.nodeType == Node.ELEMENT_NODE) out.add(child as Element)
            child = child.nextSibling
        }
        return out
    }

    private fun firstElement(element: Element): Element? = children(element).firstOrNull()

    private fun escape(text: String): String = text
        .replace("&", "&amp;")
        .replace("<", "&lt;")
        .replace(">", "&gt;")
}

class PlistException(message: String) : Exception(message)
