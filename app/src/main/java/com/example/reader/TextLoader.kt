package com.example.reader

import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.nio.ByteBuffer
import java.nio.charset.CharacterCodingException
import java.nio.charset.Charset
import java.nio.charset.CodingErrorAction

/** Ошибка, которую можно показать пользователю человеческим языком. */
class ReadException(
    message: String,
    val title: String = "Не удалось открыть файл"
) : Exception(message)

/** Готовый к показу документ: текст + необязательные метаданные. */
data class Book(
    val title: String?,
    val author: String?,
    val text: CharSequence
)

/**
 * Читает файл с диска и превращает его в [Book].
 * Поддерживаются обычный текст (.txt) и книги FictionBook (.fb2).
 */
object TextLoader {

    /** Больше 25 МБ в TextView всё равно не имеет смысла. */
    private const val MAX_BYTES = 25 * 1024 * 1024

    private val WINDOWS_1251: Charset = Charset.forName("windows-1251")

    fun read(stream: InputStream, fileName: String): Book {
        val bytes = readAllBytes(stream)
        if (bytes.isEmpty()) {
            throw ReadException("В файле нет ни одного байта данных.", "Файл пустой")
        }
        return if (isFb2(bytes, fileName)) readFb2(bytes) else readPlainText(bytes)
    }

    private fun readAllBytes(input: InputStream): ByteArray {
        val output = ByteArrayOutputStream()
        val buffer = ByteArray(16 * 1024)
        var total = 0
        input.use { stream ->
            while (true) {
                val read = stream.read(buffer)
                if (read == -1) break
                total += read
                if (total > MAX_BYTES) {
                    throw ReadException(
                        "Файл больше 25 МБ — такой документ не поместится на экране.",
                        "Файл слишком большой"
                    )
                }
                output.write(buffer, 0, read)
            }
        }
        return output.toByteArray()
    }

    private fun isFb2(bytes: ByteArray, fileName: String): Boolean {
        if (fileName.endsWith(".fb2", ignoreCase = true)) return true
        // Иногда расширение теряется при передаче файла — тогда смотрим на содержимое.
        val head = String(bytes, 0, minOf(bytes.size, 4096), Charsets.ISO_8859_1)
        return head.contains("FictionBook", ignoreCase = true)
    }

    private fun readFb2(bytes: ByteArray): Book {
        val book = try {
            Fb2Parser.parse(bytes)
        } catch (e: ReadException) {
            throw e
        } catch (e: Exception) {
            throw ReadException(
                "Не удалось разобрать книгу: файл повреждён или это не FB2. " +
                    "Попробуйте скачать файл заново.",
                "Ошибка чтения FB2"
            )
        }
        if (book.text.isBlank()) {
            throw ReadException("В книге не нашлось текста для показа.", "Файл пустой")
        }
        return book
    }

    private fun readPlainText(bytes: ByteArray): Book {
        val charset = detectCharset(bytes)
        val text = String(bytes, charset)
            .replace("\r\n", "\n")
            .replace('\r', '\n')
            .trim()

        if (text.isBlank()) {
            throw ReadException("В файле нет текста — только пробелы и пустые строки.", "Файл пустой")
        }
        return Book(title = null, author = null, text = text)
    }

    /**
     * Определяет кодировку: BOM → UTF-8, иначе пробуем строгий UTF-8,
     * а если байты не складываются в UTF-8 — считаем, что это windows-1251
     * (самая частая кодировка старых русских .txt).
     */
    private fun detectCharset(bytes: ByteArray): Charset {
        if (bytes.size >= 3 &&
            bytes[0] == 0xEF.toByte() && bytes[1] == 0xBB.toByte() && bytes[2] == 0xBF.toByte()
        ) {
            return Charsets.UTF_8
        }
        if (bytes.size >= 2 && bytes[0] == 0xFF.toByte() && bytes[1] == 0xFE.toByte()) {
            return Charsets.UTF_16LE
        }
        if (bytes.size >= 2 && bytes[0] == 0xFE.toByte() && bytes[1] == 0xFF.toByte()) {
            return Charsets.UTF_16BE
        }
        return try {
            Charsets.UTF_8.newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT)
                .decode(ByteBuffer.wrap(bytes))
            Charsets.UTF_8
        } catch (e: CharacterCodingException) {
            WINDOWS_1251
        }
    }
}
