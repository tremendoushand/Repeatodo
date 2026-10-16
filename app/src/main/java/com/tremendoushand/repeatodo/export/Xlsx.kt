package com.tremendoushand.repeatodo.export

import java.io.OutputStream
import java.time.DayOfWeek
import java.time.LocalDate
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * Writes a [MonthReport] as an Excel workbook (.xlsx) with two sheets:
 * a month calendar with what was done each day, and a task-by-day table.
 *
 * The file is built by hand (inline strings, no shared strings) to keep the app free of
 * spreadsheet libraries; it opens in Excel, Google Sheets and phone office apps.
 */
object Xlsx {
    const val MIME = "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"

    fun fileName(report: MonthReport) = "repeatodo-${report.month}.xlsx"

    fun write(report: MonthReport, out: OutputStream) {
        ZipOutputStream(out).use { zip ->
            fun put(name: String, xml: String) {
                zip.putNextEntry(ZipEntry(name))
                zip.write(xml.toByteArray(Charsets.UTF_8))
                zip.closeEntry()
            }
            put("[Content_Types].xml", CONTENT_TYPES)
            put("_rels/.rels", ROOT_RELS)
            put("xl/workbook.xml", WORKBOOK)
            put("xl/_rels/workbook.xml.rels", WORKBOOK_RELS)
            put("xl/styles.xml", STYLES)
            put("xl/worksheets/sheet1.xml", calendarSheet(report))
            put("xl/worksheets/sheet2.xml", taskSheet(report))
        }
    }

    // Cell styles, indexes into cellXfs in STYLES.
    private const val S_TITLE = 1
    private const val S_HEADER = 2
    private const val S_DAY = 3
    private const val S_OUTSIDE = 4
    private const val S_TEXT = 5
    private const val S_NUMBER = 6
    private const val S_NUMBER_DONE = 7
    private const val S_HEADER_SUN = 8
    private const val S_HEADER_SAT = 9
    private const val S_NOTE = 10

    private val WEEK = listOf("월", "화", "수", "목", "금", "토", "일")

    internal fun calendarSheet(report: MonthReport): String {
        val month = report.month
        val first = month.atDay(1)
        val lead = first.dayOfWeek.value - 1 // Monday first
        val weeks = (lead + month.lengthOfMonth() + 6) / 7
        val rows = StringBuilder()

        rows.row(1, height = 28.0) {
            str(cell("A", 1), "${month.year}년 ${month.monthValue}월 Repeatodo 기록", S_TITLE)
        }
        rows.row(2, height = 20.0) {
            WEEK.forEachIndexed { i, name -> str(cell(col(i), 2), name, headerStyle(i)) }
        }
        for (w in 0 until weeks) {
            val r = 3 + w
            val dates = (0 until 7).map { first.plusDays((w * 7 + it - lead).toLong()) }
            val lines = dates.filter { it.month == month.month }.maxOfOrNull { report.entriesOn(it).size + 1 } ?: 1
            rows.row(r, height = (lines * 15.0 + 6).coerceIn(60.0, 409.0)) {
                dates.forEachIndexed { i, date ->
                    val ref = cell(col(i), r)
                    if (date.month != month.month) {
                        append("<c r=\"$ref\" s=\"$S_OUTSIDE\"/>")
                    } else {
                        dayCell(ref, date, report.entriesOn(date))
                    }
                }
            }
        }
        val noteRow = 3 + weeks + 1
        rows.row(noteRow) {
            str(cell("A", noteRow), "할 일 이름 옆 숫자는 그날 한 횟수예요 (목표가 여러 번인 일은 한 횟수/목표).", S_NOTE)
        }

        val cols = (1..7).joinToString("") { "<col min=\"$it\" max=\"$it\" width=\"24\" customWidth=\"1\"/>" }
        return worksheet(
            sheetPr = "<sheetPr><pageSetUpPr fitToPage=\"1\"/></sheetPr>",
            dimension = "A1:G${noteRow}",
            views = "<sheetViews><sheetView workbookViewId=\"0\" showGridLines=\"0\"/></sheetViews>",
            cols = cols,
            rows = rows.toString(),
            merges = listOf("A1:G1", "A$noteRow:G$noteRow"),
            landscape = true,
        )
    }

