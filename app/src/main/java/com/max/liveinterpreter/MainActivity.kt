package com.max.liveinterpreter

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.media.*
import android.os.Bundle
import android.speech.tts.TextToSpeech
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import kotlinx.coroutines.*
import org.json.JSONObject
import org.vosk.Model
import org.vosk.Recognizer
import java.io.File
import java.util.*
import kotlin.coroutines.resume
import kotlinx.coroutines.suspendCancellableCoroutine

class MainActivity : ComponentActivity() {
    companion object {
        private const val PREFS = "live_interpreter_prefs"
        private const val KEY_IN_ID = "input_device_id"
        private const val KEY_OUT_ID = "output_device_id"
    }
    private var tts: TextToSpeech? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        tts = TextToSpeech(this) { status ->
            if (status == TextToSpeech.SUCCESS) {
                tts?.language = Locale.ENGLISH // target spoken language
                val attrs = AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA) // route to A2DP
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                    .build()
                tts?.setAudioAttributes(attrs)
            }
        }
        setContent { AppScreen(tts!!) }
    }

    override fun onDestroy() {
        tts?.shutdown()
        super.onDestroy()
    }
}

@Composable
fun AppScreen(tts: TextToSpeech) {
    val ctx = androidx.compose.ui.platform.LocalContext.current
    var hasMic by remember { mutableStateOf(false) }
    var running by remember { mutableStateOf(false) }
    var status by remember { mutableStateOf("Idle") }

    val requestPermission = remember {
        (ctx as ComponentActivity).registerForActivityResult(
            ActivityResultContracts.RequestPermission()
        ) { granted -> hasMic = granted }
    }

    LaunchedEffect(Unit) {
        hasMic = ContextCompat.checkSelfPermission(ctx, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED
        if (!hasMic) requestPermission.launch(Manifest.permission.RECORD_AUDIO)
    }

    var selectedInput by remember { mutableStateOf<AudioDeviceInfo?>(null) }
    var selectedOutput by remember { mutableStateOf<AudioDeviceInfo?>(null) }

    val audioManager = ctx.getSystemService(Context.AUDIO_SERVICE) as AudioManager
    val inputDevices = audioManager.getDevices(AudioManager.GET_DEVICES_INPUTS)
    val outputDevices = audioManager.getDevices(AudioManager.GET_DEVICES_OUTPUTS)

    // Restore last selections
    LaunchedEffect(inputDevices, outputDevices) {
        val sp = ctx.getSharedPreferences(MainActivity.PREFS, Context.MODE_PRIVATE)
        val lastIn = sp.getInt(MainActivity.KEY_IN_ID, Int.MIN_VALUE)
        val lastOut = sp.getInt(MainActivity.KEY_OUT_ID, Int.MIN_VALUE)
        selectedInput = inputDevices.firstOrNull { it.id == lastIn } ?: inputDevices.firstOrNull()
        selectedOutput = outputDevices.firstOrNull { it.id == lastOut } ?: outputDevices.firstOrNull()
    }

    var job by remember { mutableStateOf<Job?>(null) }

    Scaffold { pad ->
        Column(
            modifier = Modifier.fillMaxSize().padding(pad).padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text("RU → EN Live Interpreter", style = MaterialTheme.typography.headlineSmall)
            Text("Status: $status")

            // Input picker
            Text("Input Mic:")
            DropdownMenuDemo(inputDevices.map { it.productName.toString() }) { idx ->
                selectedInput = inputDevices.getOrNull(idx)
                selectedInput?.let { dev ->
                    val sp = ctx.getSharedPreferences(MainActivity.PREFS, Context.MODE_PRIVATE)
                    sp.edit().putInt(MainActivity.KEY_IN_ID, dev.id).apply()
                }
            }

            // Output picker (display only; Android routes TTS via A2DP automatically when connected)
            Text("Output Device (connect BT first):")
            DropdownMenuDemo(outputDevices.map { it.productName.toString() }) { idx ->
                selectedOutput = outputDevices.getOrNull(idx)
                selectedOutput?.let { dev ->
                    val sp = ctx.getSharedPreferences(MainActivity.PREFS, Context.MODE_PRIVATE)
                    sp.edit().putInt(MainActivity.KEY_OUT_ID, dev.id).apply()
                }
            }

            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Button(enabled = hasMic && !running, onClick = {
                    running = true
                    status = "Starting…"
                    job = (ctx as ComponentActivity).lifecycleScope.launch(Dispatchers.Default) {
                        InterpreterEngine(
                            ctx,
                            inputDevice = selectedInput,
                            onFinal = { finalText ->
                                val translated = MLKitTranslator.translate(finalText, from = "ru", to = "en")
                                withContext(Dispatchers.Main) {
                                    status = "Heard: $finalText\n→ $translated"
                                    tts.speak(translated, TextToSpeech.QUEUE_FLUSH, null, "utt")
                                }
                            },
                            onStatus = { s -> withContext(Dispatchers.Main) { status = s } }
                        ).run()
                    }
                }) { Text("Start") }

                OutlinedButton(enabled = running, onClick = {
                    running = false
                    status = "Stopping…"
                    job?.cancel()
                    InterpreterEngine.stopNow()
                    status = "Stopped"
                }) { Text("Stop") }
            }

            Text(
                "Tips:\n1) Plug USB‑C mic/receiver before Start.\n" +
                "2) Pair Bluetooth earbuds before Start (TTS uses media route).\n" +
                "3) First run downloads ML Kit language models.\n" +
                "4) Place Vosk RU model in app files/vosk-model folder."
            )
        }
    }
}

