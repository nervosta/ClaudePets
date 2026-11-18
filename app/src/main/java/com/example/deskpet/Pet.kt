package com.example.deskpet

import android.Manifest
import android.app.Activity
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.PixelFormat
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.SystemClock
import android.provider.Settings
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import kotlin.math.abs
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.random.Random

class MainActivity : Activity() {
    private val names = arrayOf("Клод", "Рекс", "Пёс", "Мопс", "Летучка")

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val pad = (24 * resources.displayMetrics.density).toInt()
        val prefs = getSharedPreferences("pet", MODE_PRIVATE)
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setPadding(pad, pad, pad, pad)
        }
        root.addView(TextView(this).apply {
            text = "DeskPets\nНажми на питомца, чтобы найти файл. Удерживай, чтобы сказать команду голосом."
            textSize = 18f
            gravity = Gravity.CENTER
        })
        val current = TextView(this).apply { textSize = 16f; gravity = Gravity.CENTER }
        fun refresh() { current.text = "Питомец: " + names[prefs.getInt("kind", 0).coerceIn(0, names.size - 1)] }
        root.addView(current)
        names.forEachIndexed { i, n ->
            root.addView(Button(this).apply {
                text = n
                setOnClickListener { prefs.edit().putInt("kind", i).apply(); refresh() }
            })
        }
        refresh()
        root.addView(Button(this).apply {
            text = "Запустить питомца"
            setOnClickListener { startPet() }
        })
        root.addView(Button(this).apply {
            text = "Убрать питомца"
            setOnClickListener { stopService(Intent(this@MainActivity, PetService::class.java)) }
        })
        root.addView(Button(this).apply {
            text = "Найти файл"
            setOnClickListener { startActivity(Intent(this@MainActivity, SearchActivity::class.java)) }
        })
        root.addView(Button(this).apply {
            text = "Заметки"
            setOnClickListener { startActivity(Intent(this@MainActivity, NotesActivity::class.java)) }
        })
        root.addView(Button(this).apply {
            text = "Нейросеть"
            setOnClickListener { startActivity(Intent(this@MainActivity, AiActivity::class.java)) }
        })
        val scroll = ScrollView(this)
        scroll.isFillViewport = true
        scroll.addView(root)
        setContentView(scroll)
    }

    private fun startPet() {
        if (!Settings.canDrawOverlays(this)) {
            Toast.makeText(this, "Разреши показ поверх других приложений и вернись", Toast.LENGTH_LONG).show()
            startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName")))
            return
        }
        if (Build.VERSION.SDK_INT >= 33) {
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 1)
        }
        ContextCompat.startForegroundService(this, Intent(this, PetService::class.java))
    }
}

enum class PetState { WALK, IDLE, SLEEP, HAPPY }

class PetView(context: Context) : View(context) {
    var state = PetState.IDLE
    var facing = 1
    var kind = 0

    private val p = (5 * resources.displayMetrics.density).toInt()
    val size = 20 * p
    val pixelHeight = 22 * p

    private var t = 0f
    private var oy = 0
    private val paint = Paint().apply { isAntiAlias = false }

    private val white = 0xFFFFFFFF.toInt()
    private val pink = 0xFFE5566D.toInt()
    private val zz = 0xFF8D9BE0.toInt()
    // Клод
    private val cMain = 0xFFD97757.toInt()
    private val cShade = 0xFFC4673F.toInt()
    private val cDk = 0xFFB85C3E.toInt()
    private val cLine = 0xFF5A2D1E.toInt()
    private val cEye = 0xFF2A1F1A.toInt()
    private val cream = 0xFFFAF0E6.toInt()
    // Рекс
    private val gMain = 0xFF5BAE6A.toInt()
    private val gShade = 0xFF3E8A4F.toInt()
    private val gDk = 0xFF2F6B3D.toInt()
    private val gBelly = 0xFFD2EBB8.toInt()
    private val gEye = 0xFF1B2A1F.toInt()
    // Пёс
    private val dMain = 0xFFC8956A.toInt()
    private val dShade = 0xFFA9774E.toInt()
    private val dDk = 0xFF6B4528.toInt()
    private val dEye = 0xFF2A1B12.toInt()
    // Мопс
    private val fawn = 0xFFE3C48F.toInt()
    private val fawnSh = 0xFFC9A46B.toInt()
    private val mask = 0xFF3A2B22.toInt()
    // Летучка
    private val bBody = 0xFF5B4B8A.toInt()
    private val bDark = 0xFF3A2F5C.toInt()
    private val bWing = 0xFF7A68B0.toInt()
    private val bGlow = 0xFFFFD84D.toInt()

