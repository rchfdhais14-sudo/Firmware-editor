package com.example.firmwareeditor

import android.animation.ArgbEvaluator
import android.animation.ValueAnimator
import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.OpenableColumns
import android.text.Editable
import android.text.InputType
import android.text.TextUtils
import android.text.TextWatcher
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.animation.LinearInterpolator
import android.view.animation.OvershootInterpolator
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import java.io.BufferedOutputStream
import java.io.File
import java.io.FileInputStream
import java.io.InputStream
import java.io.OutputStream
import java.util.Locale
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipOutputStream
import kotlin.math.PI
import kotlin.math.sin

class ZE(val name: String, val size: Long, val isDir: Boolean)
class VE(val name: String, val size: Long, val src: String?, val file: File?)

class MainActivity : Activity() {

    private lateinit var root: FrameLayout
    private val handler = Handler(Looper.getMainLooper())
    private var anim: ValueAnimator? = null
    private var dlg: AlertDialog? = null
    private var dlgText: TextView? = null

    // состояние прошивки
    private var zip: ZipFile? = null
    private var srcFile: File? = null
    private var zipTitle = ""
    private val entries = mutableListOf<ZE>()
    private val deleted = HashSet<String>()
    private val renamed = HashMap<String, String>()
    private val replaced = HashMap<String, File>()
    private val added = LinkedHashMap<String, File>()
    private var view: List<VE> = emptyList()
    private var viewMap: Map<String, VE> = HashMap()

    // навигация
    private var screenName = "splash"
    private var path = ""
    private var query = ""
    private val selected = LinkedHashSet<String>()
    private var listBox: LinearLayout? = null
    private var crumbs: LinearLayout? = null
    private var bottom: LinearLayout? = null
    private var chip: TextView? = null
    private var pendingExtract: VE? = null
    private var editVE: VE? = null
    private var editBox: EditText? = null
    private var editOriginal = ""

