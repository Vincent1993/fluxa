package com.fluxa.app.data.directrss

import org.w3c.dom.Element
import org.xmlpull.v1.XmlPullParser
import org.xmlpull.v1.XmlPullParserFactory
import java.io.ByteArrayInputStream
import javax.xml.parsers.DocumentBuilderFactory

/** Tokenize without DTD processing; build a DOM ourselves, never parse untrusted XML with DOM.
 * Android's pull parser has no network/entity resolver. DOCDECL is rejected before another token.
 */
internal fun secureDocument(bytes: ByteArray): Element {
    val parser = XmlPullParserFactory.newInstance().apply { isNamespaceAware = true }.newPullParser()
    parser.setFeature(XmlPullParser.FEATURE_PROCESS_DOCDECL, false)
    parser.setInput(ByteArrayInputStream(bytes), null)
    val document = DocumentBuilderFactory.newInstance().newDocumentBuilder().newDocument()
    val stack = java.util.ArrayDeque<Element>()
    var roots = 0
    var tokens = 0
    while (true) {
        val type = parser.nextToken()
        require(++tokens <= 200_000) { "XML 结构超过上限" }
        when (type) {
            XmlPullParser.END_DOCUMENT -> break
            XmlPullParser.DOCDECL -> error("不允许 XML DTD")
            XmlPullParser.START_TAG -> {
                require(stack.size < 64) { "XML 嵌套超过上限" }
                val qualified = parser.prefix?.takeIf { it.isNotEmpty() }?.let { "$it:${parser.name}" } ?: parser.name
                val element = document.createElementNS(parser.namespace.takeIf { it.isNotEmpty() }, qualified)
                for (i in 0 until parser.attributeCount) {
                    val prefix = parser.getAttributePrefix(i)?.takeIf { it.isNotEmpty() }
                    element.setAttributeNS(parser.getAttributeNamespace(i).takeIf { it.isNotEmpty() },
                        if (prefix == null) parser.getAttributeName(i) else "$prefix:${parser.getAttributeName(i)}", parser.getAttributeValue(i))
                }
                if (stack.isEmpty()) { require(++roots == 1) { "多个 XML 根节点" }; document.appendChild(element) }
                else stack.last.appendChild(element)
                stack.addLast(element)
            }
            XmlPullParser.END_TAG -> stack.removeLast()
            XmlPullParser.TEXT, XmlPullParser.CDSECT, XmlPullParser.ENTITY_REF, XmlPullParser.IGNORABLE_WHITESPACE -> {
                val text = parser.text
                require(text != null) { "未知 XML 实体" }
                if (stack.isNotEmpty()) stack.last.appendChild(document.createTextNode(text))
                else require(text.isBlank()) { "XML 根节点外存在文本" }
            }
        }
    }
    require(roots == 1 && stack.isEmpty()) { "不完整 XML" }
    return document.documentElement
}
internal fun elementBase(element: Element, fallback: String, unbased: String = fallback): String {
    val ancestors = generateSequence(element) { it.parentNode as? Element }.toList().asReversed()
    if (ancestors.all { it.getAttributeNS("http://www.w3.org/XML/1998/namespace", "base").isBlank() }) return unbased
    return ancestors.fold(fallback) { base, node ->
        node.getAttributeNS("http://www.w3.org/XML/1998/namespace", "base").takeIf { it.isNotBlank() }?.let { safeUrl(it, base) } ?: base
    }
}
