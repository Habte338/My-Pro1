package com.example.utils

import android.content.Context
import android.media.MediaRecorder
import android.os.Build
import java.io.File

class AudioRecorder(private val context: Context) {
    private var mediaRecorder: MediaRecorder? = null
    private var currentOutputFile: File? = null
    private var isRecording = false

    fun isRecording(): Boolean = isRecording

    fun startRecording(fileName: String): String? {
        stopRecording() // Clean up before starting
        
        val file = File(context.cacheDir, fileName)
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