    internal fun taskSheet(report: MonthReport): String {
        val month = report.month
        val days = month.lengthOfMonth()
        val lastCol = col(3 + days + 1)
        val rows = StringBuilder()

        rows.row(1, height = 20.0) {
            str(cell("A", 1), "카테고리", S_HEADER)
            str(cell("B", 1), "할 일", S_HEADER)
            str(cell("C", 1), "반복", S_HEADER)
            for (d in 1..days) number(cell(col(2 + d), 1), d, headerStyle(month.atDay(d).dayOfWeek.value - 1))
            str(cell(col(3 + days), 1), "합계(회)", S_HEADER)
            str(cell(col(4 + days), 1), "한 날(일)", S_HEADER)
        }
        rows.row(2) {
            str(cell("A", 2), "", S_HEADER)
            str(cell("B", 2), "", S_HEADER)
            str(cell("C", 2), "", S_HEADER)
            for (d in 1..days) {
                val i = month.atDay(d).dayOfWeek.value - 1
                str(cell(col(2 + d), 2), WEEK[i], headerStyle(i))
            }
            str(cell(col(3 + days), 2), "", S_HEADER)
            str(cell(col(4 + days), 2), "", S_HEADER)
        }
        report.tasks.forEachIndexed { index, line ->
            val r = 3 + index
            rows.row(r) {
                str(cell("A", r), line.category, S_TEXT)
                str(cell("B", r), line.title, S_TEXT)
                str(cell("C", r), line.repeat, S_TEXT)
                for (d in 1..days) {
                    val ref = cell(col(2 + d), r)
                    val count = line.counts[d]
                    if (count == null) append("<c r=\"$ref\" s=\"$S_NUMBER\"/>")
                    else number(ref, count, if (count >= line.perDay) S_NUMBER_DONE else S_NUMBER)
                }
                number(cell(col(3 + days), r), line.total, S_NUMBER)
                number(cell(col(4 + days), r), line.days, S_NUMBER)
            }
        }
        if (report.tasks.isEmpty()) {
            rows.row(3) { str(cell("A", 3), "이 달에는 기록이 없어요.", S_NOTE) }
        }

        val cols = "<col min=\"1\" max=\"1\" width=\"12\" customWidth=\"1\"/>" +
            "<col min=\"2\" max=\"2\" width=\"26\" customWidth=\"1\"/>" +
            "<col min=\"3\" max=\"3\" width=\"22\" customWidth=\"1\"/>" +
            "<col min=\"4\" max=\"${3 + days}\" width=\"4.5\" customWidth=\"1\"/>" +
            "<col min=\"${4 + days}\" max=\"${5 + days}\" width=\"9\" customWidth=\"1\"/>"
        val views = "<sheetViews><sheetView workbookViewId=\"0\">" +
            "<pane xSplit=\"3\" ySplit=\"2\" topLeftCell=\"D3\" activePane=\"bottomRight\" state=\"frozen\"/>" +
            "<selection pane=\"bottomRight\" activeCell=\"D3\" sqref=\"D3\"/>" +
            "</sheetView></sheetViews>"
        return worksheet(
            sheetPr = "<sheetPr><pageSetUpPr fitToPage=\"1\"/></sheetPr>",
            dimension = "A1:$lastCol${maxOf(3, 2 + report.tasks.size)}",
            views = views,
            cols = cols,
            rows = rows.toString(),
            merges = emptyList(),
            landscape = true,
        )
    }

    private fun headerStyle(dayIndex: Int) = when (dayIndex) {
        DayOfWeek.SUNDAY.value - 1 -> S_HEADER_SUN
        DayOfWeek.SATURDAY.value - 1 -> S_HEADER_SAT
        else -> S_HEADER
    }