@Composable
fun DropdownMenuDemo(items: List<String>, onSelect: (Int) -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    var selectedText by remember { mutableStateOf(if (items.isNotEmpty()) items[0] else "None") }

    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Button(onClick = { expanded = true }) { Text(selectedText) }
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            items.forEachIndexed { index, label ->
                DropdownMenuItem(onClick = {
                    selectedText = label
                    expanded = false
                    onSelect(index)
                }, text = { Text(label) })
            }
        }
    }
}

object MLKitTranslator {
    suspend fun translate(text: String, from: String, to: String): String = withContext(Dispatchers.IO) {
        val src = lang(from)
        val tgt = lang(to)
        val opts = com.google.mlkit.nl.translate.TranslatorOptions.Builder()
            .setSourceLanguage(src)
            .setTargetLanguage(tgt)
            .build()
        val client = com.google.mlkit.nl.translate.Translation.getClient(opts)
        try {
            client.downloadModelIfNeeded().await()
            client.translate(text).await()
        } finally { client.close() }
    }

    private fun lang(code: String): String = when (code.lowercase(Locale.US)) {
        "ru" -> com.google.mlkit.nl.translate.TranslateLanguage.RUSSIAN
        "en" -> com.google.mlkit.nl.translate.TranslateLanguage.ENGLISH
        else -> com.google.mlkit.nl.translate.TranslateLanguage.ENGLISH
    }
}

suspend fun <T> com.google.android.gms.tasks.Task<T>.await(): T = suspendCancellableCoroutine { cont ->
    addOnSuccessListener { r -> if (cont.isActive) cont.resume(r) }
    addOnFailureListener { e -> if (cont.isActive) cont.resumeWithException(e) }
}

class InterpreterEngine(
    private val ctx: Context,
    private val inputDevice: AudioDeviceInfo?,
    private val onFinal: (String) -> Unit,
    private val onStatus: (String) -> Unit
) {
    companion object {
        @Volatile private var running = true
        fun stopNow() { running = false }
    }

    suspend fun run() = withContext(Dispatchers.Default) {
        running = true
        onStatus("Initializing mic…")
        val sampleRate = 16000
        val minBuf = AudioRecord.getMinBufferSize(sampleRate, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
        val record = AudioRecord.Builder()
            .setAudioSource(MediaRecorder.AudioSource.VOICE_RECOGNITION)
            .setAudioFormat(
                AudioFormat.Builder()
                    .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                    .setSampleRate(sampleRate)
                    .setChannelMask(AudioFormat.CHANNEL_IN_MONO)
                    .build()
            ).build()
        inputDevice?.let { record.preferredDevice = it }

        val modelPath = File(ctx.filesDir, "vosk-model").absolutePath
        if (!File(modelPath).exists()) {
            onStatus("Missing Vosk model folder.")
            return@withContext
        }
        val model = Model(modelPath)
        val recognizer = Recognizer(model, 16000.0f)

        val buf = ShortArray(2048)
        record.startRecording()
        onStatus("Listening…")

        while (running) {
            val n = record.read(buf, 0, buf.size)
            if (n > 0) {
                val ok = recognizer.acceptWaveForm(buf, n)
                if (ok) {
                    val res = recognizer.result
                    val text = JSONObject(res).optString("text")
                    if (text.isNotBlank()) onFinal(text)
                } else {
                    // partials are available if needed
                }
            }
        }
        record.stop(); record.release()
        onStatus("Stopped")
    }
}
