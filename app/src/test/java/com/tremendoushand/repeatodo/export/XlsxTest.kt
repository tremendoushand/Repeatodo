package com.tremendoushand.repeatodo.export

import com.tremendoushand.repeatodo.data.CategoryEntity
import com.tremendoushand.repeatodo.data.CompletionEntity
import com.tremendoushand.repeatodo.data.TaskEntity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.time.YearMonth
import java.util.zip.ZipInputStream
import javax.xml.parsers.DocumentBuilderFactory

class XlsxTest {
    private val month = YearMonth.of(2026, 10)
    private fun day(d: Int) = month.atDay(d).toEpochDay()

    private val categories = listOf(CategoryEntity("c1", "신앙", 0xFF8E24AA, 0, 0))
    private val tasks = listOf(
        TaskEntity("t1", "묵주기도", categoryId = "c1", targetCount = 5, startDate = 0, createdAt = 0, updatedAt = 0),
        TaskEntity("t2", "물 <8잔> & \"기록\"", targetCount = 8, startDate = 0, createdAt = 1, updatedAt = 0),
        TaskEntity("t3", "안 한 일", startDate = 0, createdAt = 2, updatedAt = 0),
    )
    private val completions = listOf(
        CompletionEntity("a", "t1", day(6), 5, 0),
        CompletionEntity("b", "t1", day(7), 3, 0),
        CompletionEntity("c", "t2", day(6), 2, 0),
        CompletionEntity("d", "t2", day(31), 0, 0),
        CompletionEntity("e", "t2", month.minusMonths(1).atEndOfMonth().toEpochDay(), 4, 0),
    )

    @Test
    fun report_keepsOnlyThisMonthsWork() {
        val report = buildMonthReport(month, tasks, categories, completions) { "" }
        // Untouched tasks, zero counts and other months are left out; categorized tasks come first.
        assertEquals(listOf("묵주기도", "물 <8잔> & \"기록\""), report.tasks.map { it.title })
        val rosary = report.tasks.first()
        assertEquals(mapOf(6 to 5, 7 to 3), rosary.counts)
        assertEquals(8, rosary.total)
        assertEquals(2, rosary.days)
        assertEquals(listOf("묵주기도", "물 <8잔> & \"기록\""), report.entriesOn(month.atDay(6)).map { it.title })
    }

    @Test
    fun workbook_partsAreWellFormedXml() {
        val report = buildMonthReport(month, tasks, categories, completions) { "매주 아무 때나" }
        val out = ByteArrayOutputStream()
        Xlsx.write(report, out)

        val parts = mutableMapOf<String, String>()
        ZipInputStream(ByteArrayInputStream(out.toByteArray())).use { zip ->
            generateSequence { zip.nextEntry }.forEach { parts[it.name] = zip.readBytes().toString(Charsets.UTF_8) }
        }
        assertEquals(
            setOf(
                "[Content_Types].xml", "_rels/.rels", "xl/workbook.xml", "xl/_rels/workbook.xml.rels",
                "xl/styles.xml", "xl/worksheets/sheet1.xml", "xl/worksheets/sheet2.xml",
            ),
            parts.keys,
        )
        val builder = DocumentBuilderFactory.newInstance().apply { isNamespaceAware = true }.newDocumentBuilder()
        parts.values.forEach { builder.parse(ByteArrayInputStream(it.toByteArray(Charsets.UTF_8))) }

        val calendar = parts.getValue("xl/worksheets/sheet1.xml")
        assertTrue(calendar.contains("묵주기도 5/5회"))
        assertTrue(calendar.contains("물 &lt;8잔&gt; &amp; &quot;기록&quot; 2/8회"))
        assertEquals("repeatodo-2026-10.xlsx", Xlsx.fileName(report))
    }

    @Test
    fun columnsAndEscaping() {
        assertEquals("A", Xlsx.col(0))
        assertEquals("Z", Xlsx.col(25))
        assertEquals("AA", Xlsx.col(26))
        assertEquals("AK", Xlsx.col(36))
        assertEquals("a&lt;b&amp;x😀", Xlsx.esc("a<b&\u0001\uD800x😀"))
    }
}
