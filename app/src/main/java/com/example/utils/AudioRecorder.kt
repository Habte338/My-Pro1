package com.example.utils

import android.content.Context
import android.media.MediaPlayer
import android.media.MediaRecorder
import android.os.Build
import java.io.File

class AudioRecorder(private val context: Context) {
    private var mediaRecorder: MediaRecorder? = null
    private var currentOutputFile: File? = null
    private var isRecording = false

    fun isRecording(): Boolean = isRecording

    fun getVoiceRecordingsDir(): File {
        return File(context.filesDir, "voice_recordings").apply {
            if (!exists()) mkdirs()
        }
    }

    fun startRecording(fileName: String): String? {
        val file = File(getVoiceRecordingsDir(), fileName)
        return startRecordingToFile(file)
    }

    fun startRecordingToFile(file: File): String? {
        stopRecording() // Clean up before starting

        file.parentFile?.mkdirs()
        currentOutputFile = file

        return try {
            val recorder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                MediaRecorder(context)
            } else {
                @Suppress("DEPRECATION")
                MediaRecorder()
            }
            recorder.apply {
                setAudioSource(MediaRecorder.AudioSource.MIC)
                setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
                setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
                setOutputFile(file.absolutePath)
                setAudioSamplingRate(16000)
                setAudioEncodingBitRate(32000)
                prepare()
                start()
            }
            mediaRecorder = recorder
            isRecording = true
            file.absolutePath
        } catch (e: Exception) {
            e.printStackTrace()
            try { mediaRecorder?.release() } catch (_: Exception) {}
            mediaRecorder = null
            isRecording = false
            currentOutputFile = null
            null
        }
    }

    fun stopRecording(): File? {
        val file = currentOutputFile
        if (!isRecording && mediaRecorder == null) {
            return if (file != null && file.exists() && file.length() > 0) file else null
        }
        try {
            mediaRecorder?.stop()
        } catch (e: Exception) {
            e.printStackTrace()
        } finally {
            try {
                mediaRecorder?.release()
            } catch (_: Exception) {}
            mediaRecorder = null
            isRecording = false
            currentOutputFile = null
        }
        return if (file != null && file.exists() && file.length() > 0) file else null
    }
}

class AudioPlayerManager {
    private var mediaPlayer: MediaPlayer? = null
    private var currentPath: String? = null

    fun play(
        filePath: String,
        onCompletion: () -> Unit,
        onError: (String) -> Unit
    ): Boolean {
        stop()
        val file = File(filePath)
        if (!file.exists() || file.length() == 0L) {
            onError("Voice file is missing or empty.")
            return false
        }
        return try {
            val player = MediaPlayer().apply {
                setDataSource(file.absolutePath)
                setOnCompletionListener {
                    stop()
                    onCompletion()
                }
                setOnErrorListener { _, what, extra ->
                    stop()
                    onError("Playback error ($what/$extra).")
                    true
                }
                prepare()
                start()
            }
            mediaPlayer = player
            currentPath = filePath
            true
        } catch (e: Exception) {
            stop()
            onError("Unable to play audio: ${e.message ?: "Unsupported format"}")
            false
        }
    }

    fun stop() {
        try {
            if (mediaPlayer?.isPlaying == true) {
                mediaPlayer?.stop()
            }
        } catch (_: Exception) {}
        try {
            mediaPlayer?.release()
        } catch (_: Exception) {}
        mediaPlayer = null
        currentPath = null
    }

    fun currentPlayingPath(): String? = currentPath
}
