package com.example.reader

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.provider.OpenableColumns
import android.view.View
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.updatePadding
import com.example.reader.databinding.ActivityMainBinding
import java.io.File
import java.io.FileInputStream
import java.io.FileNotFoundException
import java.io.InputStream
import java.util.concurrent.Executors

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding

    private val worker = Executors.newSingleThreadExecutor()
    private val mainHandler = Handler(Looper.getMainLooper())
    private val prefs by lazy { getSharedPreferences(PREFS_NAME, MODE_PRIVATE) }

    /** Растёт при каждом новом открытии файла: ответы «старых» загрузок игнорируются. */
    private var loadToken = 0
    private var contentVisible = false
    private var fontSizeSp = DEFAULT_FONT_SIZE

    private val pickDocument =
        registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
            if (uri == null) return@registerForActivityResult
            // Разрешение переживёт перезапуск приложения — файл откроется при следующем старте.
            runCatching {
                contentResolver.takePersistableUriPermission(
                    uri,
                    Intent.FLAG_GRANT_READ_URI_PERMISSION
                )
            }
            openDocument(uri, restoreScroll = null)
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)
        applyWindowInsets()

        fontSizeSp = prefs.getInt(KEY_FONT_SIZE, DEFAULT_FONT_SIZE)
        applyFontSize()

        binding.openButton.setOnClickListener { pickDocument.launch(MIME_TYPES) }
        binding.fontIncrease.setOnClickListener { changeFontSize(+1) }
        binding.fontDecrease.setOnClickListener { changeFontSize(-1) }

        if (!handleViewIntent(intent)) {
            if (!restoreLastSession()) showEmptyState()
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleViewIntent(intent)
    }

    /** Открытие файла из другого приложения («Открыть с помощью → Читалка»). */
    private fun handleViewIntent(intent: Intent?): Boolean {
        if (intent?.action != Intent.ACTION_VIEW) return false
        val uri = intent.data ?: return false
        openDocument(uri, restoreScroll = null)
        return true
    }

    /**
     * Начиная с Android 15 приложение рисуется «под» системными панелями, поэтому
     * отступаем от них вручную — иначе шапка заезжает под часы, а кнопки под полоску навигации.
     */
    private fun applyWindowInsets() {
        ViewCompat.setOnApplyWindowInsetsListener(binding.root) { view, insets ->
            val bars = insets.getInsets(
                WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout()
            )
            view.updatePadding(
                left = bars.left,
                top = bars.top,
                right = bars.right,
                bottom = bars.bottom
            )
            WindowInsetsCompat.CONSUMED
        }
    }

    /** При запуске возвращаем последнюю открытую книгу на том же месте. */
    private fun restoreLastSession(): Boolean {
        val saved = prefs.getString(KEY_LAST_URI, null) ?: return false
        val uri = Uri.parse(saved)
        if (!canRead(uri)) {
            prefs.edit().remove(KEY_LAST_URI).apply()
            return false
        }
        openDocument(uri, restoreScroll = prefs.getInt(KEY_SCROLL, 0))
        return true
    }

    private fun canRead(uri: Uri): Boolean = try {
        if (uri.scheme == "file") {
            File(uri.path.orEmpty()).canRead()
        } else {
            contentResolver.persistedUriPermissions.any { it.uri == uri && it.isReadPermission }
        }
    } catch (e: Exception) {
        false
    }

    // ------------------------------------------------------------------ загрузка

    private fun openDocument(uri: Uri, restoreScroll: Int?) {
        val token = ++loadToken
        showLoading()
        val displayName = queryDisplayName(uri)

        worker.execute {
            val startedAt = SystemClock.elapsedRealtime()
            val outcome = runCatching { TextLoader.read(openStream(uri), displayName) }

            // Индикатор не должен мигать на маленьких файлах — держим его минимум 300 мс.
            val elapsed = SystemClock.elapsedRealtime() - startedAt
            if (elapsed < MIN_LOADING_MS) SystemClock.sleep(MIN_LOADING_MS - elapsed)

            mainHandler.post {
                if (token != loadToken || isFinishing || isDestroyed) return@post
                outcome
                    .onSuccess { book -> showBook(uri, displayName, book, restoreScroll) }
                    .onFailure { error -> showError(error) }
            }
        }
    }

    private fun openStream(uri: Uri): InputStream {
        val stream = when (uri.scheme) {
            "file" -> FileInputStream(
                File(uri.path ?: throw FileNotFoundException("Пустой путь к файлу"))
            )
            else -> contentResolver.openInputStream(uri)
        }
        return stream ?: throw FileNotFoundException("Не удалось получить доступ к файлу")
    }

    private fun queryDisplayName(uri: Uri): String {
        if (uri.scheme == "file") return uri.lastPathSegment ?: "Файл"
        var name: String? = null
        runCatching {
            contentResolver.query(
                uri,
                arrayOf(OpenableColumns.DISPLAY_NAME),
                null, null, null
            )?.use { cursor ->
                val index = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                if (index >= 0 && cursor.moveToFirst()) name = cursor.getString(index)
            }
        }
        return name ?: uri.lastPathSegment ?: "Файл"
    }

    // ------------------------------------------------------------------ состояния экрана

    private fun showLoading() {
        binding.loadingOverlay.visibility = View.VISIBLE
        binding.messageState.visibility = View.GONE
        binding.openButton.isEnabled = false
    }

    private fun showBook(uri: Uri, name: String, book: Book, restoreScroll: Int?) {
        contentVisible = true

        binding.textView.text = book.text
        binding.fileName.text = buildSubtitle(name, book)

        binding.loadingOverlay.visibility = View.GONE
        binding.messageState.visibility = View.GONE
        binding.scrollView.visibility = View.VISIBLE
        binding.openButton.isEnabled = true

        binding.scrollView.post { binding.scrollView.scrollTo(0, restoreScroll ?: 0) }

        prefs.edit().putString(KEY_LAST_URI, uri.toString()).apply()
    }

    private fun showEmptyState() {
        contentVisible = false
        binding.scrollView.visibility = View.GONE
        binding.textView.text = null
        binding.loadingOverlay.visibility = View.GONE
        binding.messageState.visibility = View.VISIBLE
        binding.stateIcon.setImageResource(R.drawable.ic_book)
        binding.stateIcon.alpha = 0.3f
        binding.stateTitle.setText(R.string.empty_title)
        binding.stateSubtitle.setText(R.string.empty_subtitle)
        binding.fileName.setText(R.string.no_file)
    }

    private fun showError(error: Throwable) {
        contentVisible = false

        val (title, message) = describe(error)

        binding.scrollView.visibility = View.GONE
        binding.textView.text = null
        binding.loadingOverlay.visibility = View.GONE
        binding.messageState.visibility = View.VISIBLE
        binding.openButton.isEnabled = true

        binding.stateIcon.setImageResource(R.drawable.ic_error)
        binding.stateIcon.alpha = 0.45f
        binding.stateTitle.text = title
        binding.stateSubtitle.text = message
        binding.fileName.text = getString(R.string.no_file)

        prefs.edit().remove(KEY_LAST_URI).apply()
    }

    private fun describe(error: Throwable): Pair<String, String> = when (error) {
        is ReadException -> error.title to (error.message ?: "Неизвестная ошибка чтения.")
        is FileNotFoundException -> "Файл не найден" to
            "Похоже, файл удалили или переместили. Выберите его заново кнопкой «Открыть файл»."
        is SecurityException -> "Нет доступа к файлу" to
            "Приложению не разрешено читать этот файл. Откройте его через кнопку «Открыть файл»."
        is OutOfMemoryError -> "Файл слишком большой" to
            "Не хватило памяти, чтобы открыть документ целиком."
        else -> getString(R.string.error_title) to
            (error.message ?: error.javaClass.simpleName)
    }

    private fun buildSubtitle(name: String, book: Book): String {
        val details = listOfNotNull(book.title, book.author)
            .filter { it.isNotBlank() }
            .joinToString(" • ")
        return if (details.isBlank()) name else "$name • $details"
    }

    // ------------------------------------------------------------------ шрифт

    private fun changeFontSize(delta: Int) {
        val next = (fontSizeSp + delta).coerceIn(MIN_FONT_SIZE, MAX_FONT_SIZE)
        if (next == fontSizeSp) return
        fontSizeSp = next
        prefs.edit().putInt(KEY_FONT_SIZE, next).apply()
        applyFontSize()
    }

    private fun applyFontSize() {
        binding.textView.textSize = fontSizeSp.toFloat()
    }

    // ------------------------------------------------------------------ жизненный цикл

    override fun onPause() {
        super.onPause()
        saveScrollPosition()
    }

    override fun onStop() {
        super.onStop()
        saveScrollPosition()
    }

    /** Запоминаем, до какого места дочитали, чтобы вернуться туда при следующем запуске. */
    private fun saveScrollPosition() {
        if (contentVisible) {
            prefs.edit().putInt(KEY_SCROLL, binding.scrollView.scrollY).apply()
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        worker.shutdownNow()
        mainHandler.removeCallbacksAndMessages(null)
    }

    private companion object {
        const val PREFS_NAME = "reader_prefs"
        const val KEY_LAST_URI = "last_uri"
        const val KEY_SCROLL = "last_scroll"
        const val KEY_FONT_SIZE = "font_size"

        const val DEFAULT_FONT_SIZE = 17
        const val MIN_FONT_SIZE = 12
        const val MAX_FONT_SIZE = 30
        const val MIN_LOADING_MS = 300L

        /**
         * Какие файлы показывать в системном выборе. MIME-тип .fb2 разные файловые
         * менеджеры отдают по-разному, поэтому перечисляем все частые варианты.
         */
        val MIME_TYPES = arrayOf(
            "text/plain",
            "text/*",
            "application/fb2",
            "application/x-fictionbook+xml",
            "application/xml",
            "text/xml",
            "application/octet-stream"
        )
    }
}
