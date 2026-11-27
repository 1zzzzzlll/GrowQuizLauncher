package dev.plume.quiz

import org.junit.Assert.assertEquals
import org.junit.Test

class TtsDiagnosisTest {

    @Test
    fun `no engine on board is a missing engine, not a broken one`() {
        assertEquals(TtsDiagnosis.MISSING, TtsDiagnosis.typeFor(emptyList()))
        assertEquals("tts_missing", TtsDiagnosis.typeFor(emptyList()))
    }

    @Test
    fun `an installed engine that will not start is an init failure`() {
        assertEquals(TtsDiagnosis.INIT_FAILED, TtsDiagnosis.typeFor(listOf("com.google.android.tts")))
        assertEquals("tts_init_fail", TtsDiagnosis.typeFor(listOf("com.k2fsa.sherpa.onnx.tts.engine")))
    }

    @Test
    fun `names are trimmed, deduplicated and sorted`() {
        val raw = listOf(" com.b.tts ", "com.a.tts", "com.b.tts", null, "  ")
        assertEquals(listOf("com.a.tts", "com.b.tts"), TtsDiagnosis.names(raw))
    }

    @Test
    fun `names drop to nothing when the list is only blanks`() {
        assertEquals(emptyList<String>(), TtsDiagnosis.names(listOf("", "   ", null)))
    }

    @Test
    fun `the message carries the status alone when no engine is installed`() {
        assertEquals("status=-1", TtsDiagnosis.msg(-1, emptyList()))
    }

    @Test
    fun `the message names the engines when there are any`() {
        assertEquals(
            "status=-1 engines=com.a.tts,com.b.tts",
            TtsDiagnosis.msg(-1, listOf("com.a.tts", "com.b.tts")),
        )
    }

    @Test
    fun `an installed engine is asked by name when the default one failed`() {
        assertEquals(
            "com.k2fsa.sherpa.onnx.tts.engine",
            TtsDiagnosis.explicitEngine(null, listOf("com.k2fsa.sherpa.onnx.tts.engine")),
        )
    }

    @Test
    fun `nothing is retried when no engine is installed`() {
        assertEquals(null, TtsDiagnosis.explicitEngine(null, emptyList()))
    }

    @Test
    fun `the named engine is not retried again`() {
        assertEquals(null, TtsDiagnosis.explicitEngine("com.a.tts", listOf("com.a.tts")))
    }

    @Test
    fun `transient tts failure retries only when an engine exists and cooldown passed`() {
        val gap = TtsDiagnosis.RETRY_GAP_MS
        org.junit.Assert.assertTrue(
            TtsDiagnosis.shouldRetryInit("no-language", enginesAvailable = true, retries = 0, lastFailElapsedMs = 10_000, nowElapsedMs = 10_000 + gap),
        )
        // Too soon after failure -> wait.
        org.junit.Assert.assertFalse(
            TtsDiagnosis.shouldRetryInit("no-language", enginesAvailable = true, retries = 0, lastFailElapsedMs = 10_000, nowElapsedMs = 10_000 + gap - 1),
        )
        // No engine installed on the TV -> never retry or spam tts_missing.
        org.junit.Assert.assertFalse(
            TtsDiagnosis.shouldRetryInit("no-engine", enginesAvailable = false, retries = 0, lastFailElapsedMs = 10_000, nowElapsedMs = 100_000),
        )
        // Hit retry cap -> stop spinning.
        org.junit.Assert.assertFalse(
            TtsDiagnosis.shouldRetryInit("no-language", enginesAvailable = true, retries = TtsDiagnosis.MAX_AUTO_RETRIES, lastFailElapsedMs = 10_000, nowElapsedMs = 100_000),
        )
        // No failure -> nothing to retry.
        org.junit.Assert.assertFalse(
            TtsDiagnosis.shouldRetryInit(null, enginesAvailable = true, retries = 0, lastFailElapsedMs = 10_000, nowElapsedMs = 100_000),
        )
    }

    @Test
    fun `service drop error codes trigger reconnect`() {
        org.junit.Assert.assertTrue(TtsDiagnosis.isReconnectableError(-5))
        org.junit.Assert.assertTrue(TtsDiagnosis.isReconnectableError(-9))
        org.junit.Assert.assertFalse(TtsDiagnosis.isReconnectableError(-1))
        org.junit.Assert.assertFalse(TtsDiagnosis.isReconnectableError(0))
    }
}
