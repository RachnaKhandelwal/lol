@file:Suppress("DEPRECATION")
package com.ambitrace

import android.Manifest
import android.app.Activity
import android.content.*
import android.content.pm.PackageManager
import android.graphics.*
import android.net.wifi.WifiManager
import android.os.*
import android.view.*
import android.widget.*
import androidx.core.content.ContextCompat
import kotlin.math.hypot
import kotlin.math.sqrt

const val W = 60f   // floor width in metres (x axis)
const val H = 40f   // floor height in metres (y axis)
const val MISSING = -100
class Fp(val x: Float, val y: Float, val v: Map<String, Int>)

class FloorView(c: Context) : View(c) {
    var pts = listOf<Fp>(); var pin: PointF? = null; var est: PointF? = null
    var onPin: () -> Unit = {}
    private val p = Paint(Paint.ANTI_ALIAS_FLAG)
    override fun onDraw(cv: Canvas) {
        val sx = width / W; val sy = height / H
        p.style = Paint.Style.STROKE; p.strokeWidth = 4f; p.color = Color.DKGRAY
        cv.drawRect(2f, 2f, width - 2f, height - 2f, p)
        p.style = Paint.Style.FILL; p.color = Color.LTGRAY
        pts.forEach { cv.drawCircle(it.x * sx, it.y * sy, 7f, p) }
        pin?.let { p.color = 0xFF0B7A86.toInt(); cv.drawCircle(it.x * sx, it.y * sy, 15f, p) }
        est?.let { p.style = Paint.Style.STROKE; p.strokeWidth = 7f; p.color = 0xFFD6205A.toInt(); cv.drawCircle(it.x * sx, it.y * sy, 28f, p) }
    }
    override fun onTouchEvent(e: MotionEvent): Boolean {
        if (e.action == MotionEvent.ACTION_DOWN || e.action == MotionEvent.ACTION_MOVE) {
            pin = PointF((e.x / width * W).coerceIn(0f, W), (e.y / height * H).coerceIn(0f, H))
            onPin(); invalidate()
        }
        return true
    }
}

class MainActivity : Activity() {
    private lateinit var wifi: WifiManager
    private lateinit var floor: FloorView
    private lateinit var status: TextView
    private val fps = mutableListOf<Fp>()
    private var locating = false
    private var recording = 0
    private val acc = mutableListOf<Map<String, Int>>()
    private val window = ArrayDeque<Map<String, Int>>()
    private val h = Handler(Looper.getMainLooper())
    private val prefs by lazy { getSharedPreferences("ambitrace", MODE_PRIVATE) }

    private val rx = object : BroadcastReceiver() { override fun onReceive(c: Context, i: Intent) = onScan() }
    private val loop = object : Runnable {
        override fun run() { if (recording > 0 || locating) wifi.startScan(); h.postDelayed(this, 3000) }
    }

