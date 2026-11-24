package com.example.sparklog.export

import android.content.Context
import android.content.Intent
import android.graphics.Paint
import android.graphics.Typeface
import android.graphics.pdf.PdfDocument
import androidx.core.content.FileProvider
import com.example.sparklog.data.Customer
import com.example.sparklog.data.Job
import com.example.sparklog.data.Material
import com.example.sparklog.data.Trip
import com.example.sparklog.data.WorkEntry
import java.io.File
import java.util.Locale

/** Everything about one job that goes into an export. */
data class JobExportData(
    val job: Job,
    val customer: Customer?,
    val work: List<WorkEntry>,
    val trips: List<Trip>,
    val materials: List<Material>,
    /** Effective material markup in percent. Used to compute customer prices; never printed. */
    val markupPercent: Double = 0.0,
) {
    /** Price per unit as the customer sees it. */
    fun unitPrice(m: Material) = Markup.unitPrice(m, markupPercent)
    /** Line total as the customer sees it. */
    fun lineTotal(m: Material) = Markup.lineTotal(m, markupPercent)
}

/**
 * Markup maths shared by the screens and the exports. A line's own markup (if set) wins over
 * the job's. Unit prices are rounded to whole öre before multiplying by the quantity.
 */
object Markup {
    private fun round2(d: Double) = Math.round(d * 100.0) / 100.0
    fun effective(m: Material, jobPct: Double) = if (m.markupPercent >= 0) m.markupPercent else jobPct
    fun unitPrice(m: Material, jobPct: Double) = round2(m.unitPrice * (1.0 + effective(m, jobPct) / 100.0))
    fun lineTotal(m: Material, jobPct: Double) = round2(m.quantity * unitPrice(m, jobPct))
    fun cost(m: Material) = round2(m.quantity * m.unitPrice)
    fun profit(m: Material, jobPct: Double) = round2(lineTotal(m, jobPct) - cost(m))
}

object JobExport {
    private val sv = Locale("sv", "SE") // decimal comma, matches Swedish Excel and invoices

    private fun money(d: Double) = String.format(sv, "%.2f", d)
    private fun num(d: Double) = if (d % 1.0 == 0.0) d.toInt().toString() else String.format(sv, "%.1f", d)

    private fun exportDir(context: Context): File = File(context.cacheDir, "exports").apply { mkdirs() }

    /**
     * Materials as a semicolon-separated CSV (opens directly in Excel with Swedish settings).
     * Customer-facing: prices include the markup, and supplier and invoice details are left out.
     */
    fun csv(context: Context, d: JobExportData): File {
        val sb = StringBuilder("﻿") // BOM so Excel reads UTF-8
        sb.append("Job;Customer;Date;Material;Quantity;Unit;Unit price;Total\n")
        fun q(s: String) = "\"" + s.replace("\"", "\"\"") + "\""
        for (m in d.materials.sortedWith(compareBy({ it.date }, { it.name }))) {
            sb.append(listOf(
                d.job.number.toString(), q(d.customer?.name.orEmpty()), m.date, q(m.name), num(m.quantity), m.unit,
                money(d.unitPrice(m)), money(d.lineTotal(m)),
            ).joinToString(";")).append("\n")
        }
        val f = File(exportDir(context), "Job-${d.job.number}-materials.csv")
        f.writeText(sb.toString(), Charsets.UTF_8)
        return f
    }

