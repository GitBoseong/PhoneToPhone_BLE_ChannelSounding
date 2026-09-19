package com.example.ble6_channelsounding

import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Environment
import android.provider.MediaStore
import java.io.BufferedWriter
import java.io.OutputStreamWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Owned by MeasurementRecorder's single executor, including open, flush and close. */
class SessionCsvLogger(private val context: Context) {
    private data class Output(val uri: Uri, val writer: BufferedWriter)
    private val outputs = mutableListOf<Output>()
    private var log: BufferedWriter? = null
    private var data: BufferedWriter? = null

    fun start(role: String) {
        close()
        val stamp = SimpleDateFormat("yyyyMMdd_HHmmss_SSS", Locale.US).format(Date())
        try {
            log = create("${stamp}_CS_${role}_LOG.csv")
            data = create("${stamp}_CS_${role}_DATA.csv")
            log!!.write("timestamp_iso8601,timestamp_epoch_ms,role,source,message\n")
            data!!.write(MeasurementCsv.header.joinToString(",") + "\n")
        } catch (e: Exception) {
            val incomplete = outputs.map { it.uri }
            close()
            incomplete.forEach { runCatching { context.contentResolver.delete(it, null, null) } }
            throw e
        }
    }

    private fun create(name: String): BufferedWriter {
        val resolver = context.contentResolver
        val uri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, ContentValues().apply {
            put(MediaStore.Downloads.DISPLAY_NAME, name)
            put(MediaStore.Downloads.MIME_TYPE, "text/csv")
            put(MediaStore.Downloads.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS + "/PhoneCS")
            put(MediaStore.Downloads.IS_PENDING, 1)
        }) ?: error("MediaStore insert failed: $name")
        try {
            val stream = resolver.openOutputStream(uri, "w") ?: error("Cannot open $name")
            val writer = BufferedWriter(OutputStreamWriter(stream, Charsets.UTF_8), 64 * 1024)
            outputs.add(Output(uri, writer))
            writer.write("\uFEFF")
            return writer
        } catch (e: Exception) {
            runCatching { resolver.delete(uri, null, null) }
            throw e
        }
    }

    fun event(timestamp: Long, role: String, source: String, message: String) {
        log?.apply {
            write(MeasurementCsv.line(listOf(MeasurementCsv.iso(timestamp), timestamp, role, source, message)))
            newLine()
        }
    }
    fun data(fields: List<Any?>) { data?.apply { write(MeasurementCsv.line(fields)); newLine() } }
    fun flush() { outputs.forEach { it.writer.flush() } }
    fun close() {
        var failure: Exception? = null
        outputs.forEach { output ->
            try { output.writer.flush() } catch (e: Exception) { failure = e }
            try { output.writer.close() } catch (e: Exception) { failure = e }
            try {
                context.contentResolver.update(output.uri, ContentValues().apply {
                    put(MediaStore.Downloads.IS_PENDING, 0)
                }, null, null)
            } catch (e: Exception) { failure = e }
        }
        outputs.clear(); log = null; data = null
        failure?.let { throw it }
    }
}