    /** A calendar day: the day number in bold, then one line per task done. */
    private fun StringBuilder.dayCell(ref: String, date: LocalDate, entries: List<DayEntry>) {
        append("<c r=\"$ref\" s=\"$S_DAY\" t=\"inlineStr\"><is>")
        append("<r><rPr><b/><sz val=\"11\"/></rPr><t>${date.dayOfMonth}</t></r>")
        if (entries.isNotEmpty()) {
            val text = entries.joinToString("") { e ->
                val times = when {
                    e.perDay > 1 -> " ${e.count}/${e.perDay}회"
                    e.count > 1 -> " ${e.count}회"
                    else -> ""
                }
                "\n• ${e.title}$times"
            }
            append("<r><rPr><sz val=\"10\"/></rPr><t xml:space=\"preserve\">${esc(text)}</t></r>")
        }
        append("</is></c>")
    }

    private inline fun StringBuilder.row(r: Int, height: Double? = null, cells: StringBuilder.() -> Unit) {
        append("<row r=\"$r\"")
        if (height != null) append(" ht=\"$height\" customHeight=\"1\"")
        append(">")
        cells()
        append("</row>")
    }

    private fun StringBuilder.str(ref: String, text: String, style: Int) {
        append("<c r=\"$ref\" s=\"$style\" t=\"inlineStr\"><is><t xml:space=\"preserve\">${esc(text)}</t></is></c>")
    }

    private fun StringBuilder.number(ref: String, value: Int, style: Int) {
        append("<c r=\"$ref\" s=\"$style\"><v>$value</v></c>")
    }

    private fun worksheet(
        sheetPr: String,
        dimension: String,
        views: String,
        cols: String,
        rows: String,
        merges: List<String>,
        landscape: Boolean,
    ): String = buildString {
        append(XML_HEAD)
        append("<worksheet xmlns=\"$NS_MAIN\" xmlns:r=\"$NS_REL\">")
        append(sheetPr)
        append("<dimension ref=\"$dimension\"/>")
        append(views)
        append("<sheetFormatPr defaultRowHeight=\"16.5\"/>")
        append("<cols>$cols</cols>")
        append("<sheetData>$rows</sheetData>")
        if (merges.isNotEmpty()) {
            append("<mergeCells count=\"${merges.size}\">")
            merges.forEach { append("<mergeCell ref=\"$it\"/>") }
            append("</mergeCells>")
        }
        append("<pageMargins left=\"0.4\" right=\"0.4\" top=\"0.5\" bottom=\"0.5\" header=\"0.3\" footer=\"0.3\"/>")
        append("<pageSetup paperSize=\"9\" orientation=\"${if (landscape) "landscape" else "portrait"}\" fitToWidth=\"1\" fitToHeight=\"0\"/>")
        append("</worksheet>")
    }

    /** Column letters for a 0-based index (0 = A). */
    internal fun col(index: Int): String {
        var n = index + 1
        val sb = StringBuilder()
        while (n > 0) {
            val rem = (n - 1) % 26
            sb.append('A' + rem)
            n = (n - 1) / 26
        }
        return sb.reverse().toString()
    }

    private fun cell(col: String, row: Int) = "$col$row"

    /** Escapes text for XML and drops characters XML can't hold. */
    internal fun esc(text: String): String {
        val sb = StringBuilder(text.length)
        var i = 0
        while (i < text.length) {
            val ch = text[i]
            when {
                ch == '&' -> sb.append("&amp;")
                ch == '<' -> sb.append("&lt;")
                ch == '>' -> sb.append("&gt;")
                ch == '"' -> sb.append("&quot;")
                ch == '\n' || ch == '\t' -> sb.append(ch)
                ch == '\r' -> Unit
                ch < ' ' -> Unit
                Character.isHighSurrogate(ch) && i + 1 < text.length && Character.isLowSurrogate(text[i + 1]) -> {
                    sb.append(ch).append(text[i + 1])
                    i++
                }
                Character.isSurrogate(ch) -> Unit
                ch == '￾' || ch == '￿' -> Unit
                else -> sb.append(ch)
            }
            i++
        }
        return sb.toString()
    }

    private const val XML_HEAD = "<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?>\n"
    private const val NS_MAIN = "http://schemas.openxmlformats.org/spreadsheetml/2006/main"
    private const val NS_REL = "http://schemas.openxmlformats.org/officeDocument/2006/relationships"
    private const val NS_PKG_REL = "http://schemas.openxmlformats.org/package/2006/relationships"