    /** A one-to-few page PDF summary: customer, totals, materials by date, hours and trips. */
    fun pdf(context: Context, d: JobExportData): File {
        val w = PdfWriter()
        val matTotal = d.materials.sumOf { d.lineTotal(it) }

        w.text("Job #${d.job.number}" + if (d.job.title.isNotBlank()) " - ${d.job.title}" else "", w.title)
        w.gap(4f)
        d.customer?.let { c ->
            w.text(c.name, w.bold)
            if (c.contactPerson.isNotBlank()) w.text(c.contactPerson, w.body)
            val place = listOf(c.address, listOf(c.postalCode, c.city).filter { it.isNotBlank() }.joinToString(" "))
                .filter { it.isNotBlank() }.joinToString(", ")
            if (place.isNotBlank()) w.text(place, w.body)
            if (c.propertyId.isNotBlank()) w.text("Property: ${c.propertyId}", w.body)
            if (c.orgNumber.isNotBlank()) w.text("ID: ${c.orgNumber}", w.body)
        }
        w.gap(8f)
        w.text(
            "Hours: ${num(d.work.sumOf { it.hours })}     Driven: ${num(d.trips.sumOf { it.km })} km     " +
                "Materials: ${money(matTotal)} kr (ex VAT)",
            w.bold,
        )

        // ---- materials by date
        w.gap(14f)
        w.text("Materials", w.h2)
        if (d.materials.isEmpty()) w.text("None", w.small)
        d.materials.groupBy { it.date }.toSortedMap().forEach { (date, lines) ->
            w.gap(6f)
            w.need(30f)
            w.row(date, "", "", money(lines.sumOf { d.lineTotal(it) }), w.boldSmall)
            for (m in lines) {
                w.row(m.name, "${num(m.quantity)} ${m.unit}", if (m.unitPrice > 0) money(d.unitPrice(m)) else "",
                    if (m.unitPrice > 0) money(d.lineTotal(m)) else "", w.small)
            }
        }
        if (d.materials.isNotEmpty()) {
            w.gap(6f)
            w.row("Total materials", "", "", money(matTotal), w.boldSmall)
        }

        // ---- hours
        if (d.work.isNotEmpty()) {
            w.gap(16f)
            w.text("Work hours", w.h2)
            w.gap(4f)
            for (e in d.work.sortedBy { it.date }) {
                w.row("${e.date}  ${e.description}", "", "", num(e.hours) + " h", w.small)
            }
            w.row("Total hours", "", "", num(d.work.sumOf { it.hours }) + " h", w.boldSmall)
        }

        // ---- driving
        if (d.trips.isNotEmpty()) {
            w.gap(16f)
            w.text("Driving", w.h2)
            w.gap(4f)
            for (t in d.trips.sortedBy { it.date }) {
                val route = listOf(t.fromPlace, t.toPlace).filter { it.isNotBlank() }.joinToString(" - ")
                w.row("${t.date}  $route".trim(), "", "", num(t.km) + " km", w.small)
            }
            w.row("Total driven", "", "", num(d.trips.sumOf { it.km }) + " km", w.boldSmall)
        }

        val f = File(exportDir(context), "Job-${d.job.number}.pdf")
        w.finish(f)
        return f
    }

    fun share(context: Context, file: File, mime: String, subject: String) {
        val uri = FileProvider.getUriForFile(context, context.packageName + ".fileprovider", file)
        val send = Intent(Intent.ACTION_SEND).apply {
            type = mime
            putExtra(Intent.EXTRA_STREAM, uri)
            putExtra(Intent.EXTRA_SUBJECT, subject)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        context.startActivity(Intent.createChooser(send, "Share").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }
}

/** Minimal A4 PDF text writer with automatic page breaks. */
private class PdfWriter {
    private val pageW = 595
    private val pageH = 842
    private val margin = 40f
    private val doc = PdfDocument()
    private var page: PdfDocument.Page? = null
    private var y = margin
    private var pageNo = 0

    private fun paint(size: Float, bold: Boolean = false, gray: Boolean = false) = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textSize = size
        typeface = if (bold) Typeface.DEFAULT_BOLD else Typeface.DEFAULT
        color = if (gray) 0xFF555555.toInt() else 0xFF111111.toInt()
    }

    val title = paint(18f, bold = true)
    val h2 = paint(13f, bold = true)
    val bold = paint(11f, bold = true)
    val body = paint(11f)
    val small = paint(10f)
    val boldSmall = paint(10f, bold = true)

    private fun ensurePage() {
        if (page == null) {
            pageNo++
            page = doc.startPage(PdfDocument.PageInfo.Builder(pageW, pageH, pageNo).create())
            y = margin
        }
    }

    /** Make sure `height` points fit on the current page, otherwise start a new one. */
    fun need(height: Float) {
        ensurePage()
        if (y + height > pageH - margin) {
            doc.finishPage(page)
            page = null
            ensurePage()
        }
    }

    fun gap(h: Float) { ensurePage(); y += h }

    private fun fit(s: String, p: Paint, maxWidth: Float): String {
        if (p.measureText(s) <= maxWidth) return s
        var end = s.length
        while (end > 1 && p.measureText(s.substring(0, end) + "...") > maxWidth) end--
        return s.substring(0, end) + "..."
    }

    fun text(s: String, p: Paint) {
        need(p.textSize + 6f)
        page!!.canvas.drawText(fit(s, p, pageW - 2 * margin), margin, y + p.textSize, p)
        y += p.textSize + 6f
    }

    /** Table-like row: label left, quantity and unit price in the middle, amount right-aligned. */
    fun row(label: String, qty: String, unitPrice: String, amount: String, p: Paint) {
        need(p.textSize + 5f)
        val c = page!!.canvas
        val base = y + p.textSize
        val right = pageW - margin
        c.drawText(fit(label, p, 250f), margin, base, p)
        if (qty.isNotEmpty()) c.drawText(qty, 305f, base, p)
        if (unitPrice.isNotEmpty()) c.drawText(unitPrice, 455f - p.measureText(unitPrice), base, p)
        if (amount.isNotEmpty()) c.drawText(amount, right - p.measureText(amount), base, p)
        y += p.textSize + 5f
    }

    fun finish(file: File) {
        ensurePage()
        doc.finishPage(page)
        file.outputStream().use { doc.writeTo(it) }
        doc.close()
    }
}