    override fun onMeasure(w: Int, h: Int) = setMeasuredDimension(size, pixelHeight)

    fun step() { t += 0.25f; invalidate() }

    private fun r(c: Canvas, x: Int, y: Int, w: Int, h: Int, col: Int) {
        val xx = if (facing < 0) 20 - x - w else x
        val dy = if (kind == 0) 2 else 0
        paint.color = col
        val top = (y + 6 + dy + oy) * p
        c.drawRect((xx * p).toFloat(), top.toFloat(), ((xx + w) * p).toFloat(), (top + h * p).toFloat(), paint)
    }

    override fun onDraw(c: Canvas) {
        val k = kind.coerceIn(0, 4)
        val up = state == PetState.HAPPY
        val walk = state == PetState.WALK
        val sleep = state == PetState.SLEEP
        val fr = (t / 1.9f).toInt() % 2
        val bob = when {
            walk -> fr
            sleep -> (t / 5f).toInt() % 2
            up -> -(abs(sin(t * 0.8f)) * 3).roundToInt()
            else -> 0
        }
        when (k) {
            0 -> claude(c, up, walk, sleep, fr, bob)
            1 -> rex(c, up, walk, sleep, fr, bob)
            2 -> dog(c, up, walk, sleep, fr, bob)
            3 -> pug(c, up, walk, sleep, fr, bob)
            else -> bat(c, up, walk, sleep, fr, bob)
        }
        oy = bob
        if (sleep) {
            val zx = intArrayOf(14, 17, 16, 16, 14)[k]
            val zy = intArrayOf(-2, -2, 1, 1, -2)[k] - ((t % 12f) / 4f).toInt()
            r(c, zx, zy, 3, 1, zz); r(c, zx + 1, zy + 1, 1, 1, zz); r(c, zx, zy + 2, 3, 1, zz)
        }
        if (up) {
            val hx = intArrayOf(6, 13, 13, 13, 8)[k]
            val hy = intArrayOf(0, 0, 3, 3, 0)[k]
            r(c, hx + 1, -3 + hy, 1, 1, pink); r(c, hx + 3, -3 + hy, 1, 1, pink)
            r(c, hx, -2 + hy, 5, 1, pink); r(c, hx + 1, -1 + hy, 3, 1, pink); r(c, hx + 2, hy, 1, 1, pink)
        }
    }

    private fun claude(c: Canvas, up: Boolean, walk: Boolean, sleep: Boolean, fr: Int, bob: Int) {
        oy = bob
        fun q(x: Int, y: Int, w: Int, h: Int, col: Int) = r(c, x + 2, y, w, h, col)
        val legs = intArrayOf(4, 6, 9, 11)
        fun drawLegs() = legs.forEachIndexed { i, x ->
            val h = if (walk && ((i % 2 == 0) == (fr == 0))) 1 else 2
            q(x, 12, 1, h, cDk)
        }
        drawLegs()
        q(4, 2, 8, 1, cLine); q(3, 3, 10, 1, cLine); q(2, 4, 12, 7, cLine)
        q(3, 11, 10, 1, cLine); q(4, 12, 8, 1, cLine)
        q(4, 3, 8, 1, cMain); q(3, 4, 10, 6, cMain)
        q(3, 10, 10, 1, cShade); q(4, 11, 8, 1, cShade)
        drawLegs()
        val al = if (up) 4 else if (walk) 7 + fr else 7
        val ar = if (up) 4 else if (walk) 8 - fr else 7
        q(1, al, 2, 2, cDk); q(13, ar, 2, 2, cDk)
        val plus = (t / (if (up) 2f else 7f)).toInt() % 2 == 0
        if (plus) {
            q(8, 4, 1, 1, cream); q(7, 5, 3, 1, cream); q(8, 6, 1, 1, cream)
        } else {
            q(7, 4, 1, 1, cream); q(9, 4, 1, 1, cream); q(8, 5, 1, 1, cream)
            q(7, 6, 1, 1, cream); q(9, 6, 1, 1, cream)
        }
        for (x0 in intArrayOf(5, 9)) {
            val x = x0 + 1
            when {
                up -> { q(x, 9, 1, 1, cEye); q(x + 1, 8, 1, 1, cEye); q(x + 2, 9, 1, 1, cEye) }
                sleep || (t % 24f) < 1.5f -> q(x, 9, 2, 1, cEye)
                else -> q(x, 8, 2, 2, cEye)
            }
        }
    }

