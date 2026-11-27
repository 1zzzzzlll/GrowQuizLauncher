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

    /** Bilingual cloud speech keeps real option letters A..D instead of rising "诶 (éi)". */
    @Test fun bilingualModeKeepsOptionLettersClean() {
        assertEquals(
            "苹果是什么颜色？选项 A，红色。选项 B，蓝色",
            SpeechText.forQuestion("苹果是什么颜色？", listOf("红色", "蓝色", "1", "2"), bilingual = true),
        )
        // Standalone algebra letters still read clearly in Chinese math phrasing.
        assertEquals("艾克斯 小于负三", SpeechText.forTts("x < -3", bilingual = true))
    }

    /** Character-recognition stems with tone-marked pinyin map to unambiguous homophones. */
    @Test fun toneMarkedPinyinInStemsIsSpoken() {
        assertEquals("下面哪个字读作掌？", SpeechText.forStem("下面哪个字读作「zhǎng」？"))
        assertEquals("下面哪个字读屋，是乌鸦的乌？", SpeechText.forStem("下面哪个字读「wū」，是乌鸦的乌？"))
        assertEquals("下面哪个字读作月？", SpeechText.forStem("下面哪个字读作「yuè」？"))
        // Pinyin-choice options stay unspoken so the answer is never given away.
        assertFalse(SpeechText.isSpeakableOption("píng guǒ"))
    }

    /** Standalone "2" before a classifier reads "两", while ordinals and multi-digit numbers keep "二". */
    @Test fun twoBeforeClassifierReadsAsLiang() {
        assertEquals("小明有两个苹果，又得到四个", SpeechText.forTts("小明有2个苹果，又得到4个"))
        assertEquals("长方形有两条长边", SpeechText.forTts("长方形有2条长边"))
        assertEquals("十二个苹果", SpeechText.forTts("12个苹果"))
        assertEquals("排在第二位", SpeechText.forTts("排在第2位"))
    }

    /** Prose parentheticals turn into natural pauses instead of "括号…括号"; algebra keeps "括号". */
    @Test fun proseParentheticalsBecomePausesWhileAlgebraKeepsBrackets() {
        assertEquals("我们吃的土豆，马铃薯，是植物的哪个部分？", SpeechText.forTts("我们吃的土豆（马铃薯）是植物的哪个部分？"))
        assertEquals("斑马线，人行横道", SpeechText.forTts("斑马线（人行横道）"))
        assertEquals("括号二加三括号乘二等于？", SpeechText.forTts("(2 + 3) × 2 = ?"))
    }

    /** Fill-in-the-blank runs of underscores are spoken once, not repeated per character. */
    @Test fun repeatedUnderscoresSpeakOnce() {
        assertEquals("上学前，我要自己整理横线。", SpeechText.forTts("上学前，我要自己整理___。"))
    }

    @Test fun absoluteValueBarsAreSpoken() {
        assertEquals("负十二的绝对值等于？", SpeechText.forTts("|-12| = ?"))
        assertEquals("艾克斯减三的绝对值", SpeechText.forTts("|x - 3|"))
    }

    @Test fun wrongAnswerExplanationAvoidsRepeatingAnswerWhenAlreadyPresent() {
        val direct = Question("a7-1", 7, "数学", "14 + 13 - 12 = ?", listOf("13", "17", "5", "15"), 3, "正确答案是15。")
        assertEquals("回答错误。正确答案是十五。", SpeechText.forExplanation(direct))

        val namedInProse = Question("o7-1", 7, "应用", "一共有多少人？", listOf("11人", "10人", "6人", "8人"), 3, "把两部分人数合起来：6 + 2 = 8人。")
        assertEquals("回答错误。把两部分人数合起来，六加二等于八人。", SpeechText.forExplanation(namedInProse))
    }

    @Test fun wrongAnswerExplanationNamesCorrectOptionLetterWhenExplanationOmitsIt() {
        val unitRule = Question("a10-1", 10, "数学", "2千克等于多少克？", listOf("2000", "200", "20", "1000"), 0, "1千克=1000克。")
        assertEquals("回答错误，正确答案是选项诶。一千克等于一千克。", SpeechText.forExplanation(unitRule, bilingual = false))
        assertEquals("回答错误，正确答案是选项 A。一千克等于一千克。", SpeechText.forExplanation(unitRule, bilingual = true))
    }

    @Test fun wrongAnswerExplanationFallsBackCleanlyWhenBlank() {
        val customMath = Question("u-1", 7, "数学", "1 + 2 = ?", listOf("2", "3", "4", "5"), 1, "", custom = true)
        assertEquals("回答错误，正确答案是选项必，三。", SpeechText.forExplanation(customMath))

        val customEnglish = Question("u-2", 9, "英语", "苹果英文？", listOf("apple", "book", "cat", "dog"), 0, "", custom = true)
        assertEquals("回答错误，正确答案是选项诶。", SpeechText.forExplanation(customEnglish))
    }
}
