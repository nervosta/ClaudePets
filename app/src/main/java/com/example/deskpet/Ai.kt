package com.example.deskpet

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.speech.RecognizerIntent
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import com.google.mediapipe.tasks.genai.llminference.LlmInference
import com.google.mediapipe.tasks.genai.llminference.LlmInference.LlmInferenceOptions
import java.io.File
import java.util.concurrent.Executors

class AiActivity : Activity() {
    private val ui = Handler(Looper.getMainLooper())
    private val worker = Executors.newSingleThreadExecutor()
    @Volatile private var llm: LlmInference? = null
    @Volatile private var busy = false
    private lateinit var status: TextView
    private lateinit var log: TextView
    private lateinit var scroll: ScrollView
    private lateinit var input: EditText
    private val turns = ArrayList<Pair<String, String>>()
    private val modelFile by lazy { File(filesDir, "model.task") }
    private val names = arrayOf("Клод", "Рекс", "Пёс", "Мопс", "Летучка")
    private val petName: String
        get() = names[getSharedPreferences("pet", MODE_PRIVATE).getInt("kind", 0).coerceIn(0, 4)]

    private fun toast(s: String) = Toast.makeText(this, s, Toast.LENGTH_SHORT).show()
    private fun cell() = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val pad = (16 * resources.displayMetrics.density).toInt()
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad, pad * 2, pad, pad)
        }
        status = TextView(this).apply {
            text = "Модель не загружена. Положи файл .task в «Загрузки» и нажми «Загрузить»."
            setPadding(0, 0, 0, pad / 2)
        }
        fun btn(t: String, f: () -> Unit) = Button(this).apply {
            text = t; textSize = 12f; setOnClickListener { f() }
        }
        val bar = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        bar.addView(btn("Загрузить") { prepare() }, cell())
        bar.addView(btn("Заметки") { summarizeNotes() }, cell())
        bar.addView(btn("Найти файл") { findFile() }, cell())

        log = TextView(this).apply { textSize = 16f; setPadding(0, pad / 2, 0, pad / 2) }
        scroll = ScrollView(this).apply { addView(log) }

        input = EditText(this).apply { hint = "Напиши или скажи что-нибудь"; textSize = 16f }
        val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        row.addView(input, cell())
        row.addView(Button(this).apply { text = "🎤"; setOnClickListener { startVoice() } })
        row.addView(Button(this).apply { text = "➤"; setOnClickListener { send() } })

        box.addView(status)
        box.addView(bar)
        box.addView(scroll, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f))
        box.addView(row)
        setContentView(box)

        if (modelFile.exists() && modelFile.length() > 0L) prepare()
    }

    override fun onDestroy() {
        worker.shutdownNow()
        try { llm?.close() } catch (e: Throwable) {}
        super.onDestroy()
    }

    // ---------- модель ----------
    private fun hasAccess(): Boolean =
        if (Build.VERSION.SDK_INT >= 30) Environment.isExternalStorageManager()
        else checkSelfPermission(Manifest.permission.READ_EXTERNAL_STORAGE) == PackageManager.PERMISSION_GRANTED

    private fun askAccess() {
        if (Build.VERSION.SDK_INT >= 30) {
            startActivity(
                Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION, Uri.parse("package:$packageName"))
            )
        } else {
            requestPermissions(arrayOf(Manifest.permission.READ_EXTERNAL_STORAGE), 3)
        }
    }

    private fun findModel(): File? {
        val dirs = listOf(
            Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS),
            Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOCUMENTS),
            Environment.getExternalStorageDirectory()
        )
        for (d in dirs) {
            val list = d.listFiles() ?: continue
            val hit = list.filter { it.isFile && it.name.endsWith(".task", true) }
                .maxByOrNull { it.lastModified() }
            if (hit != null) return hit
        }
        return null
    }

    private fun post(msg: String) { ui.post { status.text = msg } }

    private fun prepare() {
        if (busy) { toast("Подожди, я занят"); return }
        if (llm != null) { toast("Модель уже загружена"); return }
        if (!(modelFile.exists() && modelFile.length() > 0L) && !hasAccess()) {
            status.text = "Разреши доступ ко всем файлам, вернись и нажми «Загрузить» ещё раз"
            askAccess()
            return
        }
        busy = true
        worker.execute {
            try {
                if (!modelFile.exists() || modelFile.length() == 0L) {
                    val src = findModel()
                    if (src == null) {
                        post("Файл .task не найден в «Загрузки». Скачай модель и нажми «Загрузить».")
                        return@execute
                    }
                    post("Копирую модель (${src.length() / 1048576} МБ)…")
                    try {
                        src.inputStream().use { i -> modelFile.outputStream().use { o -> i.copyTo(o, 1 shl 20) } }
                    } catch (e: Throwable) {
                        modelFile.delete(); throw e
                    }
                }
                post("Загружаю модель…")
                val opts = LlmInferenceOptions.builder()
                    .setModelPath(modelFile.absolutePath)
                    .setMaxTokens(1024)
                    .build()
                llm = LlmInference.createFromOptions(this, opts)
                post("Готов. Можно писать.")
            } catch (e: Throwable) {
                modelFile.delete()
                post("Ошибка загрузки: ${e.message}")
            } finally {
                busy = false
            }
        }
    }

    // ---------- генерация ----------
    private fun buildPrompt(user: String, chat: Boolean): String {
        val sb = StringBuilder()
        if (!chat) {
            sb.append("<start_of_turn>user\n").append(user).append("<end_of_turn>\n<start_of_turn>model\n")
            return sb.toString()
        }
        val persona = "Ты — маленький пиксельный питомец по имени $petName на экране телефона. " +
            "Отвечай по-русски, коротко (1-3 предложения), дружелюбно."
        var recent = turns.takeLast(3)
        while (recent.isNotEmpty() && recent.sumOf { it.first.length + it.second.length } > 1800) {
            recent = recent.drop(1)
        }
        var first = true
        for ((u, m) in recent) {
            sb.append("<start_of_turn>user\n")
            if (first) sb.append(persona).append("\n\n")
            sb.append(u).append("<end_of_turn>\n<start_of_turn>model\n").append(m).append("<end_of_turn>\n")
            first = false
        }
        sb.append("<start_of_turn>user\n")
        if (first) sb.append(persona).append("\n\n")
        sb.append(user).append("<end_of_turn>\n<start_of_turn>model\n")
        return sb.toString()
    }

    private fun generate(userText: String, chat: Boolean, done: (String) -> Unit) {
        val m = llm
        if (m == null) { toast("Сначала загрузи модель"); return }
        if (busy) { toast("Подожди, ещё думаю"); return }
        busy = true
        status.text = "Думаю…"
        val prompt = buildPrompt(userText, chat)
        worker.execute {
            val answer = try {
                m.generateResponse(prompt).trim()
            } catch (e: Throwable) {
                "Ошибка: ${e.message}"
            }
            busy = false
            ui.post { status.text = "Готов."; done(answer) }
        }
    }

    private fun addLog(s: String) {
        log.append(s + "\n\n")
        scroll.post { scroll.fullScroll(View.FOCUS_DOWN) }
    }

    private fun send() {
        val t = input.text.toString().trim()
        if (t.isEmpty()) return
        if (llm == null) { toast("Сначала загрузи модель"); return }
        input.setText("")
        addLog("Ты: $t")
        generate(t, true) { a -> turns.add(t to a); addLog("$petName: $a") }
    }

    private fun summarizeNotes() {
        if (llm == null) { toast("Сначала загрузи модель"); return }
        val notes = Notes.load(this)
        if (notes.isEmpty()) { toast("Заметок пока нет"); return }
        val text = notes.take(20).joinToString("\n") { "- $it" }
        addLog("Ты: перескажи мои заметки")
        generate("Кратко перескажи мои заметки по-русски и выдели важное:\n$text", false) { a ->
            addLog("$petName: $a")
        }
    }

    private fun findFile() {
        if (llm == null) { toast("Сначала загрузи модель"); return }
        val t = input.text.toString().trim()
        if (t.isEmpty()) { toast("Опиши, какой файл нужен"); return }
        generate(
            "Из запроса выдели одно ключевое слово для поиска файла по названию. " +
                "Ответь только этим словом, без пояснений.\nЗапрос: $t",
            false
        ) { a ->
            val word = a.split(Regex("[^\\p{L}\\p{N}]+")).firstOrNull { it.length >= 2 } ?: t
            addLog("Ищу файл по слову: $word")
            startActivity(Intent(this, SearchActivity::class.java).putExtra("query", word))
        }
    }

    // ---------- голос ----------
    private fun startVoice() {
        try {
            startActivityForResult(
                Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH)
                    .putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                    .putExtra(RecognizerIntent.EXTRA_LANGUAGE, "ru-RU"),
                11
            )
        } catch (e: Exception) {
            toast("Голосовой ввод недоступен")
        }
    }

    override fun onActivityResult(req: Int, res: Int, data: Intent?) {
        super.onActivityResult(req, res, data)
        if (req == 11 && res == RESULT_OK) {
            val text = data?.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS)?.firstOrNull()
            if (text != null) { input.setText(text); input.setSelection(text.length) }
        }
    }
}