    private fun rex(c: Canvas, up: Boolean, walk: Boolean, sleep: Boolean, fr: Int, bob: Int) {
        oy = if (up) bob else 0
        val aH = if (walk && fr == 1) 2 else 3
        val bH = if (walk && fr == 0) 2 else 3
        r(c, 6, 13, 2, aH, gDk); r(c, 8, 12 + aH, 1, 1, gDk)
        r(c, 10, 13, 2, bH, gDk); r(c, 12, 12 + bH, 1, 1, gDk)
        oy = bob
        val tip = if (walk) fr else 0
        r(c, 4, 7, 2, 3, gMain); r(c, 2, 8, 2, 2, gMain); r(c, 0, 9 + tip, 2, 1, gShade)
        r(c, 6, 5, 1, 1, gDk); r(c, 8, 5, 1, 1, gDk); r(c, 10, 5, 1, 1, gDk)
        r(c, 5, 6, 8, 7, gMain)
        r(c, 5, 12, 4, 1, gShade)
        r(c, 9, 9, 4, 4, gBelly)
        r(c, 11, 4, 3, 3, gMain)
        r(c, 11, 2, 8, 5, gMain)
        r(c, 12, 1, 6, 1, gMain)
        r(c, 18, 3, 1, 1, gShade)
        if (up) {
            r(c, 13, 7, 4, 1, pink)
            r(c, 12, 8, 6, 1, gShade)
        } else {
            r(c, 12, 7, 6, 1, gShade)
            r(c, 15, 7, 1, 1, white); r(c, 17, 7, 1, 1, white)
        }
        when {
            up -> { r(c, 14, 4, 1, 1, gEye); r(c, 15, 3, 1, 1, gEye); r(c, 16, 4, 1, 1, gEye) }
            sleep || (t % 24f) < 1.5f -> r(c, 14, 4, 2, 1, gEye)
            else -> { r(c, 14, 3, 2, 2, gEye); r(c, 14, 3, 1, 1, white) }
        }
        if (up) { r(c, 13, 8, 2, 1, gDk); r(c, 14, 7, 1, 1, gDk) }
        else r(c, 13, 9, 2, 1, gDk)
    }

    private fun pug(c: Canvas, up: Boolean, walk: Boolean, sleep: Boolean, fr: Int, bob: Int) {
        oy = if (up) bob else 0
        intArrayOf(5, 7, 10, 12).forEachIndexed { i, x ->
            val h = if (walk && ((i % 2 == 0) == (fr == 0))) 2 else 3
            r(c, x, 13, 1, h, fawnSh)
        }
        oy = bob
        r(c, 3, 9, 1, 1, fawnSh); r(c, 2, 8, 1, 2, fawnSh); r(c, 3, 7 + fr, 1, 1, fawnSh)
        r(c, 4, 8, 10, 5, fawn)
        r(c, 4, 12, 10, 1, fawnSh)
        r(c, 12, 3, 7, 7, fawn)
        r(c, 12, 3, 2, 3, mask); r(c, 17, 3, 2, 2, mask)
        r(c, 14, 4, 4, 1, fawnSh)
        r(c, 16, 7, 3, 3, mask)
        when {
            up -> { r(c, 14, 6, 1, 1, mask); r(c, 15, 5, 1, 1, mask); r(c, 16, 6, 1, 1, mask) }
            sleep || (t % 24f) < 1.5f -> r(c, 15, 6, 2, 1, mask)
            else -> { r(c, 15, 5, 2, 2, mask); r(c, 15, 5, 1, 1, white) }
        }
        if (up) r(c, 17, 10, 1, 2, pink)
    }

