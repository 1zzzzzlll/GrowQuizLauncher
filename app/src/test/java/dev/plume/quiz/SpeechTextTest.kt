package dev.plume.quiz

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SpeechTextTest {

    /** Signs must become words: an engine reads "12 + 8 = ?" as "十二 八" and drops the rest. */
    @Test fun signsBecomeWords() {
        assertEquals("十二加八等于？", SpeechText.forTts("12 + 8 = ?"))
    }

    @Test fun minusBetweenDigits() {
        assertEquals("九减四等于五", SpeechText.forTts("9-4=5"))
    }

    /** A decimal point is not a full stop, and a decimal is read as a Chinese number. */
    @Test fun decimalPointSurvives() {
        assertEquals("三点五 米", SpeechText.forTts("3.5 米"))
    }

    @Test fun fullStopBecomesPeriod() {
        assertEquals("第一句。第二句。", SpeechText.forTts("第一句.第二句."))
    }

    /** A bracket pair left as a blank must be heard as one word, not as a gap of silence. */
    @Test fun blanksAreSpoken() {
        assertEquals("填空空白，太阳从东边升起", SpeechText.forTts("填空（　）：太阳从东边升起"))
    }

    /** Algebra: the brackets group the terms and the minus sign stays a minus sign. */
    @Test fun algebraIsReadable() {
        assertEquals("括号艾克斯减三括号艾克斯减四括号", SpeechText.forTts("(x - 3)(x - 4)"))
    }

    @Test fun comparisonAndNegativeNumbers() {
        assertEquals("艾克斯 小于负三", SpeechText.forTts("x < -3"))
        assertEquals("负三 小于艾克斯 小于三", SpeechText.forTts("-3 < x < 3"))
    }

    @Test fun fractionsAndPowers() {
        assertEquals("八分之六", SpeechText.forTts("6/8"))
        assertEquals("三乘十的五次方 米每秒", SpeechText.forTts("3×10^5 米/秒"))
    }

    @Test fun rootsAndPercent() {
        assertEquals("五根号七", SpeechText.forTts("5√7"))
        assertEquals("百分之八十", SpeechText.forTts("80%"))
    }

    /** A colon between two numbers is a ratio, not a pause. */
    @Test fun ratiosAreReadAsBi() {
        assertEquals("十八比二十七", SpeechText.forTts("18:27"))
    }

    /** A slash between letters is a division, between Chinese words it is a rate. */
    @Test fun slashesDivideOrRate() {
        assertEquals("开除以艾克斯", SpeechText.forTts("k/x"))
        assertEquals("米每二次方秒", SpeechText.forTts("m/s²"))
        assertEquals("三百四十 米每秒", SpeechText.forTts("340 米/秒"))
    }

    @Test fun shapesAndArrowsAreNamed() {
        assertEquals("方块，三角形，圆圈", SpeechText.forTts("□，△，○"))
        assertEquals("阳光到草到兔", SpeechText.forTts("阳光→草→兔"))
    }

    @Test fun chemicalFormulasAreNamed() {
        assertEquals("二氧化碳 和 水", SpeechText.forTts("CO₂ 和 H₂O"))
    }

    /** A Chinese speech model has no sound for a bare "A": the label is read the way people name it. */
    @Test fun optionLabelsAreReadAloud() {
        assertEquals("选项诶，苹果。选项必，香蕉。", SpeechText.forTts("选项A：苹果。选项B：香蕉。"))
    }

    /** Only the label is rewritten: an English word in the question keeps its letters. */
    @Test fun englishWordsKeepTheirLetters() {
        assertEquals("TV 里的动物", SpeechText.forTts("TV里的动物"))
    }

    /** A long dash is a pause, not a minus sign. */
    @Test fun longDashIsAPause() {
        assertEquals("小明，小红是同学", SpeechText.forTts("小明——小红是同学"))
    }

    @Test fun unitsAreSpelledOut() {
        assertEquals("五厘米加三厘米", SpeechText.forTts("5cm + 3cm"))
    }

    @Test fun blankInputStaysBlank() {
        assertEquals("", SpeechText.forTts("   "))
    }

    /** Leading punctuation would otherwise make the engine start with a pause. */
    @Test fun leadingPunctuationIsTrimmed() {
        assertEquals("题目开始", SpeechText.forTts("，。 题目开始"))
    }

    @Test fun numbersAreSpokenInChinese() {
        assertEquals("二十四", SpeechText.numberToChinese(24))
        assertEquals("十", SpeechText.numberToChinese(10))
        assertEquals("十二", SpeechText.numberToChinese(12))
        assertEquals("十五", SpeechText.numberToChinese(15))
        assertEquals("一百零五", SpeechText.numberToChinese(105))
        assertEquals("一百一十", SpeechText.numberToChinese(110))
        assertEquals("二千零二十六", SpeechText.numberToChinese(2026))
        assertEquals("零", SpeechText.numberToChinese(0))
    }

    /** A one-character, one-number or one-letter option is the answer itself, so it is never spoken. */
    @Test fun shortOptionsAreNotSpeakable() {
        assertFalse(SpeechText.isSpeakableOption("3"))
        assertFalse(SpeechText.isSpeakableOption("12"))
        assertFalse(SpeechText.isSpeakableOption("红"))
        assertFalse(SpeechText.isSpeakableOption("B"))
        assertFalse(SpeechText.isSpeakableOption("red"))
        assertFalse(SpeechText.isSpeakableOption("Apple pie"))
        assertFalse(SpeechText.isSpeakableOption("（　）"))
        assertFalse(SpeechText.isSpeakableOption(""))
    }

    /** "3年" and "3.5米" are a bare number with a unit: the same leak as "3". */
    @Test fun numberWithUnitIsNotSpeakable() {
        assertFalse(SpeechText.isSpeakableOption("3年"))
        assertFalse(SpeechText.isSpeakableOption("3.5米"))
        assertFalse(SpeechText.isSpeakableOption("12 个"))
    }

    /** An option that carries a Chinese phrase is worth listening to. */
    @Test fun chinesePhraseOptionsAreSpeakable() {
        assertTrue(SpeechText.isSpeakableOption("红色"))
        assertTrue(SpeechText.isSpeakableOption(" 苹果 "))
        assertTrue(SpeechText.isSpeakableOption("大约3米"))
    }

    /** Four one-digit options: the stem is read, the options stay silent. */
    @Test fun numbersOnlyOptionsAreSkipped() {
        assertEquals(
            "下面哪个是偶数？",
            SpeechText.forQuestion("下面哪个是偶数？", listOf("3", "4", "5", "7")),
        )
    }

    /** A short option is dropped one by one; its neighbours are still read. */
    @Test fun onlyTheShortOptionIsSkipped() {
        assertEquals(
            "下面哪个是红色的？选项必，鲜红色的衣服",
            SpeechText.forQuestion("下面哪个是红色的？", listOf("红", "鲜红色的衣服", "12", "Apple")),
        )
    }

    /** Ordinary questions keep their four options, with the labels spelled the Chinese way. */
    @Test fun longOptionsAreAllRead() {
        assertEquals(
            "苹果是什么颜色？选项诶，红色。选项必，蓝色。选项西，绿色。选项迪，紫色",
            SpeechText.forQuestion("苹果是什么颜色？", listOf("红色", "蓝色", "绿色", "紫色")),
        )
    }
}