    override fun onCreate(b: Bundle?) {
        super.onCreate(b)
        wifi = applicationContext.getSystemService(WIFI_SERVICE) as WifiManager
        status = TextView(this).apply { setPadding(24, 40, 24, 16); textSize = 15f; text = "Survey mode. Tap the map where you stand, then press Record here." }
        floor = FloorView(this).also { it.onPin = { if (locating) showError() } }
        val clip = getSystemService(CLIPBOARD_SERVICE) as ClipboardManager
        fun btn(t: String, f: (Button) -> Unit) = Button(this).apply { text = t; isAllCaps = false; setOnClickListener { f(this) } }
        fun row(vararg v: View) = LinearLayout(this).apply { v.forEach { addView(it, LinearLayout.LayoutParams(0, -2, 1f)) } }
        val modeBtn = btn("Mode: Survey") { bt ->
            locating = !locating; window.clear(); floor.est = null
            bt.text = if (locating) "Mode: Locate" else "Mode: Survey"
            say(if (locating) "Locate mode. Walk around; the pink ring is your estimate." else "Survey mode. Tap the map where you stand, then press Record here.")
            floor.invalidate()
        }
        val rec = btn("Record here") {
            if (locating) say("Switch to Survey mode first.")
            else if (floor.pin == null) say("Tap the map to pin your spot first.")
            else { acc.clear(); recording = 3; say("Recording, keep still…"); wifi.startScan() }
        }
        val exp = btn("Export") {
            val csv = toCsv()
            clip.setPrimaryClip(ClipData.newPlainText("AmbiTrace", csv))
            startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, csv), "Export survey"))
        }
        val imp = btn("Import") {
            val t = clip.primaryClip?.getItemAt(0)?.coerceToText(this)?.toString() ?: ""
            if (fromCsv(t)) { persist(); say("Imported ${fps.size} points from the clipboard.") } else say("The clipboard does not hold an AmbiTrace survey.")
        }
        val clr = btn("Clear") { fps.clear(); persist(); say("Survey cleared.") }
        setContentView(LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            addView(status)
            addView(floor, LinearLayout.LayoutParams(-1, 0, 1f).apply { setMargins(16, 0, 16, 0) })
            addView(row(modeBtn, rec)); addView(row(exp, imp, clr))
        })
        fromCsv(prefs.getString("csv", "") ?: ""); floor.pts = fps.toList()
        ContextCompat.registerReceiver(this, rx, IntentFilter(WifiManager.SCAN_RESULTS_AVAILABLE_ACTION), ContextCompat.RECEIVER_NOT_EXPORTED)
        if (checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED)
            requestPermissions(arrayOf(Manifest.permission.ACCESS_FINE_LOCATION), 1)
        h.post(loop)
    }

    override fun onDestroy() { super.onDestroy(); h.removeCallbacks(loop); unregisterReceiver(rx) }

    private fun say(t: String) { status.text = t; floor.pts = fps.toList(); floor.invalidate() }
    private fun persist() { prefs.edit().putString("csv", toCsv()).apply() }

    private fun onScan() {
        val s = try { wifi.scanResults.associate { it.BSSID to it.level } } catch (e: SecurityException) { say("Allow location permission to read Wi-Fi signals."); return }
        if (recording > 0) {
            acc.add(s); recording--
            if (recording == 0) {
                val p = floor.pin ?: return
                fps.add(Fp(p.x, p.y, mean(acc))); persist()
                say("Saved point ${fps.size} at ${"%.1f".format(p.x)}, ${"%.1f".format(p.y)} m (${fps.last().v.size} transmitters).")
            } else say("Recording, $recording scans left…")
        } else if (locating) {
            window.addLast(s); while (window.size > 3) window.removeFirst()
            floor.est = estimate(mean(window.toList()))
            if (floor.est == null) say("Survey is empty. Switch to Survey mode and record points.") else showError()
            floor.invalidate()
        }
    }

    private fun showError() {
        val e = floor.est ?: return
        val pin = floor.pin
        say("Estimate ${"%.1f".format(e.x)}, ${"%.1f".format(e.y)} m" + if (pin != null) " | error ${"%.1f".format(hypot(e.x - pin.x, e.y - pin.y))} m from your pin" else " (tap the map to compare with your real spot)")
    }

    private fun mean(l: List<Map<String, Int>>): Map<String, Int> =
        l.flatMap { it.keys }.toSet().associateWith { k -> l.sumOf { it[k] ?: MISSING } / l.size }

    private fun estimate(q: Map<String, Int>, k: Int = 4): PointF? {
        if (fps.isEmpty()) return null
        val r = fps.map { f ->
            var d = 0.0
            (f.v.keys + q.keys).forEach { key -> val a = (f.v[key] ?: MISSING) - (q[key] ?: MISSING); d += a * a }
            sqrt(d) to f
        }.sortedBy { it.first }.take(k)
        var sx = 0.0; var sy = 0.0; var sw = 0.0
        r.forEach { (d, f) -> val w = 1 / (d + 0.5); sx += w * f.x; sy += w * f.y; sw += w }
        return PointF((sx / sw).toFloat(), (sy / sw).toFloat())
    }

    private fun toCsv(): String {
        val keys = fps.flatMap { it.v.keys }.toSortedSet()
        return "x,y," + keys.joinToString(",") + "\n" +
            fps.joinToString("\n") { f -> "%.1f,%.1f,".format(f.x, f.y) + keys.joinToString(",") { (f.v[it] ?: MISSING).toString() } }
    }

    private fun fromCsv(t: String): Boolean {
        val l = t.trim().lines().map { it.split(',').map(String::trim) }
        val hd = l.firstOrNull() ?: return false
        if (hd.size < 3 || hd[0] != "x") return false
        val out = l.drop(1).mapNotNull { r ->
            val x = r.getOrNull(0)?.toFloatOrNull(); val y = r.getOrNull(1)?.toFloatOrNull()
            if (x == null || y == null || r.size != hd.size) null
            else Fp(x, y, (2 until hd.size).mapNotNull { i -> r[i].toIntOrNull()?.let { hd[i] to it } }.toMap())
        }
        fps.clear(); fps.addAll(out); floor.pts = fps.toList(); return true
    }
}
