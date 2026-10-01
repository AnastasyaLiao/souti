package com.souti.ai

import android.content.Context
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

/** 长按输入框说话 → 局域网那台 Mac 上的 FunASR（8737）转文字。
 *  录音用 16k/单声道/16bit PCM，落盘成 WAV 再 multipart POST，接口契约：
 *  POST /transcribe  字段名 audio  →  {"text": "..."}。 */
object Asr {

    private const val TAG = "AsrDiag"
    private const val RATE = 16000
    /** 默认走 8738：那是 asr_relay.py 转发到本机 8737 的口子。
     *  FunASR 服务自己只绑 127.0.0.1（fchat 那边的既定口径），不改它的代码。 */
    private const val DEFAULT_SERVER = "http://192.168.0.129:8738"

    fun server(ctx: Context): String =
        ctx.getSharedPreferences("cfg", Context.MODE_PRIVATE).getString("asr_server", DEFAULT_SERVER)
            ?: DEFAULT_SERVER

    fun setServer(ctx: Context, url: String) =
        ctx.getSharedPreferences("cfg", Context.MODE_PRIVATE).edit()
            .putString("asr_server", url.trimEnd('/')).apply()

    private var ar: AudioRecord? = null
    private var writer: Thread? = null
    private var pcm: File? = null
    private var dir: File? = null
    @Volatile private var recording = false

    /** 开始录音；麦克风不可用/被占用返回 false。 */
    @Suppress("MissingPermission")
    fun start(ctx: Context): Boolean {
        stop()
        val min = AudioRecord.getMinBufferSize(
            RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
        if (min <= 0) { Log.i(TAG, "start: bad minBufferSize=$min"); return false }
        val rec = runCatching {
            AudioRecord(MediaRecorder.AudioSource.VOICE_RECOGNITION, RATE,
                AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, min * 4)
        }.getOrNull() ?: return false
        if (rec.state != AudioRecord.STATE_INITIALIZED) {
            Log.i(TAG, "start: mic not initialized (被占用或没权限)")
            runCatching { rec.release() }; return false
        }
        val f = File(ctx.cacheDir, "asr.pcm").apply { delete() }
        ar = rec; pcm = f; dir = ctx.cacheDir; recording = true
        runCatching { rec.startRecording() }.onFailure { stop(); return false }
        writer = Thread {
            val buf = ByteArray(min * 2)
            runCatching {
                f.outputStream().buffered().use { os ->
                    while (recording) {
                        val n = rec.read(buf, 0, buf.size)
                        if (n > 0) os.write(buf, 0, n)
                    }
                }
            }
            runCatching { rec.release() }
        }.also { it.start() }
        Log.i(TAG, "start: recording @${RATE}Hz min=$min")
        return true
    }

    /** 上滑取消：立刻关掉麦克风，音频一个字节都不留，也不送去转写。 */
    fun discard() {
        val rec = ar ?: return
        Log.i(TAG, "discard: 丢弃 ${pcm?.length() ?: 0}B，不上送转写")
        recording = false
        runCatching { rec.stop() }
        runCatching { writer?.join(800) }
        ar = null; writer = null
        runCatching { pcm?.delete() }
        pcm = null
        dir?.let { runCatching { File(it, "asr.wav").delete() } }
    }

    /** 结束录音，拿到 WAV 文件（没录到东西返回 null）。 */
    fun stop(): File? {
        val rec = ar ?: return null
        recording = false
        runCatching { rec.stop() }
        runCatching { writer?.join(800) }
        ar = null; writer = null
        val src = pcm ?: return null
        pcm = null
        Log.i(TAG, "stop: 录到 ${src.length()}B")
        if (!src.exists() || src.length() < 1024) { src.delete(); return null }
        val out = File(src.parentFile, "asr.wav")
        out.outputStream().buffered().use { os ->
            os.write(wavHeader(src.length()))
            src.inputStream().buffered().use { it.copyTo(os) }
        }
        src.delete()
        return out
    }

    private fun wavHeader(dataLen: Long): ByteArray {
        val total = dataLen + 36
        val b = java.nio.ByteBuffer.allocate(44).order(java.nio.ByteOrder.LITTLE_ENDIAN)
        fun s(t: String) = t.toByteArray()
        b.put(s("RIFF")); b.putInt(total.toInt()); b.put(s("WAVE"))
        b.put(s("fmt ")); b.putInt(16); b.putShort(1); b.putShort(1)
        b.putInt(RATE); b.putInt(RATE * 2); b.putShort(2); b.putShort(16)
        b.put(s("data")); b.putInt(dataLen.toInt())
        return b.array()
    }

    /** 上传转写。失败时抛异常，调用方给提示。 */
    suspend fun transcribe(ctx: Context, wav: File): String = withContext(Dispatchers.IO) {
        val boundary = "----souti${System.currentTimeMillis()}"
        val conn = URL("${server(ctx)}/transcribe").openConnection() as HttpURLConnection
        conn.requestMethod = "POST"
        conn.doOutput = true
        conn.connectTimeout = 4000
        conn.readTimeout = 25000
        conn.setRequestProperty("Content-Type", "multipart/form-data; boundary=$boundary")
        conn.outputStream.use { os ->
            os.write(("--$boundary\r\nContent-Disposition: form-data; name=\"audio\"; " +
                "filename=\"q.wav\"\r\nContent-Type: audio/wav\r\n\r\n").toByteArray())
            wav.inputStream().buffered().use { it.copyTo(os) }
            os.write("\r\n--$boundary--\r\n".toByteArray())
        }
        val code = conn.responseCode
        val body = (if (code in 200..299) conn.inputStream else conn.errorStream)
            ?.bufferedReader()?.use { it.readText() } ?: ""
        conn.disconnect()
        if (code !in 200..299) error("HTTP $code ${body.take(120)}")
        JSONObject(body).optString("text").trim()
    }
}