    private val bg = 0xFFF7F8FC.toInt()
    private val ink = 0xFF1A1C22.toInt()
    private val grey = 0xFF666B78.toInt()
    private val primary = 0xFF2F5BEA.toInt()
    private val pc = 0xFFDCE4FF.toInt()
    private val white = 0xFFFFFFFF.toInt()
    private val danger = 0xFFB3261E.toInt()
    private val MATCH = ViewGroup.LayoutParams.MATCH_PARENT
    private val WRAP = ViewGroup.LayoutParams.WRAP_CONTENT

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        root = FrameLayout(this)
        root.setBackgroundColor(bg)
        setContentView(root)
        showSplash()
    }

    // ---------- helpers ----------
    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()

    private fun rounded(color: Int, r: Int): GradientDrawable {
        val d = GradientDrawable()
        d.setColor(color)
        d.cornerRadius = dp(r).toFloat()
        return d
    }

    private fun tv(text: String, sp: Float, color: Int, bold: Boolean): TextView {
        val t = TextView(this)
        t.text = text
        t.textSize = sp
        t.setTextColor(color)
        if (bold) t.setTypeface(Typeface.DEFAULT, Typeface.BOLD)
        return t
    }

    private fun lp(w: Int, h: Int, top: Int = 0, bottom: Int = 0): LinearLayout.LayoutParams {
        val p = LinearLayout.LayoutParams(w, h)
        p.topMargin = dp(top)
        p.bottomMargin = dp(bottom)
        return p
    }

    private fun pill(text: String, filled: Boolean, color: Int = primary): TextView {
        val b = tv(text, 15f, if (filled) white else color, true)
        b.gravity = Gravity.CENTER
        b.setPadding(dp(18), dp(14), dp(18), dp(14))
        b.background = rounded(if (filled) color else white, 24)
        return b
    }

    private fun sizeText(b: Long): String {
        val mb = b / 1048576.0
        return when {
            b < 1024 -> "$b Б"
            b < 1048576 -> String.format(Locale.getDefault(), "%.1f КБ", b / 1024.0)
            mb < 1024 -> String.format(Locale.getDefault(), "%.1f МБ", mb)
            else -> String.format(Locale.getDefault(), "%.2f ГБ", mb / 1024.0)
        }
    }

    private fun toast(s: String) = Toast.makeText(this, s, Toast.LENGTH_LONG).show()

    private fun busy(msg: String) {
        val t = tv(msg, 15f, ink, false)
        t.setPadding(dp(24), dp(20), dp(24), dp(20))
        dlgText = t
        dlg = AlertDialog.Builder(this).setView(t).setCancelable(false).show()
    }

    private fun busyMsg(msg: String) {
        runOnUiThread { dlgText?.text = msg }
    }

    private fun idle() {
        dlg?.dismiss()
        dlg = null
    }

    private fun workDir(): File {
        val d = File(filesDir, "work")
        d.mkdirs()
        return d
    }

    private fun nameOf(uri: Uri): String {
        var n = "file"
        contentResolver.query(uri, null, null, null, null)?.use { c ->
            if (c.moveToFirst()) {
                val i = c.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                if (i >= 0) n = c.getString(i)
            }
        }
        return n
    }

    private fun changes(): Int = deleted.size + renamed.size + replaced.size + added.size

    private fun isProtected(n: String): Boolean {
        val l = n.lowercase()
        return l.startsWith("meta-inf/") || l.endsWith("updater-script") || l.endsWith("boot.img") ||
            l.endsWith("system.img") || l.endsWith("recovery.img") || l.endsWith("transfer.list") ||
            l.endsWith(".new.dat") || l.endsWith(".new.dat.br") || l.endsWith(".patch.dat") ||
            l.endsWith("payload.bin") || l.endsWith("build.prop")
    }

    private fun icon(n: String): String {
        val l = n.lowercase()
        return when {
            l.endsWith(".png") || l.endsWith(".jpg") || l.endsWith(".jpeg") || l.endsWith(".webp") -> "🖼"
            l.endsWith(".apk") -> "📱"
            l.endsWith(".img") || l.endsWith(".dat") || l.endsWith(".br") || l.endsWith(".bin") || l.endsWith(".zip") -> "📦"
            l.endsWith(".so") || l.endsWith(".jar") || l.endsWith(".odex") || l.endsWith(".oat") -> "⚙"
            l.endsWith(".prop") || l.endsWith(".xml") || l.endsWith(".sh") || l.endsWith(".txt") ||
                l.endsWith(".rc") || l.endsWith(".conf") || l.endsWith("updater-script") -> "📝"
            else -> "📄"
        }
    }

    // ---------- загрузка ----------
    private fun showSplash() {
        val s = LinearLayout(this)
        s.orientation = LinearLayout.VERTICAL
        s.gravity = Gravity.CENTER
        s.setBackgroundColor(bg)
        val shape = View(this)
        val gd = GradientDrawable()
        gd.setColor(primary)
        shape.background = gd
        s.addView(shape, LinearLayout.LayoutParams(dp(96), dp(96)))
        val track = FrameLayout(this)
        track.background = rounded(pc, 3)
        val bar = View(this)
        bar.background = rounded(primary, 3)
        track.addView(bar, FrameLayout.LayoutParams(dp(56), dp(6)))
        s.addView(track, lp(dp(160), dp(6), 56))
        s.addView(tv("Редактор прошивок", 22f, ink, true), lp(WRAP, WRAP, 32))
        root.addView(s, FrameLayout.LayoutParams(MATCH, MATCH))
        val ev = ArgbEvaluator()
        val a = ValueAnimator.ofFloat(0f, 1f)
        a.duration = 3200
        a.repeatCount = ValueAnimator.INFINITE
        a.interpolator = LinearInterpolator()
        a.addUpdateListener { v ->
            val f = v.animatedValue as Float
            val k = ((sin(f * 4.0 * PI) + 1.0) / 2.0).toFloat()
            gd.cornerRadius = dp(48) * (1f - 0.62f * k)
            gd.setColor(ev.evaluate(k, primary, 0xFF8E4EC6.toInt()) as Int)
            shape.rotation = f * 360f
            val sc = 1f + 0.1f * sin(f * 2.0 * PI).toFloat()
            shape.scaleX = sc
            shape.scaleY = sc
            val m = ((sin(f * 2.0 * PI * 1.25) + 1.0) / 2.0).toFloat()
            bar.translationX = dp(104) * m
        }
        a.start()
        anim = a
        handler.postDelayed({
            s.animate().alpha(0f).setDuration(450).withEndAction {
                anim?.cancel()
                showHome()
            }.start()
        }, 2400)
    }

    // ---------- главный экран ----------
    private fun showHome() {
        screenName = "home"
        root.removeAllViews()
        val l = LinearLayout(this)
        l.orientation = LinearLayout.VERTICAL
        l.setBackgroundColor(bg)
        l.setPadding(dp(24), dp(28), dp(24), dp(28))
        l.addView(tv("Редактор прошивок", 34f, ink, true))
        l.addView(tv("Откройте ZIP, удалите лишнее, добавьте своё и сохраните новый архив", 15f, grey, false), lp(WRAP, WRAP, 6, 24))

        val card = LinearLayout(this)
        card.orientation = LinearLayout.VERTICAL
        card.setPadding(dp(20), dp(18), dp(20), dp(18))
        card.background = rounded(white, 24)
        card.addView(tv("Что можно делать", 16f, ink, true))
        val points = arrayOf(
            "Смотреть содержимое по папкам и искать по имени",
            "Удалять файлы и папки, отменять правки",
            "Переименовывать и добавлять свои файлы",
            "Править текстовые файлы (updater-script, build.prop)",
            "Просматривать картинки и извлекать файлы",
            "Сохранять результат в новый ZIP"
        )
        for (p in points) card.addView(tv("•  $p", 14f, grey, false), lp(WRAP, WRAP, 6))
        l.addView(card, lp(MATCH, WRAP))

        val warn = tv("Правки меняют только файлы внутри ZIP. Содержимое образов (system.img, *.dat) не редактируется. После сохранения подпись архива сбрасывается: он подходит для TWRP, но стоковый recovery такой файл может не принять.", 12f, grey, false)
        warn.setPadding(dp(4), dp(14), dp(4), 0)
        l.addView(warn)

        l.addView(View(this), LinearLayout.LayoutParams(0, 0, 1f))
        val b = pill("Открыть ZIP-прошивку", true)
        b.setOnClickListener {
            val i = Intent(Intent.ACTION_OPEN_DOCUMENT)
            i.addCategory(Intent.CATEGORY_OPENABLE)
            i.type = "*/*"
            i.putExtra(Intent.EXTRA_MIME_TYPES, arrayOf("application/zip", "application/x-zip-compressed", "application/octet-stream"))
            startActivityForResult(i, 1)
        }
        l.addView(b, lp(MATCH, WRAP))
        root.addView(l, FrameLayout.LayoutParams(MATCH, MATCH))
        l.alpha = 0f
        l.animate().alpha(1f).setDuration(300).start()
    }

    // ---------- результаты выбора файлов ----------
    @Deprecated("Deprecated in Java")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (resultCode != RESULT_OK || data == null) return
        when (requestCode) {
            1 -> data.data?.let { openZip(it) }
            2 -> {
                val uris = mutableListOf<Uri>()
                val cd = data.clipData
                if (cd != null) for (i in 0 until cd.itemCount) uris.add(cd.getItemAt(i).uri)
                else data.data?.let { uris.add(it) }
                addFiles(uris)
            }
            3 -> data.data?.let { extractTo(it) }
            4 -> data.data?.let { saveZip(it) }
        }
    }

    private fun openZip(uri: Uri) {
        busy("Копируем прошивку…\nЭто может занять время")
        Thread {
            var err = ""
            try {
                zip?.close()
                zip = null
                workDir().deleteRecursively()
                val name = nameOf(uri)
                val f = File(workDir(), "source.zip")
                val input = contentResolver.openInputStream(uri) ?: throw Exception("не удалось открыть")
                input.use { i -> f.outputStream().use { o -> i.copyTo(o) } }
                val z = ZipFile(f)
                val list = mutableListOf<ZE>()
                val en = z.entries()
                while (en.hasMoreElements()) {
                    val e = en.nextElement()
                    list.add(ZE(e.name, e.size, e.isDirectory))
                }
                zip = z
                srcFile = f
                zipTitle = name
                entries.clear()
                entries.addAll(list)
                deleted.clear(); renamed.clear(); replaced.clear(); added.clear(); selected.clear()
                path = ""
                query = ""
            } catch (e: Exception) {
                err = "Не удалось открыть: это не ZIP-архив или он повреждён"
            }
            runOnUiThread {
                idle()
                if (err.isNotEmpty()) toast(err) else showEditor()
            }
        }.start()
    }

    private fun addFiles(uris: List<Uri>) {
        busy("Добавляем файлы…")
        Thread {
            for ((i, u) in uris.withIndex()) {
                try {
                    val n = nameOf(u)
                    val f = File(workDir(), "add_" + System.currentTimeMillis() + "_" + i)
                    val input = contentResolver.openInputStream(u) ?: continue
                    input.use { s -> f.outputStream().use { o -> s.copyTo(o) } }
                    val full = path + n
                    val existing = viewMap[full]
                    if (existing != null && existing.src != null) replaced[existing.src] = f
                    else added[full] = f
                } catch (e: Exception) {
                }
            }
            runOnUiThread {
                idle()
                refresh()
            }
        }.start()
    }

    // ---------- вид прошивки ----------
    private fun refresh() {
        val out = ArrayList<VE>(entries.size + added.size)
        for (e in entries) {
            if (e.isDir || deleted.contains(e.name)) continue
            val rep = replaced[e.name]
            out.add(VE(renamed[e.name] ?: e.name, rep?.length() ?: e.size, e.name, rep))
        }
        for ((n, f) in added) out.add(VE(n, f.length(), null, f))
        view = out
        val m = HashMap<String, VE>()
        for (v in out) m[v.name] = v
        viewMap = m
        if (screenName == "editor") {
            renderCrumbs()
            renderList()
            renderBottom()
            chip?.text = "Правок: " + changes()
        }
    }

    private fun showEditor() {
        screenName = "editor"
        query = ""
        root.removeAllViews()
        val l = LinearLayout(this)
        l.orientation = LinearLayout.VERTICAL
        l.setBackgroundColor(bg)
        l.setPadding(dp(16), dp(12), dp(16), dp(12))

        val top = LinearLayout(this)
        top.orientation = LinearLayout.HORIZONTAL
        top.gravity = Gravity.CENTER_VERTICAL
        val back = tv("←", 28f, ink, false)
        back.setPadding(dp(8), dp(6), dp(14), dp(6))
        back.setOnClickListener { goBack() }
        top.addView(back)
        val names = LinearLayout(this)
        names.orientation = LinearLayout.VERTICAL
        val nm = tv(zipTitle, 17f, ink, true)
        nm.maxLines = 1
        nm.ellipsize = TextUtils.TruncateAt.MIDDLE
        names.addView(nm)
        names.addView(tv("Файлов: " + entries.count { !it.isDir }, 12f, grey, false))
        top.addView(names, LinearLayout.LayoutParams(0, WRAP, 1f))
        val ch = tv("Правок: 0", 13f, primary, true)
        ch.setPadding(dp(14), dp(8), dp(14), dp(8))
        ch.background = rounded(pc, 18)
        ch.setOnClickListener { showChanges() }
        top.addView(ch)
        chip = ch
        l.addView(top, lp(MATCH, WRAP, 0, 10))

        val search = EditText(this)
        search.hint = "Поиск по имени"
        search.textSize = 15f
        search.setSingleLine()
        search.background = rounded(white, 22)
        search.setPadding(dp(18), dp(12), dp(18), dp(12))
        search.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, st: Int, c: Int, a: Int) {}
            override fun onTextChanged(s: CharSequence?, st: Int, b: Int, c: Int) {}
            override fun afterTextChanged(s: Editable?) {
                query = s.toString().trim()
                renderCrumbs()
                renderList()
            }
        })
        l.addView(search, lp(MATCH, WRAP, 0, 8))

        val hs = HorizontalScrollView(this)
        hs.isHorizontalScrollBarEnabled = false
        val cr = LinearLayout(this)
        cr.orientation = LinearLayout.HORIZONTAL
        cr.gravity = Gravity.CENTER_VERTICAL
        hs.addView(cr)
        crumbs = cr
        l.addView(hs, lp(MATCH, WRAP, 0, 6))

        val sv = ScrollView(this)
        sv.isVerticalScrollBarEnabled = false
        val box = LinearLayout(this)
        box.orientation = LinearLayout.VERTICAL
        box.setPadding(0, 0, 0, dp(8))
        sv.addView(box, FrameLayout.LayoutParams(MATCH, WRAP))
        listBox = box
        l.addView(sv, LinearLayout.LayoutParams(MATCH, 0, 1f))

        val bt = LinearLayout(this)
        bt.orientation = LinearLayout.HORIZONTAL
        bt.gravity = Gravity.CENTER_VERTICAL
        bottom = bt
        l.addView(bt, lp(MATCH, WRAP, 8))

        root.addView(l, FrameLayout.LayoutParams(MATCH, MATCH))
        refresh()
    }

    private fun renderCrumbs() {
        val cr = crumbs ?: return
        cr.removeAllViews()
        if (query.isNotEmpty()) {
            cr.addView(tv("Результаты поиска", 13f, grey, true))
            return
        }
        fun add(label: String, target: String) {
            val t = tv(label, 14f, primary, true)
            t.setPadding(dp(6), dp(6), dp(6), dp(6))
            t.setOnClickListener {
                path = target
                selected.clear()
                refresh()
            }
            cr.addView(t)
        }
        add("🏠", "")
        var acc = ""
        for (seg in path.split("/").filter { it.isNotEmpty() }) {
            cr.addView(tv("›", 14f, grey, false))
            acc += "$seg/"
            add(seg, acc)
        }
    }

    private fun renderList() {
        val box = listBox ?: return
        box.removeAllViews()
        class Row(val name: String, val label: String, val sub: String, val isFolder: Boolean)
        val rows = mutableListOf<Row>()
        if (query.isNotEmpty()) {
            val q = query.lowercase()
            for (v in view) {
                if (v.name.lowercase().contains(q)) {
                    rows.add(Row(v.name, v.name.substringAfterLast('/'),
                        sizeText(v.size) + "  •  " + v.name.substringBeforeLast('/', "/"), false))
                    if (rows.size >= 300) break
                }
            }
        } else {
            val folders = LinkedHashMap<String, LongArray>()
            val files = mutableListOf<VE>()
            for (v in view) {
                if (!v.name.startsWith(path)) continue
                val rest = v.name.substring(path.length)
                if (rest.isEmpty()) continue
                val i = rest.indexOf('/')
                if (i >= 0) {
                    val f = folders.getOrPut(rest.substring(0, i)) { LongArray(2) }
                    f[0]++
                    f[1] += v.size
                } else files.add(v)
            }
            for (k in folders.keys.sorted()) {
                val f = folders[k]!!
                rows.add(Row(path + k + "/", k, "Файлов: " + f[0] + "  •  " + sizeText(f[1]), true))
            }
            for (v in files.sortedBy { it.name.lowercase() }) {
                rows.add(Row(v.name, v.name.substringAfterLast('/'), sizeText(v.size), false))
            }
        }
        if (rows.isEmpty()) {
            val e = tv(if (query.isNotEmpty()) "Ничего не найдено" else "Здесь пусто", 15f, grey, false)
            e.gravity = Gravity.CENTER
            e.setPadding(0, dp(60), 0, 0)
            box.addView(e, lp(MATCH, WRAP))
            return
        }
        for ((i, r) in rows.withIndex()) {
            if (i >= 400) {
                box.addView(tv("Показаны первые 400. Уточните поиск.", 12f, grey, false), lp(WRAP, WRAP, 8))
                break
            }
            val on = selected.contains(r.name)
            val row = LinearLayout(this)
            row.orientation = LinearLayout.HORIZONTAL
            row.gravity = Gravity.CENTER_VERTICAL
            row.setPadding(dp(14), dp(12), dp(14), dp(12))
            row.background = rounded(if (on) pc else white, 20)
            val ic = tv(if (r.isFolder) "📁" else icon(r.name), 22f, ink, false)
            ic.gravity = Gravity.CENTER
            row.addView(ic, LinearLayout.LayoutParams(dp(44), dp(44)))
            val mid = LinearLayout(this)
            mid.orientation = LinearLayout.VERTICAL
            val n = tv(r.label, 15f, ink, true)
            n.maxLines = 1
            n.ellipsize = TextUtils.TruncateAt.MIDDLE
            mid.addView(n)
            val s = tv(r.sub, 12f, grey, false)
            s.maxLines = 1
            s.ellipsize = TextUtils.TruncateAt.MIDDLE
            mid.addView(s, lp(WRAP, WRAP, 1))
            val ml = LinearLayout.LayoutParams(0, WRAP, 1f)
            ml.leftMargin = dp(10)
            row.addView(mid, ml)
            if (on) row.addView(tv("✓", 20f, primary, true))
            row.setOnClickListener {
                if (selected.isNotEmpty()) toggle(r.name)
                else if (r.isFolder) {
                    path = r.name
                    refresh()
                } else viewMap[r.name]?.let { fileMenu(it) }
            }
            row.setOnLongClickListener {
                toggle(r.name)
                true
            }
            box.addView(row, lp(MATCH, WRAP, 0, 6))
        }
    }

    private fun toggle(n: String) {
        if (!selected.remove(n)) selected.add(n)
        renderList()
        renderBottom()
    }

    private fun renderBottom() {
        val bt = bottom ?: return
        bt.removeAllViews()
        fun add(v: View, weight: Float = 0f) {
            val p = LinearLayout.LayoutParams(if (weight > 0) 0 else WRAP, WRAP, weight)
            p.rightMargin = dp(8)
            bt.addView(v, p)
        }
        if (selected.isEmpty()) {
            val a = pill("+ Файл", false)
            a.setOnClickListener {
                val i = Intent(Intent.ACTION_OPEN_DOCUMENT)
                i.addCategory(Intent.CATEGORY_OPENABLE)
                i.type = "*/*"
                i.putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true)
                startActivityForResult(i, 2)
            }
            add(a)
            val s = pill("Сохранить ZIP", true)
            s.setOnClickListener {
                val i = Intent(Intent.ACTION_CREATE_DOCUMENT)
                i.addCategory(Intent.CATEGORY_OPENABLE)
                i.type = "application/zip"
                i.putExtra(Intent.EXTRA_TITLE, zipTitle.removeSuffix(".zip") + "_edited.zip")
                startActivityForResult(i, 4)
            }
            add(s, 1f)
        } else {
            val c = tv("Выбрано: " + selected.size, 14f, ink, true)
            add(c, 1f)
            val d = pill("Удалить", true, danger)
            d.setOnClickListener { confirmDelete(selected.toList()) }
            add(d)
            if (selected.size == 1) {
                val r = pill("Имя", false)
                r.setOnClickListener { renameDialog(selected.first()) }
                add(r)
            }
            val x = pill("✕", false)
            x.setOnClickListener {
                selected.clear()
                renderList()
                renderBottom()
            }
            add(x)
        }
    }

    // ---------- действия над файлами ----------
    private fun fileMenu(v: VE) {
        val ops = mutableListOf<String>()
        val l = v.name.lowercase()
        val isImg = l.endsWith(".png") || l.endsWith(".jpg") || l.endsWith(".jpeg") || l.endsWith(".webp") || l.endsWith(".bmp")
        if (isImg) ops.add("Просмотр")
        if (v.size <= 524288) ops.add("Открыть как текст")
        ops.add("Извлечь на устройство")
        ops.add("Переименовать")
        ops.add("Удалить")
        ops.add("Свойства")
        AlertDialog.Builder(this).setTitle(v.name.substringAfterLast('/'))
            .setItems(ops.toTypedArray()) { _, i ->
                when (ops[i]) {
                    "Просмотр" -> previewImage(v)
                    "Открыть как текст" -> openText(v)
                    "Извлечь на устройство" -> {
                        pendingExtract = v
                        val it = Intent(Intent.ACTION_CREATE_DOCUMENT)
                        it.addCategory(Intent.CATEGORY_OPENABLE)
                        it.type = "*/*"
                        it.putExtra(Intent.EXTRA_TITLE, v.name.substringAfterLast('/'))
                        startActivityForResult(it, 3)
                    }
                    "Переименовать" -> renameDialog(v.name)
                    "Удалить" -> confirmDelete(listOf(v.name))
                    "Свойства" -> AlertDialog.Builder(this).setTitle("Свойства")
                        .setMessage("Путь: " + v.name + "\nРазмер: " + sizeText(v.size) +
                            (if (v.src == null) "\nДобавлен вами" else if (v.file != null) "\nИзменён вами" else "") +
                            (if (isProtected(v.name)) "\n\nФайл нужен для установки прошивки" else ""))
                        .setPositiveButton("Закрыть", null).show()
                }
            }.show()
    }

    private fun stream(v: VE): InputStream {
        val f = v.file
        if (f != null) return FileInputStream(f)
        val z = zip ?: throw Exception("архив закрыт")
        val e = z.getEntry(v.src) ?: throw Exception("запись не найдена")
        return z.getInputStream(e)
    }

    private fun extractTo(uri: Uri) {
        val v = pendingExtract ?: return
        busy("Извлекаем…")
        Thread {
            var err = ""
            try {
                val o = contentResolver.openOutputStream(uri, "wt") ?: throw Exception("не удалось записать")
                o.use { out -> stream(v).use { it.copyTo(out) } }
            } catch (e: Exception) {
                err = "Ошибка: " + (e.message ?: "")
            }
            runOnUiThread {
                idle()
                toast(if (err.isEmpty()) "Файл сохранён" else err)
            }
        }.start()
    }

    private fun previewImage(v: VE) {
        try {
            val bytes = stream(v).use { it.readBytes() }
            val opt = BitmapFactory.Options()
            opt.inJustDecodeBounds = true
            BitmapFactory.decodeByteArray(bytes, 0, bytes.size, opt)
            var s = 1
            while (opt.outWidth / s > 1600 || opt.outHeight / s > 1600) s *= 2
            val o2 = BitmapFactory.Options()
            o2.inSampleSize = s
            val b: Bitmap? = BitmapFactory.decodeByteArray(bytes, 0, bytes.size, o2)
            if (b == null) {
                toast("Не удалось показать картинку")
                return
            }
            val iv = ImageView(this)
            iv.setImageBitmap(b)
            iv.adjustViewBounds = true
            iv.setPadding(dp(12), dp(12), dp(12), dp(12))
            AlertDialog.Builder(this).setTitle(v.name.substringAfterLast('/') + "  " + b.width + "×" + b.height)
                .setView(iv).setPositiveButton("Закрыть", null).show()
        } catch (e: Exception) {
            toast("Ошибка: " + (e.message ?: ""))
        }
    }

    private fun confirmDelete(names: List<String>) {
        val prot = names.any { isProtected(it) || (it.endsWith("/") && view.any { v -> v.name.startsWith(it) && isProtected(v.name) }) }
        val msg = StringBuilder("Удалить выбранное (" + names.size + ")?")
        if (prot) msg.append("\n\nСреди них есть файлы, нужные для установки (META-INF, образы, updater-script, build.prop). Без них прошивка может не установиться.")
        msg.append("\n\nФайл на телефоне не удаляется: правка попадёт только в новый ZIP, её можно отменить.")
        AlertDialog.Builder(this).setTitle("Удаление").setMessage(msg.toString())
            .setPositiveButton("Удалить") { _, _ ->
                for (n in names) deleteName(n)
                selected.clear()
                refresh()
            }.setNegativeButton("Отмена", null).show()
    }

    private fun deleteName(n: String) {
        if (n.endsWith("/")) {
            for (e in entries) {
                if ((renamed[e.name] ?: e.name).startsWith(n)) deleted.add(e.name)
            }
            val it = added.entries.iterator()
            while (it.hasNext()) {
                val x = it.next()
                if (x.key.startsWith(n)) {
                    x.value.delete()
                    it.remove()
                }
            }
        } else {
            val v = viewMap[n] ?: return
            if (v.src != null) {
                deleted.add(v.src)
                replaced.remove(v.src)?.delete()
            } else {
                added.remove(n)?.delete()
            }
        }
    }

    private fun renameDialog(name: String) {
        val isF = name.endsWith("/")
        val clean = name.removeSuffix("/")
        val parent = clean.substringBeforeLast('/', "").let { if (it.isEmpty()) "" else "$it/" }
        val e = EditText(this)
        e.setText(clean.substringAfterLast('/'))
        e.setSingleLine()
        e.setPadding(dp(24), dp(16), dp(24), dp(16))
        AlertDialog.Builder(this).setTitle("Новое имя").setView(e)
            .setPositiveButton("Готово") { _, _ ->
                val nn = e.text.toString().trim().replace("/", "")
                if (nn.isEmpty()) return@setPositiveButton
                if (isF) {
                    val np = "$parent$nn/"
                    if (view.any { it.name.startsWith(np) }) {
                        toast("Папка с таким именем уже есть")
                        return@setPositiveButton
                    }
                    for (v in view.filter { it.name.startsWith(name) }) moveTo(v, np + v.name.substring(name.length))
                } else {
                    val full = parent + nn
                    if (viewMap.containsKey(full)) {
                        toast("Файл с таким именем уже есть")
                        return@setPositiveButton
                    }
                    viewMap[name]?.let { moveTo(it, full) }
                }
                selected.clear()
                refresh()
            }.setNegativeButton("Отмена", null).show()
    }

    private fun moveTo(v: VE, newName: String) {
        if (v.src != null) renamed[v.src] = newName
        else {
            val f = added.remove(v.name)
            if (f != null) added[newName] = f
        }
    }

    // ---------- текстовый редактор ----------
    private fun openText(v: VE) {
        try {
            val bytes = stream(v).use { it.readBytes() }
            if (bytes.any { it.toInt() == 0 }) {
                toast("Это не текстовый файл")
                return
            }
            val text = String(bytes, Charsets.UTF_8)
            editVE = v
            editOriginal = text
            screenName = "text"
            root.removeAllViews()
            val l = LinearLayout(this)
            l.orientation = LinearLayout.VERTICAL
            l.setBackgroundColor(bg)
            l.setPadding(dp(16), dp(12), dp(16), dp(12))
            val top = LinearLayout(this)
            top.orientation = LinearLayout.HORIZONTAL
            top.gravity = Gravity.CENTER_VERTICAL
            val back = tv("←", 28f, ink, false)
            back.setPadding(dp(8), dp(6), dp(14), dp(6))
            back.setOnClickListener { closeText() }
            top.addView(back)
            val nm = tv(v.name.substringAfterLast('/'), 17f, ink, true)
            nm.maxLines = 1
            nm.ellipsize = TextUtils.TruncateAt.MIDDLE
            top.addView(nm, LinearLayout.LayoutParams(0, WRAP, 1f))
            val sv = pill("Сохранить", true)
            sv.setOnClickListener { saveText() }
            top.addView(sv)
            l.addView(top, lp(MATCH, WRAP, 0, 10))
            val e = EditText(this)
            e.setText(text)
            e.typeface = Typeface.MONOSPACE
            e.textSize = 13f
            e.setTextColor(ink)
            e.gravity = Gravity.TOP
            e.inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
            e.background = rounded(white, 20)
            e.setPadding(dp(14), dp(14), dp(14), dp(14))
            l.addView(e, LinearLayout.LayoutParams(MATCH, 0, 1f))
            editBox = e
            root.addView(l, FrameLayout.LayoutParams(MATCH, MATCH))
        } catch (ex: Exception) {
            toast("Ошибка: " + (ex.message ?: ""))
        }
    }

    private fun saveText() {
        val v = editVE ?: return
        val txt = editBox?.text?.toString() ?: return
        try {
            val bytes = txt.toByteArray(Charsets.UTF_8)
            if (v.src == null) {
                val f = added[v.name] ?: File(workDir(), "add_" + System.currentTimeMillis())
                f.writeBytes(bytes)
                added[v.name] = f
            } else {
                val f = replaced[v.src] ?: File(workDir(), "rep_" + System.currentTimeMillis())
                f.writeBytes(bytes)
                replaced[v.src] = f
            }
            editOriginal = txt
            toast("Правка сохранена в проекте")
            screenName = "editor"
            showEditor()
        } catch (e: Exception) {
            toast("Ошибка: " + (e.message ?: ""))
        }
    }

    private fun closeText() {
        val now = editBox?.text?.toString() ?: ""
        if (now != editOriginal) {
            AlertDialog.Builder(this).setMessage("Выйти без сохранения правок?")
                .setPositiveButton("Выйти") { _, _ -> showEditor() }
                .setNegativeButton("Остаться", null).show()
        } else showEditor()
    }

    // ---------- правки и сохранение ----------
    private fun showChanges() {
        val m = "Удалено: " + deleted.size + "\nПереименовано: " + renamed.size +
            "\nИзменено: " + replaced.size + "\nДобавлено: " + added.size
        val b = AlertDialog.Builder(this).setTitle("Правки").setMessage(m).setPositiveButton("Закрыть", null)
        if (changes() > 0) {
            b.setNegativeButton("Отменить все") { _, _ ->
                deleted.clear()
                renamed.clear()
                for (f in replaced.values) f.delete()
                for (f in added.values) f.delete()
                replaced.clear()
                added.clear()
                selected.clear()
                refresh()
            }
        }
        b.show()
    }

    private fun saveZip(uri: Uri) {
        busy("Сохраняем ZIP…")
        Thread {
            var err = ""
            var size = 0L
            try {
                val z = zip ?: throw Exception("архив закрыт")
                val o = contentResolver.openOutputStream(uri, "wt") ?: throw Exception("не удалось записать")
                var count = 0
                val total = entries.size + added.size
                ZipOutputStream(BufferedOutputStream(o, 1 shl 16)).use { zo ->
                    val en = z.entries()
                    while (en.hasMoreElements()) {
                        val e = en.nextElement()
                        count++
                        if (count % 20 == 0) busyMsg("Сохраняем ZIP…\n$count из $total")
                        if (deleted.contains(e.name)) continue
                        val ne = ZipEntry(renamed[e.name] ?: e.name)
                        val rep = replaced[e.name]
                        if (rep != null) {
                            ne.method = ZipEntry.DEFLATED
                            zo.putNextEntry(ne)
                            FileInputStream(rep).use { it.copyTo(zo) }
                        } else if (e.isDirectory) {
                            ne.time = e.time
                            zo.putNextEntry(ne)
                        } else if (e.method == ZipEntry.STORED) {
                            ne.method = ZipEntry.STORED
                            ne.size = e.size
                            ne.compressedSize = e.size
                            ne.crc = e.crc
                            ne.time = e.time
                            zo.putNextEntry(ne)
                            z.getInputStream(e).use { it.copyTo(zo) }
                        } else {
                            ne.time = e.time
                            zo.putNextEntry(ne)
                            z.getInputStream(e).use { it.copyTo(zo) }
                        }
                        zo.closeEntry()
                    }
                    for ((n, f) in added) {
                        val ne = ZipEntry(n)
                        ne.method = ZipEntry.DEFLATED
                        zo.putNextEntry(ne)
                        FileInputStream(f).use { it.copyTo(zo) }
                        zo.closeEntry()
                    }
                }
            } catch (e: Exception) {
                err = "Ошибка сохранения: " + (e.message ?: "")
            }
            runOnUiThread {
                idle()
                if (err.isNotEmpty()) toast(err)
                else AlertDialog.Builder(this).setTitle("Готово")
                    .setMessage("Новый ZIP сохранён. Подпись архива сброшена: ставьте через TWRP или другой recovery без проверки подписи.")
                    .setPositiveButton("Ок", null).show()
            }
        }.start()
    }

    // ---------- назад ----------
    private fun goBack() {
        when {
            selected.isNotEmpty() -> {
                selected.clear()
                refresh()
            }
            query.isNotEmpty() -> showEditor()
            path.isNotEmpty() -> {
                path = path.removeSuffix("/").substringBeforeLast('/', "").let { if (it.isEmpty()) "" else "$it/" }
                refresh()
            }
            changes() > 0 -> AlertDialog.Builder(this).setMessage("Закрыть прошивку? Несохранённые правки пропадут.")
                .setPositiveButton("Закрыть") { _, _ -> showHome() }
                .setNegativeButton("Остаться", null).show()
            else -> showHome()
        }
    }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        when (screenName) {
            "editor" -> goBack()
            "text" -> closeText()
            else -> super.onBackPressed()
        }
    }
}
