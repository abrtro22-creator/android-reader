package com.example.reader

import android.graphics.Typeface
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.style.RelativeSizeSpan
import android.text.style.StyleSpan
import android.util.Xml
import org.xmlpull.v1.XmlPullParser
import java.io.ByteArrayInputStream

/**
 * Разбор книг в формате FB2 (FictionBook 2).
 *
 * FB2 — это обычный XML, поэтому файл читается потоковым парсером: в память не попадают
 * base64-картинки из тега <binary>, а текст собирается по мере чтения документа.
 * Названия глав выделяются жирным шрифтом через spans.
 */
internal object Fb2Parser {

    private val WHITESPACE = Regex("\\s+")

    fun parse(bytes: ByteArray): Book {
        val text = SpannableStringBuilder()
        val titleRanges = ArrayList<IntRange>()

        var bookTitle: String? = null
        var nickName: String? = null
        var firstName = ""
        var lastName = ""
        var inBody = false
        var inDescription = false
        var metaField: String? = null
        var titleStart = -1
        var inTitle = false

        val parser = Xml.newPullParser()
        parser.setFeature(XmlPullParser.FEATURE_PROCESS_NAMESPACES, false)
        parser.setInput(ByteArrayInputStream(bytes), null)

        var event = parser.eventType
        while (event != XmlPullParser.END_DOCUMENT) {
            when (event) {
                XmlPullParser.START_TAG -> {
                    val tag = localName(parser)
                    when {
                        // Картинки и обложка не нужны — пропускаем целиком, не разбирая base64.
                        tag == "binary" -> skipElement(parser)
                        tag == "description" -> inDescription = true
                        tag == "body" -> {
                            inBody = true
                            newlines(text, 2)
                        }
                        inBody -> when (tag) {
                            "title", "subtitle" -> {
                                newlines(text, 2)
                                titleStart = text.length
                                inTitle = true
                            }
                            "epigraph", "annotation", "poem", "stanza", "cite" -> newlines(text, 2)
                            "empty-line" -> newlines(text, 2)
                            else -> Unit
                        }
                        inDescription -> if (tag == "book-title" || tag == "first-name" ||
                            tag == "last-name" || tag == "nickname"
                        ) {
                            metaField = tag
                        }
                    }
                }

                XmlPullParser.TEXT -> {
                    val raw = parser.text
                    if (!raw.isNullOrEmpty()) {
                        if (inBody) {
                            appendText(text, raw)
                        } else if (inDescription && metaField != null) {
                            val value = raw.replace(WHITESPACE, " ").trim()
                            if (value.isNotEmpty()) {
                                when (metaField) {
                                    "book-title" -> bookTitle = value
                                    "first-name" -> firstName = value
                                    "last-name" -> lastName = value
                                    "nickname" -> nickName = value
                                }
                            }
                        }
                    }
                }

                XmlPullParser.END_TAG -> {
                    val tag = localName(parser)
                    when {
                        tag == "description" -> inDescription = false
                        tag == "body" -> inBody = false
                        inBody -> when (tag) {
                            "title", "subtitle" -> {
                                if (titleStart in 0 until text.length) {
                                    var end = text.length
                                    while (end > titleStart &&
                                        (text[end - 1] == '\n' || text[end - 1] == ' ')
                                    ) {
                                        end--
                                    }
                                    if (end > titleStart) titleRanges.add(titleStart until end)
                                }
                                titleStart = -1
                                inTitle = false
                                newlines(text, 2)
                            }
                            // Между абзацами оставляем пустую строку, а строки стихов — подряд.
                            "p" -> newlines(text, if (inTitle) 1 else 2)
                            "v", "text-author", "date" -> newlines(text, 1)
                            "section" -> newlines(text, 2)
                        }
                        inDescription -> if (tag == metaField) metaField = null
                    }
                }
            }
            event = parser.next()
        }

        // Убираем пустоту в конце документа
        while (text.isNotEmpty() && (text[text.length - 1] == '\n' || text[text.length - 1] == ' ')) {
            text.delete(text.length - 1, text.length)
        }

        for (range in titleRanges) {
            if (range.first < range.last && range.last <= text.length) {
                text.setSpan(
                    StyleSpan(Typeface.BOLD),
                    range.first,
                    range.last,
                    Spanned.SPAN_EXCLUSIVE_EXCLUSIVE
                )
                text.setSpan(
                    RelativeSizeSpan(1.12f),
                    range.first,
                    range.last,
                    Spanned.SPAN_EXCLUSIVE_EXCLUSIVE
                )
            }
        }

        val author = when {
            firstName.isNotBlank() || lastName.isNotBlank() ->
                listOf(firstName, lastName).filter { it.isNotBlank() }.joinToString(" ")
            else -> nickName
        }

        return Book(title = bookTitle, author = author, text = text)
    }

    private fun localName(parser: XmlPullParser): String =
        parser.name?.substringAfter(':')?.lowercase() ?: ""

    /** Пропускает элемент вместе со всем содержимым. */
    private fun skipElement(parser: XmlPullParser) {
        var depth = 1
        while (depth > 0) {
            when (parser.next()) {
                XmlPullParser.START_TAG -> depth++
                XmlPullParser.END_TAG -> depth--
                XmlPullParser.END_DOCUMENT -> return
            }
        }
    }

    /** Добавляет перевод строки, но не больше [count] подряд. */
    private fun newlines(sb: SpannableStringBuilder, count: Int) {
        while (sb.isNotEmpty() && sb[sb.length - 1] == ' ') {
            sb.delete(sb.length - 1, sb.length)
        }
        if (sb.isEmpty()) return
        var existing = 0
        var index = sb.length - 1
        while (index >= 0 && sb[index] == '\n') {
            existing++
            index--
        }
        var toAdd = count - existing
        while (toAdd-- > 0) sb.append('\n')
    }

    private fun appendText(sb: SpannableStringBuilder, raw: String) {
        val clean = raw.replace(WHITESPACE, " ")
        if (clean.isBlank()) return
        if (sb.isEmpty() || sb[sb.length - 1] == '\n') {
            sb.append(clean.trimStart())
        } else {
            sb.append(clean)
        }
    }
}
