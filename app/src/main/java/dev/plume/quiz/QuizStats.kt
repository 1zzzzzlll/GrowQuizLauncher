package dev.plume.quiz

import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/**
 * Turns the attempt log and the watch-time buckets into the parent-facing report. Pure Kotlin:
 * no Android classes, so the bucketing and the range math run under plain unit tests.
 *
 * Ranges are trailing windows measured in whole days (week = last 7 days, month = last 30, quarter
 * = last 91, half = last 182, year = last 365, all = everything). Buckets follow the range: days
 * for week and month, weeks for quarter and half, months for year and all.
 */
object QuizStats {
    data class Bucket(
        val key: String,
        val label: String,
        val attempts: Int,
        val correct: Int,
        val wrong: Int,
        val tvMs: Long,
    )

    data class CategoryStat(val name: String, val attempts: Int, val correct: Int, val wrong: Int)

    data class WrongStat(val id: String, val category: String, val count: Int)

    data class Report(
        val range: String,
        val sinceMs: Long,
        val buckets: List<Bucket>,
        val categories: List<CategoryStat>,
        val wrongs: List<WrongStat>,
        val attempts: Int,
        val correct: Int,
        val wrong: Int,
        val repeatWrong: Int,
        val scoreDelta: Int,
        val activeDays: Int,
        val tvMs: Long,
    )

    /** Trailing window length in days for every supported range key. */
    val RANGE_DAYS = linkedMapOf(
        "week" to 7,
        "month" to 30,
        "quarter" to 91,
        "half" to 182,
        "year" to 365,
        "all" to Int.MAX_VALUE,
    )

    private val dayFormat = DateTimeFormatter.ofPattern("yyyy-MM-dd")
    private val labelFormat = DateTimeFormatter.ofPattern("M/d")
    private val monthFormat = DateTimeFormatter.ofPattern("yyyy-MM")

    /** Builds the report; [zone] exists so tests can pin the time zone, the app uses the device one. */
    fun build(
        range: String,
        attempts: List<QuizLedger.Attempt>,
        tvMsByDay: Map<String, Long>,
        nowMs: Long,
        zone: ZoneId = ZoneId.systemDefault(),
    ): Report {
        val days = RANGE_DAYS[range] ?: RANGE_DAYS.getValue("month")
        val today = Instant.ofEpochMilli(nowMs).atZone(zone).toLocalDate()
        val firstDay = if (days == Int.MAX_VALUE) {
            attempts.minOfOrNull { Instant.ofEpochMilli(it.timeMs).atZone(zone).toLocalDate() } ?: today
        } else {
            today.minusDays((days - 1).toLong())
        }
        val sinceMs = firstDay.atStartOfDay(zone).toInstant().toEpochMilli()
        val inRange = attempts.filter { it.timeMs >= sinceMs }

        // per-day rollup: answers and points by local day key
        val dayAttempts = HashMap<String, Int>()
        val dayCorrect = HashMap<String, Int>()
        val dayWrong = HashMap<String, Int>()
        var correct = 0
        var wrong = 0
        var repeatWrong = 0
        var scoreDelta = 0
        for (a in inRange) {
            val key = Instant.ofEpochMilli(a.timeMs).atZone(zone).toLocalDate().format(dayFormat)
            dayAttempts[key] = (dayAttempts[key] ?: 0) + 1
            if (a.correct) {
                correct += 1
                dayCorrect[key] = (dayCorrect[key] ?: 0) + 1
            } else {
                wrong += 1
                if (a.repeatWrong) repeatWrong += 1
                dayWrong[key] = (dayWrong[key] ?: 0) + 1
            }
            scoreDelta += QuizRules.pointsFor(a.correct, a.repeatWrong)
        }

        // categories in the range, biggest first
        val catAttempts = LinkedHashMap<String, Int>()
        val catCorrect = LinkedHashMap<String, Int>()
        for (a in inRange) {
            catAttempts[a.category] = (catAttempts[a.category] ?: 0) + 1
            if (a.correct) catCorrect[a.category] = (catCorrect[a.category] ?: 0) + 1
        }
        val categories = catAttempts.entries
            .sortedByDescending { it.value }
            .map { (name, total) -> CategoryStat(name, total, catCorrect[name] ?: 0, total - (catCorrect[name] ?: 0)) }

        // the questions the child keeps missing, biggest first
        val wrongById = LinkedHashMap<String, WrongStat>()
        for (a in inRange) {
            if (a.correct) continue
            val known = wrongById[a.id]
            wrongById[a.id] = WrongStat(a.id, a.category, (known?.count ?: 0) + 1)
        }
        val wrongs = wrongById.values.sortedByDescending { it.count }.take(5)

        // buckets: days, weeks or months depending on the range; empty ones stay in so charts are continuous
        val daily = ArrayList<Bucket>()
        var day = firstDay
        while (!day.isAfter(today)) {
            val key = day.format(dayFormat)
            daily += Bucket(
                key = key,
                label = day.format(labelFormat),
                attempts = dayAttempts[key] ?: 0,
                correct = dayCorrect[key] ?: 0,
                wrong = dayWrong[key] ?: 0,
                tvMs = tvMsByDay[key] ?: 0L,
            )
            day = day.plusDays(1)
        }
        val buckets = when {
            range == "week" || range == "month" -> daily
            range == "quarter" || range == "half" -> groupWeeks(daily)
            else -> groupMonths(daily, zone) // year and all
        }

        var tvMs = 0L
        for (bucket in daily) tvMs += bucket.tvMs

        return Report(
            range = range,
            sinceMs = sinceMs,
            buckets = buckets,
            categories = categories,
            wrongs = wrongs,
            attempts = inRange.size,
            correct = correct,
            wrong = wrong,
            repeatWrong = repeatWrong,
            scoreDelta = scoreDelta,
            activeDays = dayAttempts.keys.size,
            tvMs = tvMs,
        )
    }

    /** Folds seven-day runs into one bucket, the newest run last; the oldest run may be short. */
    private fun groupWeeks(daily: List<Bucket>): List<Bucket> {
        val result = ArrayList<Bucket>()
        var index = 0
        while (index < daily.size) {
            val run = daily.subList(index, minOf(index + 7, daily.size))
            result += run.drop(1).fold(run.first().copy(label = "${run.first().label}") as Bucket) { acc, b ->
                acc.copy(attempts = acc.attempts + b.attempts, correct = acc.correct + b.correct, wrong = acc.wrong + b.wrong, tvMs = acc.tvMs + b.tvMs)
            }
            index += run.size
        }
        return result
    }

    /** Folds a month of days into one bucket keyed by yyyy-MM, newest last. */
    private fun groupMonths(daily: List<Bucket>, zone: ZoneId): List<Bucket> {
        val result = LinkedHashMap<String, Bucket>()
        for (bucket in daily) {
            val month = LocalDate.parse(bucket.key, dayFormat).format(monthFormat)
            val known = result[month]
            result[month] = if (known == null) {
                bucket.copy(key = month, label = month.substring(2)) // 26-11 style label
            } else {
                known.copy(
                    attempts = known.attempts + bucket.attempts,
                    correct = known.correct + bucket.correct,
                    wrong = known.wrong + bucket.wrong,
                    tvMs = known.tvMs + bucket.tvMs,
                )
            }
        }
        return result.values.toList()
    }
}
