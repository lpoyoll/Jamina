package com.jamina.reports

import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.pdf.PdfDocument
import com.jamina.core.db.ScanObservationEntity
import com.jamina.core.db.SurveyPointEntity
import java.io.OutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

data class ReportSection(
    val title: String,
    val lines: List<String> = emptyList(),
    val image: Bitmap? = null,
    val caption: String? = null,
)

/** Simple paginated A4 PDF writer using Android's PdfDocument. */
object PdfReport {
    private const val W = 595
    private const val H = 842
    private const val M = 40f

    fun write(out: OutputStream, title: String, subtitle: String, sections: List<ReportSection>) {
        val doc = PdfDocument()
        var pageNo = 0
        var page: PdfDocument.Page? = null
        var y = 0f
        val titleP = Paint(Paint.ANTI_ALIAS_FLAG).apply { textSize = 20f; isFakeBoldText = true; color = Color.BLACK }
        val headP = Paint(Paint.ANTI_ALIAS_FLAG).apply { textSize = 14f; isFakeBoldText = true; color = 0xFF00658F.toInt() }
        val bodyP = Paint(Paint.ANTI_ALIAS_FLAG).apply { textSize = 10f; color = Color.BLACK }
        val smallP = Paint(Paint.ANTI_ALIAS_FLAG).apply { textSize = 8f; color = Color.DKGRAY }

        fun newPage() {
            page?.let { doc.finishPage(it) }
            pageNo++
            page = doc.startPage(PdfDocument.PageInfo.Builder(W, H, pageNo).create())
            y = M
            page!!.canvas.drawText("Jamina Wi-Fi Survey — page $pageNo", M, H - 20f, smallP)
        }

        fun ensure(space: Float) { if (page == null || y + space > H - M) newPage() }

        fun wrap(text: String, p: Paint, maxW: Float): List<String> {
            val words = text.split(" ")
            val lines = mutableListOf<String>()
            var cur = ""
            for (w in words) {
                val trial = if (cur.isEmpty()) w else "$cur $w"
                if (p.measureText(trial) > maxW && cur.isNotEmpty()) { lines += cur; cur = w } else cur = trial
            }
            if (cur.isNotEmpty()) lines += cur
            return lines
        }

        newPage()
        page!!.canvas.drawText(title, M, y + 20f, titleP); y += 34f
        for (l in wrap(subtitle, bodyP, W - 2 * M)) { page!!.canvas.drawText(l, M, y, bodyP); y += 14f }
        y += 10f

        for (s in sections) {
            ensure(40f)
            page!!.canvas.drawText(s.title, M, y + 12f, headP); y += 24f
            for (line in s.lines) {
                for (l in wrap(line, bodyP, W - 2 * M)) {
                    ensure(14f)
                    page!!.canvas.drawText(l, M, y + 10f, bodyP); y += 14f
                }
            }
            s.image?.let { bmp ->
                val maxW = W - 2 * M
                val scale = minOf(maxW / bmp.width, (H - 2 * M - 60f) / bmp.height)
                val iw = bmp.width * scale; val ih = bmp.height * scale
                ensure(ih + 24f)
                page!!.canvas.drawBitmap(bmp, null, RectF(M, y + 4f, M + iw, y + 4f + ih), Paint(Paint.FILTER_BITMAP_FLAG))
                y += ih + 8f
                s.caption?.let { cap -> for (l in wrap(cap, smallP, maxW)) { ensure(12f); page!!.canvas.drawText(l, M, y + 8f, smallP); y += 11f } }
            }
            y += 12f
        }
        page?.let { doc.finishPage(it) }
        doc.writeTo(out)
        doc.close()
    }
}

object CsvExport {
    private val fmt = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS", Locale.UK)

    private fun esc(s: String?): String {
        if (s == null) return ""
        return if (s.contains(',') || s.contains('"') || s.contains('\n')) "\"" + s.replace("\"", "\"\"") + "\"" else s
    }

    fun write(
        out: OutputStream,
        sessionNames: Map<String, String>,
        points: List<SurveyPointEntity>,
        obs: Map<String, List<ScanObservationEntity>>,
        metersPerUnit: Map<String, Float>,
    ) {
        val w = out.bufferedWriter()
        w.write("session,point_id,time,x_m,y_m,position_source,position_accuracy_m,scan_fresh,scan_age_ms,connected_ssid,connected_bssid,connected_rssi,link_mbps,latency_ms,loss_pct,throughput_mbps,bssid,ssid,rssi_dbm,frequency_mhz,channel,width_mhz,observed_time\n")
        for (p in points) {
            val mpu = metersPerUnit[p.sessionId] ?: 1f
            val base = listOf(
                esc(sessionNames[p.sessionId]), p.id, fmt.format(Date(p.timestamp)), "%.2f".format(Locale.UK, p.x * mpu), "%.2f".format(Locale.UK, p.y * mpu),
                p.positionSource, "%.1f".format(Locale.UK, p.accuracyM), p.scanFresh.toString(), p.scanAgeMs.toString(),
                esc(p.connectedSsid), p.connectedBssid ?: "", p.connectedRssi?.toString() ?: "", p.linkSpeedMbps?.toString() ?: "",
                p.latencyMs?.let { "%.1f".format(Locale.UK, it) } ?: "", p.packetLossPct?.let { "%.1f".format(Locale.UK, it) } ?: "",
                p.throughputMbps?.let { "%.1f".format(Locale.UK, it) } ?: "",
            ).joinToString(",")
            val list = p.scanBatchId?.let { obs[it] } ?: emptyList()
            if (list.isEmpty()) w.write("$base,,,,,,,\n")
            for (o in list) {
                w.write("$base,${o.bssid},${esc(o.ssid)},${o.rssi},${o.frequency},${o.channel},${o.widthMhz},${fmt.format(Date(o.timestamp))}\n")
            }
        }
        w.flush()
    }
}
