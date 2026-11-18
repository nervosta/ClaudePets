package com.example.deskpet

import android.Manifest
import android.app.Activity
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
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
import android.text.Editable
import android.text.TextWatcher
import android.webkit.MimeTypeMap
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ListView
import android.widget.TextView
import android.widget.Toast
import androidx.core.content.FileProvider
import org.json.JSONArray
import java.io.File

object Notes {
    private fun prefs(c: Context) = c.getSharedPreferences("notes", Context.MODE_PRIVATE)
    fun load(c: Context): MutableList<String> {
        val arr = JSONArray(prefs(c).getString("list", "[]"))
        return MutableList(arr.length()) { arr.getString(it) }
    }
    fun save(c: Context, list: List<String>) {
        prefs(c).edit().putString("list", JSONArray(list).toString()).apply()
    }
    fun add(c: Context, text: String) {
        val l = load(c); l.add(0, text); save(c, l)
    }
}

class NotesActivity : Activity() {
    private lateinit var notes: MutableList<String>
    private lateinit var adapter: ArrayAdapter<String>

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val pad = (16 * resources.displayMetrics.density).toInt()
        notes = Notes.load(this)
        adapter = ArrayAdapter(this, android.R.layout.simple_list_item_1, notes)
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad, pad * 2, pad, pad)
        }
        val input = EditText(this).apply { hint = "Новая заметка"; textSize = 18f }
        val add = Button(this).apply { text = "Добавить" }
        val hint = TextView(this).apply {
            text = "Нажми на заметку, чтобы скопировать. Удерживай, чтобы удалить."
            setPadding(0, pad / 2, 0, pad / 2)
        }
        val lv = ListView(this).apply { this.adapter = this@NotesActivity.adapter }
        add.setOnClickListener {
            val t = input.text.toString().trim()
            if (t.isNotEmpty()) {
                notes.add(0, t); Notes.save(this, notes)
                adapter.notifyDataSetChanged(); input.setText("")
            }
        }
        lv.setOnItemClickListener { _, _, pos, _ ->
            val cm = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            cm.setPrimaryClip(ClipData.newPlainText("note", notes[pos]))
            Toast.makeText(this, "Скопировано", Toast.LENGTH_SHORT).show()
        }
        lv.setOnItemLongClickListener { _, _, pos, _ ->
            notes.removeAt(pos); Notes.save(this, notes)
            adapter.notifyDataSetChanged(); true
        }
        box.addView(input); box.addView(add); box.addView(hint)
        box.addView(lv, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f))
        setContentView(box)
    }

    override fun onResume() {
        super.onResume()
        notes.clear(); notes.addAll(Notes.load(this)); adapter.notifyDataSetChanged()
    }
}

class SearchActivity : Activity() {
    private val ui = Handler(Looper.getMainLooper())
    private val files = ArrayList<File>()
    private val labels = ArrayList<String>()
    private lateinit var adapter: ArrayAdapter<String>
    private lateinit var input: EditText
    private lateinit var info: TextView
    private lateinit var lv: ListView
    private var searchId = 0
    private var asked = false
    private var voiceLaunched = false
    private val root: File = Environment.getExternalStorageDirectory()

