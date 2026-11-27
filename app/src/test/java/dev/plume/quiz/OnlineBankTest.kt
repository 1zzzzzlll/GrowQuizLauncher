package dev.plume.quiz

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import kotlin.random.Random

class OnlineBankTest {
    private val base = "https://example.test:8443/growquiz"
    private val validSha = "a".repeat(64)
    private val otherSha = "b".repeat(64)

    @Test
    fun autoDisableThresholdIsThreeCorrectAnswers() {
        assertEquals(3, QuestionBank.AUTO_DISABLE_AFTER)
    }

    @Test
    fun manifestAndRelativeShardUrlsResolveUnderUpdateHost() {
        assertEquals("$base/questions/manifest.json", OnlineBanks.manifestUrl(base))
        assertEquals("$base/questions/manifest.json", OnlineBanks.manifestUrl("$base/"))
        assertEquals("$base/questions/age_7.json", OnlineBanks.absolute(base, "questions/age_7.json"))
        assertEquals("https://example.test:8443/questions/age_7.json", OnlineBanks.absolute("$base/", "/questions/age_7.json"))
        assertEquals("https://cdn.test/age_7.json", OnlineBanks.absolute(base, "https://cdn.test/age_7.json"))
    }

    @Test
    fun randomDelaysStayInsideConfiguredJitterBounds() {
        assertEquals(OnlineBanks.FIRST_DELAY_MIN_MS, OnlineBanks.initialDelayMs(neverSynced = true, roll = 0.0))
        assertEquals(OnlineBanks.FIRST_DELAY_MAX_MS, OnlineBanks.initialDelayMs(neverSynced = true, roll = 1.0))
        assertEquals(OnlineBanks.START_DELAY_MIN_MS, OnlineBanks.initialDelayMs(neverSynced = false, roll = 0.0))
        assertEquals(OnlineBanks.START_DELAY_MAX_MS, OnlineBanks.initialDelayMs(neverSynced = false, roll = 1.0))
        assertEquals(OnlineBanks.SYNC_INTERVAL_MIN_MS, OnlineBanks.nextIntervalMs(0.0))
        assertEquals(OnlineBanks.SYNC_INTERVAL_MAX_MS, OnlineBanks.nextIntervalMs(1.0))
    }

    @Test
    fun parseManifestKeepsValidShardsAndSkipsBrokenOnes() {
        val raw = """
            {
              "version": "2026-fall-w13",
              "semester": "2026-2027学年第一学期（上册）",
              "teachingWeek": 13,
              "totalWeeks": 18,
              "progressPct": 72,
              "shards": [
                {"age": 7, "url": "questions/age_7.json", "sha256": "$validSha", "count": 120, "bytes": 45000},
                {"age": 8, "url": "questions/age_8.json", "sha256": "short", "count": 50},
                {"age": 99, "url": "questions/age_99.json", "sha256": "$validSha", "count": 10},
                {"age": 9, "url": "questions/age_9.json", "sha256": "$otherSha", "count": 140, "bytes": 52000}
              ]
            }
        """.trimIndent()
        val manifest = OnlineBanks.parseManifest(base, raw)
        assertNotNull(manifest)
        manifest!!
        assertEquals("2026-fall-w13", manifest.version)
        assertEquals(13, manifest.teachingWeek)
        assertEquals(72, manifest.progressPct)
        assertEquals(2, manifest.shards.size)
        assertEquals(7, manifest.shards[0].age)
        assertEquals("$base/questions/age_7.json", manifest.shards[0].url)
        assertEquals(9, manifest.shards[1].age)
        assertNull(OnlineBanks.parseManifest(base, "not json"))
    }

    @Test
    fun shardsToSyncAlwaysPrioritizesChildAgeAndCapsRoutineBatch() {
        val shards = (7..15).map { age ->
            OnlineShard(age = age, url = "$base/questions/age_$age.json", sha256 = validSha, count = 100)
        }
        val local = mapOf(7 to validSha, 9 to otherSha)
        val picked = OnlineBanks.shardsToSync(shards, local, preferredAge = 9, forceAll = false, rng = Random(42))
        assertEquals(OnlineBanks.MAX_SHARDS_PER_PASS, picked.size)
        assertEquals(9, picked.first().age)
        assertTrue(picked.none { it.age == 7 })

        val forced = OnlineBanks.shardsToSync(shards, local, preferredAge = 9, forceAll = true, rng = Random(42))
        assertEquals(8, forced.size)
        assertEquals(9, forced.first().age)
    }

    @Test
    fun parseShardQuestionsValidatesOptionsAndMarksOnlineFlag() {
        val raw = """
            {
              "age": 7,
              "questions": [
                {
                  "id": "o7-f01-001",
                  "age": 7,
                  "category": "语文",
                  "question": "《春夏秋冬》中，“春风吹”后面一句是什么？",
                  "options": ["夏雨落", "秋霜降", "冬雪飘", "池草青"],
                  "answerIndex": 0,
                  "explanation": "课文原文：春风吹，夏雨落，秋霜降，冬雪飘。"
                },
                {
                  "id": "o7-bad-dup-opts",
                  "age": 7,
                  "category": "数学",
                  "question": "1 + 1 = ?",
                  "options": ["2", "2", "3", "4"],
                  "answerIndex": 0
                },
                {
                  "id": "o8-wrong-age",
                  "age": 8,
                  "category": "数学",
                  "question": "2 × 3 = ?",
                  "options": ["5", "6", "7", "8"],
                  "answerIndex": 1
                }
              ]
            }
        """.trimIndent()
        val parsed = OnlineBanks.parseShardQuestions(raw, expectedAge = 7)
        assertNotNull(parsed)
        assertEquals(1, parsed!!.size)
        assertEquals("o7-f01-001", parsed[0].id)
        assertTrue(parsed[0].online)
        assertEquals(false, parsed[0].custom)
    }

    @Test
    fun sha256MatchesKnownVector() {
        val f = File.createTempFile("shard", ".json").apply {
            writeBytes(byteArrayOf(1, 2, 3, 4, 5))
            deleteOnExit()
        }
        assertEquals("74f81fe167d99b4cb41d6d0ccda82278caee9f3e2f25d5e5a3936ff3dcec60d0", OnlineBanks.sha256(f))
    }
}
