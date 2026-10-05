package com.bazuka.vozcomida

import android.Manifest
import android.app.Activity
import android.app.AlertDialog
import android.app.TimePickerDialog
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.view.Gravity
import android.view.View
import android.text.InputType
import android.view.inputmethod.EditorInfo
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Spinner
import android.widget.TextView
import android.widget.Toast
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

class MainActivity : Activity() {

    private val green = Color.parseColor("#2E7D32")
    private val red = Color.parseColor("#C62828")

    private lateinit var db: Db
    private val day: Calendar = Calendar.getInstance()

    private lateinit var dateText: TextView
    private lateinit var totalText: TextView
    private lateinit var listBox: LinearLayout
    private lateinit var statusText: TextView
    private lateinit var micButton: Button
    private lateinit var input: EditText
    private lateinit var timeChip: TextView
    private var manualTime: Pair<Int, Int>? = null

    private var recognizer: SpeechRecognizer? = null
    private var listening = false

    private val dateFmt = SimpleDateFormat("EEEE d 'de' MMMM", Locale("es", "ES"))
    private val timeFmt = SimpleDateFormat("HH:mm", Locale.getDefault())

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        db = Db(this)
        buildUi()
        refresh()
    }

    override fun onDestroy() {
        recognizer?.destroy()
        super.onDestroy()
    }

    // ---------- UI ----------

    private fun buildUi() {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.parseColor("#F5F5F5"))
        }

        // Cabecera con navegación por día
        val header = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(green)
            setPadding(dp(16), dp(36), dp(16), dp(12))
        }
        val nav = LinearLayout(this).apply { gravity = Gravity.CENTER_VERTICAL }
        val prev = navButton("◀") { day.add(Calendar.DAY_OF_YEAR, -1); refresh() }
        val next = navButton("▶") { day.add(Calendar.DAY_OF_YEAR, 1); refresh() }
        dateText = TextView(this).apply {
            setTextColor(Color.WHITE); textSize = 18f; gravity = Gravity.CENTER
            typeface = Typeface.DEFAULT_BOLD
            setOnClickListener { day.timeInMillis = System.currentTimeMillis(); refresh() }
        }
        nav.addView(prev)
        nav.addView(dateText, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        nav.addView(next)
        totalText = TextView(this).apply {
            setTextColor(Color.WHITE); textSize = 14f; gravity = Gravity.CENTER
        }
        val export = TextView(this).apply {
            text = "Exportar CSV"; setTextColor(Color.WHITE); textSize = 13f
            gravity = Gravity.CENTER; setPadding(0, dp(6), 0, 0)
            setOnClickListener { exportCsv() }
        }
        header.addView(nav)
        header.addView(totalText)
        header.addView(export)
        root.addView(header)

        // Lista del día
        listBox = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(12), dp(8), dp(12), dp(8))
        }
        val scroll = ScrollView(this).apply { addView(listBox) }
        root.addView(scroll, LinearLayout.LayoutParams(-1, 0, 1f))

        // Zona inferior: estado, micrófono y texto manual
        val bottom = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            setBackgroundColor(Color.WHITE)
            setPadding(dp(12), dp(10), dp(12), dp(14))
            elevation = dp(8).toFloat()
        }
        statusText = TextView(this).apply {
            text = "Toca el micrófono y di, por ejemplo:\n\"Desayuno a las 8 y media dos huevos y una tostada\"\nToca un registro para editarlo."
            gravity = Gravity.CENTER; textSize = 14f; setTextColor(Color.DKGRAY)
        }
        micButton = Button(this).apply {
            text = "🎤"; textSize = 32f
            setTextColor(Color.WHITE)
            background = circle(green)
            setOnClickListener { toggleListening() }
        }
        input = EditText(this).apply {
            hint = "…o escribe aquí y pulsa Enter"
            setSingleLine(true)
            imeOptions = EditorInfo.IME_ACTION_DONE
            setOnEditorActionListener { _, _, _ ->
                val t = text.toString()
                if (t.isNotBlank()) { handleText(t); setText("") }
                true
            }
        }
        bottom.addView(statusText, LinearLayout.LayoutParams(-1, -2))
        bottom.addView(micButton, LinearLayout.LayoutParams(dp(88), dp(88)).apply { topMargin = dp(8) })
        timeChip = TextView(this).apply {
            textSize = 14f; setTextColor(green); gravity = Gravity.CENTER
            setPadding(dp(12), dp(8), dp(12), dp(8))
            setOnClickListener { pickManualTime() }
            setOnLongClickListener { manualTime = null; updateTimeChip(); true }
        }
        updateTimeChip()
        bottom.addView(timeChip, LinearLayout.LayoutParams(-2, -2))
        bottom.addView(input, LinearLayout.LayoutParams(-1, -2))
        root.addView(bottom, LinearLayout.LayoutParams(-1, -2))

        setContentView(root)
    }

    private fun navButton(label: String, onClick: () -> Unit) = TextView(this).apply {
        text = label; setTextColor(Color.WHITE); textSize = 22f
        setPadding(dp(16), dp(4), dp(16), dp(4))
        setOnClickListener { onClick() }
    }

    private fun circle(color: Int) = GradientDrawable().apply {
        shape = GradientDrawable.OVAL; setColor(color)
    }

    private fun refresh() {
        val entries = db.forDay(day)
        val today = Calendar.getInstance()
        val isToday = today.get(Calendar.YEAR) == day.get(Calendar.YEAR) &&
            today.get(Calendar.DAY_OF_YEAR) == day.get(Calendar.DAY_OF_YEAR)
        dateText.text = (if (isToday) "Hoy · " else "") +
            dateFmt.format(day.time).replaceFirstChar { it.uppercase() }

        val kcal = entries.sumOf { it.kcal ?: 0 }
        totalText.text = "${entries.size} alimentos" + if (kcal > 0) " · $kcal kcal" else ""

        listBox.removeAllViews()
        if (entries.isEmpty()) {
            listBox.addView(TextView(this).apply {
                text = "Sin registros para este día"; gravity = Gravity.CENTER
                setPadding(0, dp(40), 0, 0); setTextColor(Color.GRAY)
            })
            return
        }
        for (meal in Meals.ALL) {
            val group = entries.filter { it.meal == meal }
            if (group.isEmpty()) continue
            val sub = group.sumOf { it.kcal ?: 0 }
            listBox.addView(TextView(this).apply {
                text = meal + if (sub > 0) "  ($sub kcal)" else ""
                textSize = 16f; typeface = Typeface.DEFAULT_BOLD; setTextColor(green)
                setPadding(dp(4), dp(14), 0, dp(4))
            })
            for (e in group) listBox.addView(entryView(e))
        }
    }

    private fun entryView(e: Entry): View {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
            setBackgroundColor(Color.WHITE); setPadding(dp(12), dp(10), dp(12), dp(10))
        }
        val label = TextView(this).apply {
            text = "${e.qty} × ${e.name}"; textSize = 16f; setTextColor(Color.BLACK)
        }
        val meta = TextView(this).apply {
            text = timeFmt.format(Date(e.ts)) + (e.kcal?.let { " · $it kcal" } ?: "")
            textSize = 12f; setTextColor(Color.GRAY)
        }
        val col = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            addView(label); addView(meta)
        }
        val del = TextView(this).apply {
            text = "✕"; textSize = 20f; setTextColor(red); setPadding(dp(12), 0, 0, 0)
            setOnClickListener {
                AlertDialog.Builder(this@MainActivity)
                    .setMessage("¿Borrar \"${e.name}\"?")
                    .setPositiveButton("Borrar") { _, _ -> db.delete(e.id); refresh() }
                    .setNegativeButton("Cancelar", null).show()
            }
        }
        row.addView(col, LinearLayout.LayoutParams(0, -2, 1f))
        row.addView(del)
        col.setOnClickListener { showEdit(e) }
        return LinearLayout(this).apply {
            setPadding(0, dp(2), 0, dp(2)); addView(row, LinearLayout.LayoutParams(-1, -2))
        }
    }

    // ---------- Voz ----------

    private fun toggleListening() {
        if (listening) { recognizer?.stopListening(); return }
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(arrayOf(Manifest.permission.RECORD_AUDIO), 1)
            return
        }
        if (!SpeechRecognizer.isRecognitionAvailable(this)) {
            status("Este dispositivo no tiene reconocimiento de voz. Escribe el texto abajo " +
                "(o usa el micrófono del teclado).")
            return
        }
        startListening()
    }

    override fun onRequestPermissionsResult(code: Int, perms: Array<out String>, results: IntArray) {
        super.onRequestPermissionsResult(code, perms, results)
        if (results.firstOrNull() == PackageManager.PERMISSION_GRANTED) startListening()
        else status("Se necesita permiso de micrófono para usar la voz.")
    }

    private fun startListening() {
        recognizer?.destroy()
        recognizer = SpeechRecognizer.createSpeechRecognizer(this).apply {
            setRecognitionListener(object : RecognitionListener {
                override fun onReadyForSpeech(params: Bundle?) { status("Escuchando…") }
                override fun onBeginningOfSpeech() {}
                override fun onRmsChanged(rmsdB: Float) {}
                override fun onBufferReceived(buffer: ByteArray?) {}
                override fun onEndOfSpeech() { setListening(false) }
                override fun onEvent(eventType: Int, params: Bundle?) {}
                override fun onPartialResults(partial: Bundle?) {
                    partial?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                        ?.firstOrNull()?.let { status("“$it”") }
                }
                override fun onResults(results: Bundle?) {
                    setListening(false)
                    val text = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                        ?.firstOrNull()
                    if (text.isNullOrBlank()) status("No te entendí, intenta de nuevo.")
                    else handleText(text)
                }
                override fun onError(error: Int) {
                    setListening(false)
                    status(
                        when (error) {
                            SpeechRecognizer.ERROR_NO_MATCH, SpeechRecognizer.ERROR_SPEECH_TIMEOUT ->
                                "No te escuché. Toca el micrófono e inténtalo otra vez."
                            SpeechRecognizer.ERROR_NETWORK, SpeechRecognizer.ERROR_NETWORK_TIMEOUT ->
                                "Sin conexión: el reconocimiento de voz necesita internet."
                            else -> "Error de reconocimiento de voz ($error)."
                        }
                    )
                }
            })
        }
        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, "es-ES")
            putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
            putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1)
        }
        setListening(true)
        recognizer?.startListening(intent)
    }

    private fun setListening(on: Boolean) {
        listening = on
        micButton.background = circle(if (on) red else green)
        micButton.text = if (on) "⏹" else "🎤"
    }

    private fun status(msg: String) { statusText.text = msg }

    // ---------- Lógica ----------

    private fun handleText(text: String) {
        if (Parser.isUndo(text)) {
            status(if (db.deleteLast()) "Último registro borrado." else "No hay registros para borrar.")
            refresh()
            return
        }
        val parsed = Parser.parse(text)
        if (parsed == null) {
            status("No pude interpretar: “$text”")
            return
        }
        // Se guarda en el día que se está viendo, con la hora actual.
        val cal = Calendar.getInstance().apply {
            set(Calendar.YEAR, day.get(Calendar.YEAR))
            set(Calendar.DAY_OF_YEAR, day.get(Calendar.DAY_OF_YEAR))
            val t = if (parsed.hour != null) parsed.hour to parsed.minute else manualTime
            if (t != null) {
                set(Calendar.HOUR_OF_DAY, t.first); set(Calendar.MINUTE, t.second)
                set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0)
            }
        }
        manualTime = null
        updateTimeChip()
        parsed.items.forEachIndexed { i, item -> db.insert(cal.timeInMillis + i, parsed.meal, item) }
        status("✔ ${parsed.meal} ${timeFmt.format(cal.time)}: " + parsed.items.joinToString(", ") { "${it.qty} ${it.name}" } +
            "\n(di \"borrar último\" para deshacer)")
        refresh()
    }

    private fun updateTimeChip() {
        val t = manualTime
        timeChip.text = if (t == null) "🕒 Hora: ahora (toca para cambiar)"
        else "🕒 Hora: %02d:%02d (mantén pulsado para volver a ahora)".format(t.first, t.second)
    }

    private fun pickManualTime() {
        val now = Calendar.getInstance()
        val cur = manualTime ?: (now.get(Calendar.HOUR_OF_DAY) to now.get(Calendar.MINUTE))
        TimePickerDialog(this, { _, h, m -> manualTime = h to m; updateTimeChip() },
            cur.first, cur.second, true).show()
    }

    /** Editor de un registro: comida, hora, alimento, cantidad y calorías. */
    private fun showEdit(e: Entry) {
        val cal = Calendar.getInstance().apply { timeInMillis = e.ts }
        var hour = cal.get(Calendar.HOUR_OF_DAY)
        var minute = cal.get(Calendar.MINUTE)

        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(8), dp(20), 0)
        }
        val meal = Spinner(this).apply {
            adapter = ArrayAdapter(this@MainActivity, android.R.layout.simple_spinner_dropdown_item, Meals.ALL)
            setSelection(Meals.ALL.indexOf(e.meal).coerceAtLeast(0))
        }
        val timeBtn = Button(this)
        fun showTime() { timeBtn.text = "🕒 Hora: %02d:%02d".format(hour, minute) }
        showTime()
        timeBtn.setOnClickListener {
            TimePickerDialog(this, { _, h, m -> hour = h; minute = m; showTime() }, hour, minute, true).show()
        }
        val name = EditText(this).apply { hint = "Alimento"; setText(e.name) }
        val qty = EditText(this).apply { hint = "Cantidad"; setText(e.qty) }
        val kcal = EditText(this).apply {
            hint = "Calorías (opcional)"; inputType = InputType.TYPE_CLASS_NUMBER
            setText(e.kcal?.toString() ?: "")
        }
        listOf(meal, timeBtn, name, qty, kcal).forEach { box.addView(it, LinearLayout.LayoutParams(-1, -2)) }

        AlertDialog.Builder(this)
            .setTitle("Editar registro")
            .setView(box)
            .setPositiveButton("Guardar") { _, _ ->
                val n = name.text.toString().trim()
                if (n.isEmpty()) return@setPositiveButton
                cal.set(Calendar.HOUR_OF_DAY, hour); cal.set(Calendar.MINUTE, minute)
                db.update(Entry(
                    e.id, cal.timeInMillis, meal.selectedItem as String, n,
                    qty.text.toString().trim().ifEmpty { "1" }, kcal.text.toString().toIntOrNull()
                ))
                refresh()
            }
            .setNegativeButton("Cancelar", null)
            .show()
    }

    private fun exportCsv() {
        val all = db.all()
        if (all.isEmpty()) { Toast.makeText(this, "No hay datos", Toast.LENGTH_SHORT).show(); return }
        val fmt = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.US)
        val sb = StringBuilder("fecha,comida,alimento,cantidad,kcal\n")
        for (e in all) {
            sb.append(fmt.format(Date(e.ts))).append(',').append(e.meal).append(',')
                .append('"').append(e.name.replace("\"", "\"\"")).append('"').append(',')
                .append(e.qty).append(',').append(e.kcal ?: "").append('\n')
        }
        startActivity(Intent.createChooser(
            Intent(Intent.ACTION_SEND).apply {
                type = "text/plain"
                putExtra(Intent.EXTRA_SUBJECT, "Registro de alimentación")
                putExtra(Intent.EXTRA_TEXT, sb.toString())
            }, "Exportar"
        ))
    }
}
