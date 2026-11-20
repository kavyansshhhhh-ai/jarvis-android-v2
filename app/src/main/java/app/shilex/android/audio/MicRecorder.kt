package app.shilex.android.audio

import android.annotation.SuppressLint
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.media.audiofx.AcousticEchoCanceler
import android.media.audiofx.NoiseSuppressor
import app.shilex.android.Diag
import app.shilex.android.live.LiveProtocol

/** 16 kHz mono PCM from the mic, in 100 ms chunks, on its own thread. */
class MicRecorder(private val onChunk: (ByteArray) -> Unit) {
    private val lock = Any()
    @Volatile private var running = false
    @Volatile private var activeRecord: AudioRecord? = null
    private var thread: Thread? = null

    /** Callers check the RECORD_AUDIO permission first. Returns false if the mic is busy. */
    @SuppressLint("MissingPermission")
    fun start(): Boolean = synchronized(lock) {
        if (running) return true
        val rate = LiveProtocol.INPUT_SAMPLE_RATE
        val chunkBytes = rate / 10 * 2
        val minBuffer = AudioRecord.getMinBufferSize(rate, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
        val record = runCatching {
            AudioRecord(
                // The voice-call source turns on the phone's own echo and noise handling.
                MediaRecorder.AudioSource.VOICE_COMMUNICATION,
                rate,
                AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT,
                maxOf(minBuffer, chunkBytes * 4),
            )
        }.getOrNull() ?: return false
        if (record.state != AudioRecord.STATE_INITIALIZED) {
            runCatching { record.release() }
            Diag.w("mic: could not open (busy or blocked)")
            return false
        }
        val echo = if (AcousticEchoCanceler.isAvailable()) runCatching { AcousticEchoCanceler.create(record.audioSessionId) }.getOrNull() else null
        val noise = if (NoiseSuppressor.isAvailable()) runCatching { NoiseSuppressor.create(record.audioSessionId) }.getOrNull() else null
        val effects = listOfNotNull(echo, noise).onEach { runCatching { it.enabled = true } }
        Diag.i("mic: started, echo canceller=${echo != null}, noise suppressor=${noise != null}")

        activeRecord = record
        running = true
        runCatching { record.startRecording() }.onFailure { err ->
            Diag.w("mic: startRecording failed (${err.javaClass.simpleName})")
            running = false
            activeRecord = null
            effects.forEach { runCatching { it.release() } }
            runCatching { record.release() }
            return false
        }
        val worker = Thread({
            val buffer = ByteArray(chunkBytes)
            var reportedError = false
            try {
                while (running) {
                    val read = record.read(buffer, 0, buffer.size)
                    if (read > 0) {
                        onChunk(buffer.copyOf(read))
                    } else if (read < 0 && running && !reportedError) {
                        reportedError = true
                        Diag.w("mic: read error $read")
                        break
                    }
                }
            } finally {
                running = false
                runCatching { record.stop() }
                effects.forEach { runCatching { it.release() } }
                runCatching { record.release() }
                if (activeRecord === record) activeRecord = null
            }
        }, "mic")
        thread = worker
        worker.start()
        return true
    }

    fun stop() {
        val workerToJoin: Thread?
        synchronized(lock) {
            running = false
            runCatching { activeRecord?.stop() }
            workerToJoin = thread
            thread = null
        }
        if (workerToJoin != null && Thread.currentThread() !== workerToJoin) {
            runCatching { workerToJoin.join(400L) }
        }
    }
}
