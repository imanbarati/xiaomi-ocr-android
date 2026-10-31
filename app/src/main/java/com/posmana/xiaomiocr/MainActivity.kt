package com.posmana.xiaomiocr

import android.net.Uri
import android.os.Bundle
import android.widget.*
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.Base64
import java.util.concurrent.TimeUnit

class MainActivity : ComponentActivity() {
    private val client = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(10, TimeUnit.MINUTES)
        .writeTimeout(2, TimeUnit.MINUTES).build()

    private lateinit var status: TextView
    private lateinit var output: TextView
    private lateinit var prompt: EditText
    private lateinit var modelButton: Button
    private lateinit var projectorButton: Button
    private lateinit var imageButton: Button
    private lateinit var runButton: Button
    private var modelFile: File? = null
    private var projectorFile: File? = null
    private var imageUri: Uri? = null
    private var server: Process? = null

    private val modelPicker = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        uri?.let { copyPicked(it, "model-q4_k_m.gguf") { f -> modelFile = f; modelButton.text = "Model ready ✓" } }
    }
    private val projectorPicker = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        uri?.let { copyPicked(it, "mmproj-f16.gguf") { f -> projectorFile = f; projectorButton.text = "Vision projector ready ✓" } }
    }
    private val imagePicker = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        imageUri = uri
        imageButton.text = if (uri != null) "Image selected ✓" else "Choose image"
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(28, 32, 28, 24)
        }
        fun button(label: String, action: () -> Unit) = Button(this).also {
            it.text = label; it.setOnClickListener { action() }; root.addView(it)
        }
        root.addView(TextView(this).apply {
            text = "Xiaomi OCR"; textSize = 26f
        })
        root.addView(TextView(this).apply {
            text = "Offline document OCR · Xiaomi-OCR-0 · ARM64"; textSize = 14f
        })
        status = TextView(this).apply { text = "Select the two GGUF files to begin."; setPadding(0, 16, 0, 16) }
        root.addView(status)
        modelButton = button("Choose Q4_K_M model") { modelPicker.launch(arrayOf("*/*")) }
        projectorButton = button("Choose F16 vision projector") { projectorPicker.launch(arrayOf("*/*")) }
        imageButton = button("Choose image") { imagePicker.launch(arrayOf("image/*")) }
        prompt = EditText(this).apply {
            setText("Read this document and transcribe its contents in reading order as Markdown. Preserve tables and formulas.")
            minLines = 3
        }
        root.addView(prompt)
        runButton = button("Start local OCR") { runOcr() }
        output = TextView(this).apply { textSize = 14f; setTextIsSelectable(true); setPadding(0, 20, 0, 20) }
        root.addView(ScrollView(this).apply { addView(output) },
            LinearLayout.LayoutParams(-1, 0, 1f))
        setContentView(root)
    }

    private fun copyPicked(uri: Uri, name: String, done: (File) -> Unit) {
        lifecycleScope.launch {
            try {
                status.text = "Copying selected file into app storage…"
                val file = withContext(Dispatchers.IO) {
                    val target = File(filesDir, name)
                    contentResolver.openInputStream(uri)!!.use { input ->
                        target.outputStream().use { out -> input.copyTo(out) }
                    }
                    target
                }
                done(file)
                status.text = "File ready: ${file.name} (${file.length() / 1024 / 1024} MB)"
            } catch (e: Exception) { status.text = "File import failed: ${e.message}" }
        }
    }

    private fun runOcr() {
        val model = modelFile
        val mmproj = projectorFile
        val image = imageUri
        if (model == null || mmproj == null || image == null) {
            status.text = "Select both GGUF files and an image first."; return
        }
        runButton.isEnabled = false
        lifecycleScope.launch {
            try {
                status.text = "Starting on-device inference engine…"
                withContext(Dispatchers.IO) { startServer(model, mmproj) }
                status.text = "Preparing image and running OCR…"
                val result = withContext(Dispatchers.IO) { requestOcr(image, prompt.text.toString()) }
                output.text = result
                status.text = "Finished. Inference ran locally on this phone."
            } catch (e: Exception) {
                status.text = "OCR failed: ${e.message}"
            } finally { runButton.isEnabled = true }
        }
    }

    private fun startServer(model: File, mmproj: File) {
        if (server?.isAlive == true) return
        val executable = File(filesDir, "llama-server")
        if (!executable.exists()) {
            assets.open("llama-server").use { input -> executable.outputStream().use { input.copyTo(it) } }
            executable.setExecutable(true)
        }
        server = ProcessBuilder(
            executable.absolutePath,
            "-m", model.absolutePath,
            "--mmproj", mmproj.absolutePath,
            "--host", "127.0.0.1", "--port", "8080",
            "-c", "2048", "-ngl", "0",
            "--image-min-tokens", "1024"
        ).directory(filesDir).redirectErrorStream(true)
            .redirectOutput(File(filesDir, "llama-server.log")).start()

        val deadline = System.currentTimeMillis() + 180_000
        while (System.currentTimeMillis() < deadline) {
            if (server?.isAlive != true) {
                val log = File(filesDir, "llama-server.log").takeIf { it.exists() }?.readText()?.takeLast(3000)
                error("llama-server exited. $log")
            }
            try {
                val response = client.newCall(Request.Builder().url("http://127.0.0.1:8080/health").build()).execute()
                response.close()
                if (response.isSuccessful) return
            } catch (_: Exception) { Thread.sleep(1000) }
        }
        error("Model loading timed out. Check available RAM and server log.")
    }

    private fun requestOcr(uri: Uri, task: String): String {
        val bytes = contentResolver.openInputStream(uri)!!.use { it.readBytes() }
        val mime = contentResolver.getType(uri) ?: "image/jpeg"
        val encoded = Base64.getEncoder().encodeToString(bytes)
        val content = JSONArray()
            .put(JSONObject().put("type", "text").put("text", task))
            .put(JSONObject().put("type", "image_url").put("image_url",
                JSONObject().put("url", "data:$mime;base64,$encoded")))
        val body = JSONObject().put("model", "xiaomi-ocr-0")
            .put("messages", JSONArray().put(JSONObject().put("role", "user").put("content", content)))
            .put("temperature", 0).put("max_tokens", 2048)
        val request = Request.Builder().url("http://127.0.0.1:8080/v1/chat/completions")
            .post(body.toString().toRequestBody("application/json".toMediaType())).build()
        client.newCall(request).execute().use { response ->
            val text = response.body?.string().orEmpty()
            if (!response.isSuccessful) error("HTTP ${response.code}: $text")
            val json = JSONObject(text)
            return json.getJSONArray("choices").getJSONObject(0)
                .getJSONObject("message").getString("content")
        }
    }

    override fun onDestroy() {
        server?.destroy()
        super.onDestroy()
    }
}