    private const val CONTENT_TYPES = XML_HEAD +
        "<Types xmlns=\"http://schemas.openxmlformats.org/package/2006/content-types\">" +
        "<Default Extension=\"rels\" ContentType=\"application/vnd.openxmlformats-package.relationships+xml\"/>" +
        "<Default Extension=\"xml\" ContentType=\"application/xml\"/>" +
        "<Override PartName=\"/xl/workbook.xml\" ContentType=\"application/vnd.openxmlformats-officedocument.spreadsheetml.sheet.main+xml\"/>" +
        "<Override PartName=\"/xl/worksheets/sheet1.xml\" ContentType=\"application/vnd.openxmlformats-officedocument.spreadsheetml.worksheet+xml\"/>" +
        "<Override PartName=\"/xl/worksheets/sheet2.xml\" ContentType=\"application/vnd.openxmlformats-officedocument.spreadsheetml.worksheet+xml\"/>" +
        "<Override PartName=\"/xl/styles.xml\" ContentType=\"application/vnd.openxmlformats-officedocument.spreadsheetml.styles+xml\"/>" +
        "</Types>"

    private const val ROOT_RELS = XML_HEAD +
        "<Relationships xmlns=\"$NS_PKG_REL\">" +
        "<Relationship Id=\"rId1\" Type=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships/officeDocument\" Target=\"xl/workbook.xml\"/>" +
        "</Relationships>"

    private const val WORKBOOK = XML_HEAD +
        "<workbook xmlns=\"$NS_MAIN\" xmlns:r=\"$NS_REL\">" +
        "<bookViews><workbookView/></bookViews>" +
        "<sheets>" +
        "<sheet name=\"달력\" sheetId=\"1\" r:id=\"rId1\"/>" +
        "<sheet name=\"할 일별\" sheetId=\"2\" r:id=\"rId2\"/>" +
        "</sheets>" +
        "</workbook>"

    private const val WORKBOOK_RELS = XML_HEAD +
        "<Relationships xmlns=\"$NS_PKG_REL\">" +
        "<Relationship Id=\"rId1\" Type=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships/worksheet\" Target=\"worksheets/sheet1.xml\"/>" +
        "<Relationship Id=\"rId2\" Type=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships/worksheet\" Target=\"worksheets/sheet2.xml\"/>" +
        "<Relationship Id=\"rId3\" Type=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships/styles\" Target=\"styles.xml\"/>" +
        "</Relationships>"

    private const val THIN = "<left style=\"thin\"><color rgb=\"FFBFBFBF\"/></left><right style=\"thin\"><color rgb=\"FFBFBFBF\"/></right>" +
        "<top style=\"thin\"><color rgb=\"FFBFBFBF\"/></top><bottom style=\"thin\"><color rgb=\"FFBFBFBF\"/></bottom><diagonal/>"