    private fun bat(c: Canvas, up: Boolean, walk: Boolean, sleep: Boolean, fr: Int, bob: Int) {
        oy = if (up) bob else 0
        r(c, 8, 14, 1, 2, bDark); r(c, 11, 14, 1, 2, bDark)
        oy = bob
        if (sleep) {
            r(c, 5, 8, 2, 6, bWing); r(c, 13, 8, 2, 6, bWing)
        } else if (walk || up) {
            if (fr == 0) {
                r(c, 2, 8, 5, 2, bWing); r(c, 13, 8, 5, 2, bWing)
                r(c, 1, 10, 3, 1, bWing); r(c, 16, 10, 3, 1, bWing)
            } else {
                r(c, 2, 5, 5, 2, bWing); r(c, 13, 5, 5, 2, bWing)
                r(c, 1, 4, 3, 1, bWing); r(c, 16, 4, 3, 1, bWing)
            }
        } else {
            r(c, 3, 8, 4, 3, bWing); r(c, 13, 8, 4, 3, bWing)
            r(c, 2, 11, 3, 1, bWing); r(c, 15, 11, 3, 1, bWing)
        }
        r(c, 7, 4, 6, 10, bBody)
        r(c, 8, 10, 4, 4, bWing)
        r(c, 7, 2, 2, 2, bDark); r(c, 11, 2, 2, 2, bDark)
        when {
            up -> {
                r(c, 8, 6, 1, 1, bGlow); r(c, 9, 5, 1, 1, bGlow)
                r(c, 10, 5, 1, 1, bGlow); r(c, 11, 6, 1, 1, bGlow)
            }
            sleep || (t % 24f) < 1.5f -> { r(c, 8, 6, 1, 1, bDark); r(c, 11, 6, 1, 1, bDark) }
            else -> { r(c, 8, 5, 1, 2, bGlow); r(c, 11, 5, 1, 2, bGlow) }
        }
        r(c, 8, 8, 1, 1, white); r(c, 11, 8, 1, 1, white)
    }

    private fun dog(c: Canvas, up: Boolean, walk: Boolean, sleep: Boolean, fr: Int, bob: Int) {
        oy = if (up) bob else 0
        intArrayOf(4, 6, 10, 12).forEachIndexed { i, x ->
            val h = if (walk && ((i % 2 == 0) == (fr == 0))) 2 else 3
            r(c, x, 13, 1, h, dDk)
        }
        oy = bob
        if (sleep) {
            r(c, 2, 9, 2, 1, dMain)
        } else {
            if (fr == 0) r(c, 2, 5, 1, 3, dMain) else r(c, 3, 5, 1, 3, dMain)
            r(c, 3, 8, 1, 1, dMain)
        }
        r(c, 4, 8, 10, 5, dMain)
        r(c, 4, 12, 10, 1, dShade)
        r(c, 7, 9, 2, 2, dShade)
        r(c, 12, 4, 6, 5, dMain)
        r(c, 17, 6, 2, 3, dMain)
        r(c, 18, 6, 1, 1, dEye)
        r(c, 12, 4, 2, 4, dDk)
        r(c, 12, 8, 2, 2, pink)
        when {
            up -> { r(c, 14, 6, 1, 1, dEye); r(c, 15, 5, 1, 1, dEye); r(c, 16, 6, 1, 1, dEye) }
            sleep -> r(c, 14, 6, 2, 1, dEye)
            (t % 24f) < 1.5f -> r(c, 15, 6, 1, 1, dEye)
            else -> r(c, 15, 5, 1, 2, dEye)
        }
        r(c, 16, 8, 2, 1, dShade)
        if (up) r(c, 17, 9, 1, 2, pink)
    }
}

class PetService : Service() {
    private lateinit var wm: WindowManager
    private lateinit var pet: PetView
    private lateinit var lp: WindowManager.LayoutParams
    private val handler = Handler(Looper.getMainLooper())
    private val prefs by lazy { getSharedPreferences("pet", Context.MODE_PRIVATE) }
    private var stateUntil = 0L
    private var dragging = false

