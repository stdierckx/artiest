package eu.torqa.artiest.spike

import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.provider.MediaStore
import org.json.JSONArray
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Writes the Phase 0 findings to Downloads as JSON.
 *
 * Doubles as a live test of the export path v1 needs: MediaStore on Android 14
 * with no runtime permission at all. If this works here, "save PNG to the
 * tablet" is already solved.
 */
object SessionExporter {

    fun buildReport(device: DeviceReport, stats: PenStats): JSONObject = JSONObject().apply {
        put("capturedAt", SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(Date()))

        put("device", JSONObject().apply {
            put("manufacturer", device.manufacturer)
            put("model", device.model)
            put("device", device.device)
            put("soc", device.soc)
            put("android", "${device.androidRelease} (API ${device.sdkInt})")
            put("memoryClassMb", device.memoryClassMb)
            put("largeMemoryClassMb", device.largeMemoryClassMb)
            put("currentRefreshHz", device.currentRefreshHz)
            put("displayModes", JSONArray(device.displayModes))
        })

        put("gpu", JSONObject().apply {
            put("vendor", device.glVendor)
            put("renderer", device.glRenderer)
            put("version", device.glVersion)
            put("maxTextureSize", device.glMaxTextureSize)
            put("supports4096Canvas", device.supportsFullCanvas)
        })

        put("pen", JSONObject().apply {
            put("events", stats.events)
            put("samples", stats.samples)
            put("historicalSamples", stats.historicalSamples)
            put("samplesPerEvent", stats.samplesPerEvent())
            put("sampleRateHz", stats.sampleRateHz())
            put("canceledEvents", stats.canceledEvents)
            put("flaggedCanceledPointers", stats.flaggedCanceledPointers)
            put("pressureMin", stats.pressureMin)
            put("pressureMax", stats.pressureMax)
            put("distinctPressureValues", stats.distinctPressureValues())
            put("estimatedPressureLevels", stats.estimatedPressureLevels())
            put("tiltMinRad", stats.tiltMin)
            put("tiltMaxRad", stats.tiltMax)
            put("orientationMinRad", stats.orientationMin)
            put("orientationMaxRad", stats.orientationMax)
            put("hoverSamples", stats.hoverSamples)
            put("hoverDistanceMax", stats.distanceMax)
            put("toolTypesSeen", JSONArray(stats.toolTypesSeen.map { toolTypeName(it) }))
            put("buttonStatesSeen", JSONArray(stats.buttonStatesSeen.toList()))
            put("historyBatchSizes", JSONObject(stats.historyBatchSizes.mapKeys { it.key.toString() }))
        })
    }

    fun writeToDownloads(context: Context, report: JSONObject): Uri? {
        val name = "artiest-phase0-${System.currentTimeMillis()}.json"
        val values = ContentValues().apply {
            put(MediaStore.Downloads.DISPLAY_NAME, name)
            put(MediaStore.Downloads.MIME_TYPE, "application/json")
            put(MediaStore.Downloads.IS_PENDING, 1)
        }
        val resolver = context.contentResolver
        val uri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values) ?: return null
        resolver.openOutputStream(uri)?.use { it.write(report.toString(2).toByteArray()) }
        values.clear()
        values.put(MediaStore.Downloads.IS_PENDING, 0)
        resolver.update(uri, values, null, null)
        return uri
    }
}
