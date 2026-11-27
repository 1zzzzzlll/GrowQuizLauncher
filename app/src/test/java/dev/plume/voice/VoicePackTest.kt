package dev.plume.voice

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class VoicePackTest {

    private val base = "https://example.test:8443/growquiz"

    @Test fun aRelativePackUrlResolvesUnderTheUpdateHost() {
        assertEquals(
            "$base/tts/zh.zip",
            VoicePacks.absolute(base, "tts/zh.zip"),
        )
        // A leading slash means the host root, not the update directory: that is how a file on
        // another path of the same server is addressed.
        assertEquals("https://example.test:8443/tts/zh.zip", VoicePacks.absolute("$base/", "/tts/zh.zip"))
        // A mirror in the manifest is left alone.
        assertEquals("https://cdn.test/zh.zip", VoicePacks.absolute(base, "https://cdn.test/zh.zip"))
    }

    @Test fun theManifestSitsNextToTheApkList() {
        assertEquals("$base/tts/voice.json", VoicePacks.manifestUrl(base))
        assertEquals("$base/tts/voice.json", VoicePacks.manifestUrl("$base/"))
        assertEquals("$base/tts/speak", VoicePacks.cloudSpeakUrl(base))
        assertEquals("$base/tts/speak", VoicePacks.cloudSpeakUrl("$base/"))
    }

    @Test fun sizesReadTheWayAParentSaysThem() {
        assertEquals("31 MB", VoicePacks.sizeText(30_600_000))
        assertEquals("28 MB", VoicePacks.sizeText(27_995_532))
        assertEquals("840 KB", VoicePacks.sizeText(840_000))
    }

    @Test fun aPackNeverWritesOutsideItsDirectory() {
        assertTrue(VoicePacks.safeEntry("model.onnx"))
        assertTrue(VoicePacks.safeEntry("sub/lexicon.txt"))
        assertFalse(VoicePacks.safeEntry("../shared_prefs/x.xml"))
        assertFalse(VoicePacks.safeEntry("/data/model.onnx"))
        assertFalse(VoicePacks.safeEntry("C:\\model.onnx"))
        assertFalse(VoicePacks.safeEntry(""))
    }

    @Test fun aPackIsOnlyCompleteWithTheThreeModelFiles() {
        assertTrue(VoicePacks.complete(setOf("model.onnx", "lexicon.txt", "tokens.txt")))
        assertTrue(VoicePacks.complete(setOf("model-steps-3.onnx", "vocoder.onnx", "lexicon.txt", "tokens.txt")))
        assertFalse(VoicePacks.complete(setOf("model-steps-3.onnx", "lexicon.txt", "tokens.txt")))
        assertFalse(VoicePacks.complete(setOf("model.onnx", "tokens.txt")))
        assertFalse(VoicePacks.complete(emptySet()))
    }

    @Test fun theSameSentenceGetsTheSameClipName() {
        val a = VoicePacks.clipName("听一听，选出正确的字", 0, 0.9f)
        assertEquals(a, VoicePacks.clipName("听一听，选出正确的字", 0, 0.9f))
        // A different voice or speed must not read a clip made for another one.
        assertTrue(a != VoicePacks.clipName("听一听，选出正确的字", 1, 0.9f))
        assertTrue(a != VoicePacks.clipName("听一听，选出正确的字", 0, 0.8f))
        assertTrue(a.endsWith(".pcm"))
    }

    @Test fun theDigestMatchesTheServedFile() {
        val file = File.createTempFile("pack", ".bin").apply {
            writeBytes(byteArrayOf(1, 2, 3, 4, 5))
            deleteOnExit()
        }
        // SHA-256 of 01 02 03 04 05, computed by sha256sum(1).
        assertEquals("74f81fe167d99b4cb41d6d0ccda82278caee9f3e2f25d5e5a3936ff3dcec60d0", VoicePacks.sha256(file))
    }

    @Test fun quietWaveformsAreBoostedAndSoftLimitedWithoutClipping() {
        // Simulate a quiet AISHELL-3 waveform peaking around 0.12 (about -18 dBFS).
        val quiet = FloatArray(400) { i -> if (i % 2 == 0) 0.10f else -0.10f }
        val boosted = EmbeddedTts.normalizeToPcm(quiet)
        val peakAfter = boosted.maxOf { kotlin.math.abs(it.toInt()) }
        assertTrue("expected boosted peak > 6000, got $peakAfter", peakAfter > 6000)
        assertTrue("expected soft-limited peak <= 32767, got $peakAfter", peakAfter <= 32767)

        // Extreme transient spikes must soft-limit below full-scale clipping.
        val loud = FloatArray(400) { i -> if (i == 10) 1.5f else 0.08f }
        val limited = EmbeddedTts.normalizeToPcm(loud)
        assertTrue(limited.all { it.toInt() in -32767..32767 })
    }

    @Test fun sherpaCallbackExposesExactBoxedInvokeSignatureRequiredByJni() {
        val method = SherpaCallback::class.java.getMethod("invoke", FloatArray::class.java)
        assertEquals(java.lang.Integer::class.java, method.returnType)
        val cb = SherpaCallback { samples -> samples.size }
        assertEquals(3, cb.invoke(floatArrayOf(0.1f, 0.2f, 0.3f)))
    }
}