    private fun toast(s: String) = Toast.makeText(this, s, Toast.LENGTH_SHORT).show()
    private fun cell() = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val pad = (16 * resources.displayMetrics.density).toInt()
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad, pad * 2, pad, pad)
        }
        val top = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        input = EditText(this).apply { hint = "Название файла"; textSize = 18f; isSingleLine = true }
        top.addView(input, cell())
        top.addView(Button(this).apply { text = "🎤"; setOnClickListener { startVoice() } })
        top.addView(Button(this).apply {
            text = "📝"
            setOnClickListener { startActivity(Intent(this@SearchActivity, NotesActivity::class.java)) }
        })
        info = TextView(this).apply { setPadding(0, pad / 2, 0, pad / 2) }
        adapter = ArrayAdapter(this, android.R.layout.simple_list_item_multiple_choice, labels)
        lv = ListView(this).apply {
            choiceMode = ListView.CHOICE_MODE_MULTIPLE
            this.adapter = this@SearchActivity.adapter
            setOnItemLongClickListener { _, _, pos, _ -> openFile(files[pos]); true }
        }
        val bar = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        fun btn(t: String, f: () -> Unit) = Button(this).apply {
            text = t; textSize = 12f; setOnClickListener { f() }
        }
        bar.addView(btn("Все") { selectAll() }, cell())
        bar.addView(btn("Копировать") { copyNames() }, cell())
        bar.addView(btn("Отправить") { sendFiles() }, cell())
        bar.addView(btn("Править") { editFile() }, cell())

        box.addView(top)
        box.addView(info)
        box.addView(lv, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f))
        box.addView(bar)
        setContentView(box)

        var pending: Runnable? = null
        input.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun afterTextChanged(s: Editable?) {
                pending?.let { ui.removeCallbacks(it) }
                val q = s.toString().trim()
                pending = Runnable { search(q) }
                ui.postDelayed(pending!!, 400)
            }
        })
        if (intent.getBooleanExtra("voice", false)) startVoice()
        intent.getStringExtra("query")?.let { input.setText(it) }
    }

    override fun onResume() {
        super.onResume()
        if (hasAccess()) {
            info.text = "Введи название. Удерживай файл, чтобы открыть."
        } else {
            info.text = "Разреши доступ ко всем файлам и вернись сюда"
            if (!asked && !voiceLaunched) { asked = true; askAccess() }
        }
    }

    private fun hasAccess(): Boolean =
        if (Build.VERSION.SDK_INT >= 30) Environment.isExternalStorageManager()
        else checkSelfPermission(Manifest.permission.READ_EXTERNAL_STORAGE) == PackageManager.PERMISSION_GRANTED

    private fun askAccess() {
        if (Build.VERSION.SDK_INT >= 30) {
            toast("Включи доступ ко всем файлам для DeskPets")
            startActivity(
                Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION, Uri.parse("package:$packageName"))
            )
        } else {
            requestPermissions(arrayOf(Manifest.permission.READ_EXTERNAL_STORAGE), 2)
        }
    }

    // ---------- голос ----------
    private fun startVoice() {
        voiceLaunched = true
        try {
            startActivityForResult(
                Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH)
                    .putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                    .putExtra(RecognizerIntent.EXTRA_LANGUAGE, "ru-RU")
                    .putExtra(RecognizerIntent.EXTRA_PROMPT, "Скажи: Клод, найди договор"),
                10
            )
        } catch (e: Exception) {
            voiceLaunched = false
            Toast.makeText(this, "Голосовой ввод недоступен", Toast.LENGTH_LONG).show()
        }
    }

    override fun onActivityResult(req: Int, res: Int, data: Intent?) {
        super.onActivityResult(req, res, data)
        if (req == 10) {
            voiceLaunched = false
            val text = if (res == RESULT_OK)
                data?.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS)?.firstOrNull() else null
            if (text != null) handleVoice(text)
        }
    }

    private fun handleVoice(raw: String) {
        val s = raw.lowercase().trim().replace(Regex("^клод[,!.]?\\s*"), "")
        when {
            Regex("^(выбери|выдели)\\s+все").containsMatchIn(s) -> selectAll()
            s.startsWith("скопируй") -> copyNames()
            s.startsWith("отправь") -> sendFiles()
            s.startsWith("редактируй") || s.startsWith("правь") || s.startsWith("измени") -> editFile()
            Regex("^(запиши|заметк|создай заметк|добавь заметк)").containsMatchIn(s) -> {
                val text = Regex("(?:запиши|заметк\\w*)[:,]?\\s*(.*)").find(s)?.groupValues?.get(1)
                    ?.replace(Regex("^заметк\\w*[:,]?\\s*"), "")?.trim().orEmpty()
                if (text.isEmpty()) {
                    startActivity(Intent(this, NotesActivity::class.java))
                } else {
                    Notes.add(this, text); toast("Записал: $text")
                }
            }
            else -> {
                val q = Regex("^(?:найди|найти|ищи|поищи|поиск|покажи|открой)\\s+(?:файл\\w*\\s+)?(.*)$")
                    .find(s)?.groupValues?.get(1) ?: s
                input.setText(q); input.setSelection(q.length)
            }
        }
    }

    // ---------- поиск ----------
    private fun search(query: String) {
        val id = ++searchId
        if (query.length < 2 || !hasAccess()) {
            show(emptyList(), id, query.isNotEmpty())
            return
        }
        info.text = "Ищу…"
        Thread {
            val found = ArrayList<File>()
            val q = query.lowercase()
            val stack = ArrayDeque<File>()
            stack.add(root)
            while (stack.isNotEmpty() && found.size < 100 && id == searchId) {
                val dir = stack.removeLast()
                val children = dir.listFiles() ?: continue
                for (f in children) {
                    if (f.isDirectory) {
                        if (!f.name.startsWith(".")) stack.add(f)
                    } else if (f.name.lowercase().contains(q)) {
                        found.add(f)
                    }
                }
            }
            found.sortByDescending { it.lastModified() }
            runOnUiThread { show(found, id, true) }
        }.start()
    }

    private fun show(found: List<File>, id: Int, searched: Boolean) {
        if (id != searchId) return
        files.clear(); files.addAll(found)
        labels.clear()
        for (f in found) {
            val where = f.parentFile?.path?.removePrefix(root.path) ?: ""
            labels.add(f.name + "\n" + (if (where.isEmpty()) "/" else where))
        }
        lv.clearChoices()
        adapter.notifyDataSetChanged()
        info.text = when {
            found.isNotEmpty() -> "Найдено: ${found.size}"
            searched && input.text.trim().length >= 2 -> "Ничего не найдено"
            else -> "Введи название. Удерживай файл, чтобы открыть."
        }
    }

    // ---------- действия с файлами ----------
    private fun selected(): List<File> = files.indices.filter { lv.isItemChecked(it) }.map { files[it] }
    private fun selectAll() { for (i in files.indices) lv.setItemChecked(i, true) }
    private fun uriOf(f: File): Uri = FileProvider.getUriForFile(this, "$packageName.fileprovider", f)
    private fun mimeOf(f: File): String =
        MimeTypeMap.getSingleton().getMimeTypeFromExtension(f.extension.lowercase()) ?: "*/*"

    private fun copyNames() {
        val sel = selected()
        if (sel.isEmpty()) { toast("Сначала выбери файлы"); return }
        val cm = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        cm.setPrimaryClip(ClipData.newPlainText("files", sel.joinToString("\n") { it.name }))
        toast("Скопировано названий: ${sel.size}")
    }

    private fun sendFiles() {
        val sel = selected()
        if (sel.isEmpty()) { toast("Сначала выбери файлы"); return }
        val intent = if (sel.size == 1) {
            Intent(Intent.ACTION_SEND).setType(mimeOf(sel[0])).putExtra(Intent.EXTRA_STREAM, uriOf(sel[0]))
        } else {
            Intent(Intent.ACTION_SEND_MULTIPLE).setType("*/*")
                .putParcelableArrayListExtra(Intent.EXTRA_STREAM, ArrayList(sel.map { uriOf(it) }))
        }
        intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        try { startActivity(Intent.createChooser(intent, "Отправить")) }
        catch (e: Exception) { toast("Не удалось отправить") }
    }

    private fun editFile() {
        val sel = selected()
        if (sel.size != 1) { toast("Выбери один файл"); return }
        val f = sel[0]
        try {
            startActivity(
                Intent(Intent.ACTION_EDIT).setDataAndType(uriOf(f), mimeOf(f))
                    .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
            )
        } catch (e: Exception) {
            openFile(f)
        }
    }

    private fun openFile(f: File) {
        try {
            startActivity(
                Intent(Intent.ACTION_VIEW).setDataAndType(uriOf(f), mimeOf(f))
                    .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            )
        } catch (e: Exception) {
            toast("Не удалось открыть файл")
        }
    }
}