    private val loop = object : Runnable {
        override fun run() { tick(); handler.postDelayed(this, 33) }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        startAsForeground()
        wm = getSystemService(WINDOW_SERVICE) as WindowManager
        pet = PetView(this)
        val dm = resources.displayMetrics
        lp = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = dm.widthPixels / 3
            y = dm.heightPixels - pet.pixelHeight - (160 * dm.density).toInt()
        }
        pet.setOnTouchListener(touch())
        wm.addView(pet, lp)
        handler.post(loop)
    }

    private fun openSearch(voice: Boolean) {
        startActivity(
            Intent(this, SearchActivity::class.java)
                .putExtra("voice", voice)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        )
    }

    private fun touch(): View.OnTouchListener {
        var sx = 0f; var sy = 0f; var ox = 0; var oy = 0
        var moved = false; var longFired = false
        val longPress = Runnable {
            longFired = true
            pet.state = PetState.HAPPY
            stateUntil = SystemClock.uptimeMillis() + 1600
            openSearch(true)
        }
        return View.OnTouchListener { _, e ->
            when (e.action) {
                MotionEvent.ACTION_DOWN -> {
                    sx = e.rawX; sy = e.rawY; ox = lp.x; oy = lp.y
                    moved = false; longFired = false; dragging = true
                    handler.postDelayed(longPress, 500)
                }
                MotionEvent.ACTION_MOVE -> {
                    if (abs(e.rawX - sx) > 12 || abs(e.rawY - sy) > 12) {
                        moved = true
                        handler.removeCallbacks(longPress)
                    }
                    if (moved && !longFired) {
                        lp.x = ox + (e.rawX - sx).toInt()
                        lp.y = oy + (e.rawY - sy).toInt()
                        wm.updateViewLayout(pet, lp)
                    }
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    handler.removeCallbacks(longPress)
                    dragging = false
                    if (longFired) {
                        // голосовая команда уже запущена
                    } else if (moved) {
                        pet.state = PetState.IDLE
                        stateUntil = SystemClock.uptimeMillis() + 800
                    } else if (e.action == MotionEvent.ACTION_UP) {
                        pet.state = PetState.HAPPY
                        stateUntil = SystemClock.uptimeMillis() + 1600
                        openSearch(false)
                    }
                }
            }
            true
        }
    }

    private fun tick() {
        pet.kind = prefs.getInt("kind", 0)
        val now = SystemClock.uptimeMillis()
        if (!dragging && now >= stateUntil) {
            when (Random.nextInt(10)) {
                in 0..5 -> { pet.state = PetState.WALK; stateUntil = now + Random.nextLong(3000, 8000); pet.facing = if (Random.nextBoolean()) 1 else -1 }
                in 6..7 -> { pet.state = PetState.IDLE; stateUntil = now + Random.nextLong(2000, 4000) }
                else -> { pet.state = PetState.SLEEP; stateUntil = now + Random.nextLong(6000, 12000) }
            }
        }
        if (!dragging && pet.state == PetState.WALK) {
            val dm = resources.displayMetrics
            lp.x += pet.facing * (dm.density * 2.5f).toInt()
            if (lp.x < 0) { lp.x = 0; pet.facing = 1 }
            if (lp.x > dm.widthPixels - pet.size) { lp.x = dm.widthPixels - pet.size; pet.facing = -1 }
            wm.updateViewLayout(pet, lp)
        }
        pet.step()
    }

    private fun startAsForeground() {
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel("pet", "DeskPets", NotificationManager.IMPORTANCE_LOW))
        val stop = PendingIntent.getService(
            this, 0, Intent(this, PetService::class.java).setAction("STOP"),
            PendingIntent.FLAG_IMMUTABLE
        )
        val n = Notification.Builder(this, "pet")
            .setSmallIcon(android.R.drawable.star_on)
            .setContentTitle("Питомец гуляет")
            .addAction(Notification.Action.Builder(null, "Убрать", stop).build())
            .build()
        ServiceCompat.startForeground(
            this, 1, n,
            if (Build.VERSION.SDK_INT >= 34) ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE else 0
        )
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == "STOP") { stopSelf(); return START_NOT_STICKY }
        return START_STICKY
    }

    override fun onDestroy() {
        handler.removeCallbacks(loop)
        if (::pet.isInitialized) wm.removeView(pet)
        super.onDestroy()
    }
}