    private const val STYLES = XML_HEAD +
        "<styleSheet xmlns=\"$NS_MAIN\">" +
        "<fonts count=\"6\">" +
        "<font><sz val=\"11\"/><name val=\"맑은 고딕\"/><family val=\"2\"/></font>" +
        "<font><b/><sz val=\"11\"/><name val=\"맑은 고딕\"/><family val=\"2\"/></font>" +
        "<font><b/><sz val=\"16\"/><name val=\"맑은 고딕\"/><family val=\"2\"/></font>" +
        "<font><b/><sz val=\"11\"/><color rgb=\"FFC00000\"/><name val=\"맑은 고딕\"/><family val=\"2\"/></font>" +
        "<font><b/><sz val=\"11\"/><color rgb=\"FF1F4E9F\"/><name val=\"맑은 고딕\"/><family val=\"2\"/></font>" +
        "<font><sz val=\"9\"/><color rgb=\"FF7F7F7F\"/><name val=\"맑은 고딕\"/><family val=\"2\"/></font>" +
        "</fonts>" +
        "<fills count=\"5\">" +
        "<fill><patternFill patternType=\"none\"/></fill>" +
        "<fill><patternFill patternType=\"gray125\"/></fill>" +
        "<fill><patternFill patternType=\"solid\"><fgColor rgb=\"FFE8EEF8\"/><bgColor indexed=\"64\"/></patternFill></fill>" +
        "<fill><patternFill patternType=\"solid\"><fgColor rgb=\"FFF2F2F2\"/><bgColor indexed=\"64\"/></patternFill></fill>" +
        "<fill><patternFill patternType=\"solid\"><fgColor rgb=\"FFD9EAD3\"/><bgColor indexed=\"64\"/></patternFill></fill>" +
        "</fills>" +
        "<borders count=\"2\">" +
        "<border><left/><right/><top/><bottom/><diagonal/></border>" +
        "<border>$THIN</border>" +
        "</borders>" +
        "<cellStyleXfs count=\"1\"><xf numFmtId=\"0\" fontId=\"0\" fillId=\"0\" borderId=\"0\"/></cellStyleXfs>" +
        "<cellXfs count=\"11\">" +
        // 0 default
        "<xf numFmtId=\"0\" fontId=\"0\" fillId=\"0\" borderId=\"0\" xfId=\"0\"/>" +
        // 1 title
        "<xf numFmtId=\"0\" fontId=\"2\" fillId=\"0\" borderId=\"0\" xfId=\"0\" applyFont=\"1\" applyAlignment=\"1\"><alignment horizontal=\"center\" vertical=\"center\"/></xf>" +
        // 2 header
        "<xf numFmtId=\"0\" fontId=\"1\" fillId=\"2\" borderId=\"1\" xfId=\"0\" applyFont=\"1\" applyFill=\"1\" applyBorder=\"1\" applyAlignment=\"1\"><alignment horizontal=\"center\" vertical=\"center\"/></xf>" +
        // 3 calendar day
        "<xf numFmtId=\"0\" fontId=\"0\" fillId=\"0\" borderId=\"1\" xfId=\"0\" applyBorder=\"1\" applyAlignment=\"1\"><alignment horizontal=\"left\" vertical=\"top\" wrapText=\"1\"/></xf>" +
        // 4 day outside the month
        "<xf numFmtId=\"0\" fontId=\"0\" fillId=\"3\" borderId=\"1\" xfId=\"0\" applyFill=\"1\" applyBorder=\"1\"/>" +
        // 5 text
        "<xf numFmtId=\"0\" fontId=\"0\" fillId=\"0\" borderId=\"1\" xfId=\"0\" applyBorder=\"1\" applyAlignment=\"1\"><alignment vertical=\"center\"/></xf>" +
        // 6 number
        "<xf numFmtId=\"0\" fontId=\"0\" fillId=\"0\" borderId=\"1\" xfId=\"0\" applyBorder=\"1\" applyAlignment=\"1\"><alignment horizontal=\"center\" vertical=\"center\"/></xf>" +
        // 7 number on a day the goal was met
        "<xf numFmtId=\"0\" fontId=\"0\" fillId=\"4\" borderId=\"1\" xfId=\"0\" applyFill=\"1\" applyBorder=\"1\" applyAlignment=\"1\"><alignment horizontal=\"center\" vertical=\"center\"/></xf>" +
        // 8 Sunday header
        "<xf numFmtId=\"0\" fontId=\"3\" fillId=\"2\" borderId=\"1\" xfId=\"0\" applyFont=\"1\" applyFill=\"1\" applyBorder=\"1\" applyAlignment=\"1\"><alignment horizontal=\"center\" vertical=\"center\"/></xf>" +
        // 9 Saturday header
        "<xf numFmtId=\"0\" fontId=\"4\" fillId=\"2\" borderId=\"1\" xfId=\"0\" applyFont=\"1\" applyFill=\"1\" applyBorder=\"1\" applyAlignment=\"1\"><alignment horizontal=\"center\" vertical=\"center\"/></xf>" +
        // 10 note
        "<xf numFmtId=\"0\" fontId=\"5\" fillId=\"0\" borderId=\"0\" xfId=\"0\" applyFont=\"1\"/>" +
        "</cellXfs>" +
        "<cellStyles count=\"1\"><cellStyle name=\"Normal\" xfId=\"0\" builtinId=\"0\"/></cellStyles>" +
        "</styleSheet>"
}
