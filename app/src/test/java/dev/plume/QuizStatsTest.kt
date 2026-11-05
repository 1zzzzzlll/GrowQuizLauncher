package dev.plume

import dev.plume.quiz.QuizLedger
import dev.plume.quiz.QuizStats
import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.LocalDateTime
import java.time.ZoneOffset

class QuizStatsTest {
    // A fixed UTC zone keeps day boundaries stable no matter where the test runs.
    private val zone = ZoneOffset.UTC
    private fun ms(day: Int, hour: Int = 10) =
        LocalDateTime.of(2026, 11, day, hour, 0).toInstant(zone).toEpochMilli()
    private val now = ms(5, 20) // "today" for every test: November 5th, 2026

    private fun attempt(day: Int, id: String, category: String, correct: Boolean, repeat: Boolean = false, hour: Int = 10) =
        QuizLedger.Attempt(ms(day, hour), id, 7, category, correct, repeat, snap = false)

    @Test
    fun weekRangeCoversSevenDailyBucketsIncludingEmptyOnes() {
        val report = QuizStats.build(
            "week",
            listOf(attempt(5, "a7-0001", "数学", true), attempt(3, "a7-0002", "语文", false), attempt(3, "a7-0002", "语文", false, repeat = true, hour = 11)),
            mapOf("2026-11-05" to 90 * 60_000L),
            now, zone,
        )
        assertEquals(7, report.buckets.size)
        assertEquals("10/30", report.buckets.first().label)
        assertEquals("11/5", report.buckets.last().label)
        assertEquals(1, report.buckets.last().attempts)
        assertEquals(0, report.buckets.first().attempts) // the empty days stay as zero-height buckets
        assertEquals(3, report.attempts)
        assertEquals(1, report.correct)
        assertEquals(2, report.wrong)
        assertEquals(1, report.repeatWrong)
        assertEquals(10 - 5 - 13, report.scoreDelta)
        assertEquals(2, report.activeDays)
        assertEquals(90 * 60_000L, report.tvMs)
    }

    @Test
    fun olderAttemptsStayOutsideTheWindow() {
        val octoberFirst = QuizLedger.Attempt(
            LocalDateTime.of(2026, 10, 1, 10, 0).toInstant(zone).toEpochMilli(),
            "a7-0001", 7, "数学", correct = true, repeatWrong = false, snap = false,
        )
        val report = QuizStats.build(
            "week",
            listOf(octoberFirst, attempt(5, "a7-0002", "数学", true)),
            emptyMap(), now, zone,
        )
        assertEquals(1, report.attempts) // October 1st lies before the 7-day window
        assertEquals(10, report.scoreDelta)
    }

    @Test
    fun categoriesSortByAttemptsAndCountAccuracy() {
        val report = QuizStats.build(
            "week",
            listOf(
                attempt(5, "a7-0001", "数学", true), attempt(5, "a7-0002", "数学", false),
                attempt(5, "a7-0003", "语文", true), attempt(5, "a7-0004", "语文", true),
                attempt(4, "a7-0005", "语文", false),
            ),
            emptyMap(), now, zone,
        )
        assertEquals(listOf("语文", "数学"), report.categories.map { it.name }) // 3 answers beat 2
        assertEquals(2, report.categories[0].correct)
        assertEquals(1, report.categories[0].wrong)
        assertEquals(1, report.categories[1].correct)
        assertEquals(1, report.categories[1].wrong)
    }

    @Test
    fun repeatedWrongsRankTheTopWrongQuestions() {
        val report = QuizStats.build(
            "month",
            listOf(
                attempt(5, "a7-0002", "数学", false), attempt(4, "a7-0002", "数学", false),
                attempt(4, "a7-0009", "常识", false), attempt(3, "a7-0001", "数学", true),
            ),
            emptyMap(), now, zone,
        )
        assertEquals("a7-0002", report.wrongs.first().id)
        assertEquals(2, report.wrongs.first().count)
        assertEquals("数学", report.wrongs.first().category)
        assertEquals(listOf("a7-0002", "a7-0009"), report.wrongs.map { it.id })
    }

    @Test
    fun yearAndAllGroupIntoMonthlyBuckets() {
        val report = QuizStats.build(
            "all",
            listOf(attempt(1, "a7-0001", "数学", true), attempt(5, "a7-0002", "数学", true)),
            mapOf("2026-11-05" to 60_000L, "2026-11-01" to 60_000L),
            now, zone,
        )
        assertEquals(1, report.buckets.size) // both days live in the same month
        assertEquals("2026-11", report.buckets.first().key)
        assertEquals(2, report.buckets.first().attempts)
        assertEquals(120_000L, report.tvMs)
    }
}
