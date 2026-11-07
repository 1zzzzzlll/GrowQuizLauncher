package dev.plume.quiz

/**
 * Rewrites a question into text a plain TTS engine can speak without stalling.
 *
 * A Chinese question holds things most engines cannot say: "12 + 8 = ?" loses the signs and comes out as
 * "十二 八", "(x - 3)(x - 4)" loses both the brackets and the minus, "6/8" loses the fraction bar. Every
 * change here is about pronunciation only: the child still reads the screen, this decides what the voice
 * says.
 *
 * Reading is also an information leak: an option of one character, one number or one letter gives the
 * answer away the moment it is spoken, so [forQuestion] leaves those out entirely and reads the stem
 * alone. See [isSpeakableOption].
 *
 * Kept free of Android types so it can be tested on the JVM.
 */
object SpeechText {

    /**
     * Whole words first, longest first, before any single character is rewritten: "cm" must not be eaten
     * by a shorter rule, and "H₂O" must stay a word instead of "H 二 O".
     */
    private val PHRASES = listOf(
        "m/s²" to "米每二次方秒",
        "km/h" to "千米每小时",
        "m/s" to "米每秒",
        "m³" to "立方米",
        "H₂SO₄" to "硫酸",
        "H₂CO₃" to "碳酸",
        "H₂O" to "水",
        "CO₂" to "二氧化碳",
        "SO₂" to "二氧化硫",
        "NO₂" to "二氧化氮",
        "O₃" to "臭氧",
        "O₂" to "氧气",
        "CH₄" to "甲烷",
        "km" to "千米",
        "dm" to "分米",
        "cm" to "厘米",
        "mm" to "毫米",
        "kg" to "千克",
        "mL" to "毫升",
        "ml" to "毫升",
        "m²" to "平方米",
    )

    /** Single characters, in this order: the two-character operators must run before "=" and "<". */
    private val SYMBOLS = listOf(
        "÷" to "除以",
        "×" to "乘",
        "✕" to "乘",
        "*" to "乘",
        "＋" to "加",
        "+" to "加",
        "≈" to "约等于",
        "≠" to "不等于",
        "≤" to "小于等于",
        "≥" to "大于等于",
        "＝" to "等于",
        "=" to "等于",
        "<" to "小于",
        ">" to "大于",
        "√" to "根号",
        "π" to "派",
        "℃" to "摄氏度",
        "°" to "度",
        "²" to "平方",
        "³" to "立方",
        "→" to "到",
        "←" to "从",
        "□" to "方块",
        "■" to "方块",
        "▢" to "方块",
        "◻" to "方块",
        "△" to "三角形",
        "▲" to "三角形",
        "○" to "圆圈",
        "◎" to "圆圈",
        "●" to "圆点",
        "☆" to "星",
        "★" to "星",
        "＿" to "横线",
        "_" to "横线",
        "·" to "",
        "※" to "",
    )

    private val DASH = Regex("[—–]{1,2}")
    /** "100%" must be 百分之一百, not 一百百分之. Runs before the digits change into words. */
    private val PERCENT = Regex("([0-9]+(?:\\.[0-9]+)?)\\s*%")
    /** "10^5" is 十的五次方; the digits inside are read by the number rule below. */
    private val POWER = Regex("\\^\\s*([0-9]+)")
    /** "6/8" is 八分之六: a slash between two numbers is always a fraction bar. */
    private val FRACTION = Regex("([0-9]+)\\s*/\\s*([0-9]+)")
    /**
     * "18:27" is a ratio, not a clock and not a list: read as 十八比二十七. A colon between two numbers
     * must be settled before the comma rule turns every colon into a pause.
     */
    private val RATIO = Regex("([0-9]+)\\s*[:：]\\s*([0-9]+)")
    /** A slash between two Chinese words is "or"; between anything else it is a division. */
    private val CJK_SLASH = Regex("(?<=[\\u4e00-\\u9fff])\\s*/\\s*(?=[\\u4e00-\\u9fff])")
    /** "³√8" is 三次根号八; the index belongs in front of 根号, not after it. */
    private val ROOT_INDEX = Regex("([²³])\\s*(?=√)")
    /** A pair of brackets with nothing inside is a blank to fill in, and must be heard as one word. */
    private val BLANK_PAIR = Regex("[（(【\\[〈][\\s\\u3000]*[）)】\\]〉]")
    private val OPEN_BRACKET = Regex("[（(【\\[〈]")
    private val CLOSE_BRACKET = Regex("[）)】\\]〉]")
    /** "(x-3)(x-4)" turns both brackets into 括号: said twice in a row it reads as a stutter. */
    private val DOUBLE_BRACKET = Regex("(?:括号){2,}")
    /** A dot between two digits is a decimal point and must stay: 3.5 is not "三。五". */
    private val DOT = Regex("(?<![0-9])[.．]|[.．](?![0-9])")
    // 《》 and the quote marks are read by the eye; dropping them does not lose the words between them.
    private val BOOK_TITLES = Regex("[《》〈〉「」『』｛｝{}“”\"'‘’]")
    private val COMMA_LIKE = Regex("[，,、；;：:]")
    private val QUESTION = Regex("[？?]")
    private val EXCLAIM = Regex("[！!]")
    private val TILDE = Regex("[～~]")
    private val ELLIPSIS = Regex("…+")
    private val SPACES = Regex("[\\s\\u3000]+")
    private val REPEATED_COMMA = Regex("，{2,}")
    private val REPEATED_PERIOD = Regex("。{2,}")
    // The stem keeps its own ？ or ！, and the next segment follows right after: "是偶数？。选项A" would
    // give the engine an empty sentence to read out.
    private val PERIOD_AFTER_STOP = Regex("([？!！])。")
    private val SPACE_BEFORE_PUNCT = Regex(" +([，。？！、])")
    private val SPACE_AFTER_PUNCT = Regex("(?<=[，。？！、]) +")
    /** "20 加 9" reads with a pause on each space; a spoken operator is not a word gap. */
    private val SPACE_AROUND_OPERATOR = Regex(" *(?=[加减乘除以等于])|(?<=[加减乘除以等于]) *")
    /**
     * A minus between two operands is subtraction; in front of a number it is a negative sign. Both are
     * settled while the operands are still Latin, because "x" has become a word by the end.
     */
    private val MINUS_BETWEEN = Regex("(?<=[0-9A-Za-z）)】])\\s*[-−－]\\s*(?=[0-9A-Za-z（(【□▢■])")
    private val LEADING_MINUS = Regex("(?<![0-9A-Za-z）)】])[-−－](?=[0-9])")
    private val REMAINING_MINUS = Regex("[-−－]")
    /**
     * A Chinese model reads "24" as a number only when it is spelled in Chinese: fed as digits it can
     * drop the token and leave a hole in the sentence ("减  等于"). Four digits is the range a question
     * uses; longer runs are dates and stay as they are.
     */
    private val SHORT_NUMBER = Regex("(?<![\\d.])[0-9]{1,4}(?![\\d.])")
    /** "4.6" is 四点六. Left as digits, a decimal is the token an engine most often drops. */
    private val DECIMAL = Regex("([0-9]+)[.．]([0-9]+)")
    private val DIGITS = "零一二三四五六七八九"
    /**
     * A lone Latin letter has no sound in a Chinese model: "x - 3" comes out as "减 三" and the child
     * cannot tell what is being subtracted. Each letter is read the way a Chinese speaker names it.
     * Only a letter standing on its own changes - "TV" and "HIV" keep their letters.
     */
    private val LONE_LETTER = Regex("(?<![A-Za-z])[A-Za-z](?![A-Za-z])")
    private val LETTER_SOUND = mapOf(
        'A' to "诶", 'B' to "必", 'C' to "西", 'D' to "迪", 'E' to "伊", 'F' to "艾弗",
        'G' to "吉", 'H' to "艾尺", 'I' to "艾", 'J' to "杰", 'K' to "开", 'L' to "艾勒",
        'M' to "艾姆", 'N' to "恩", 'O' to "欧", 'P' to "皮", 'Q' to "扣", 'R' to "啊",
        'S' to "艾斯", 'T' to "梯", 'U' to "优", 'V' to "维", 'W' to "达不溜", 'X' to "艾克斯",
        'Y' to "歪", 'Z' to "贼",
    )
    private val SUBSCRIPT = mapOf(
        '₀' to "零", '₁' to "一", '₂' to "二", '₃' to "三", '₄' to "四",
        '₅' to "五", '₆' to "六", '₇' to "七", '₈' to "八", '₉' to "九",
    )
    // Letters only, not digits: "有 3 个" reads fine either way, but a digit cut loose from its Chinese
    // neighbours makes some engines switch to an English reading ("12" as "one two").
    private val CJK_THEN_LATIN = Regex("([\\u4e00-\\u9fff])([A-Za-z])")
    private val LATIN_THEN_CJK = Regex("([A-Za-z])([\\u4e00-\\u9fff])")

    /**
     * Whether an option may be read out at all.
     *
     * An option that is one character, one number, one letter or a run of English letters *is* the
     * answer: hearing "三", "12" or "B" tells the child what to press without understanding the
     * question. Those stay on screen for the eye and are left out of the spoken sentence. What is
     * left is a short phrase in Chinese, which only helps once the question has been understood.
     */
    fun isSpeakableOption(option: String): Boolean {
        val t = option.trim().trim('。', '，', '、', '.', ',', '：', ':')
        if (t.length < 2) return false // 一个字、一个数字、一个字母
        val core = t.filter { !it.isWhitespace() && it !in "。，、.,：:·" }
        if (core.none { it.code in 0x4E00..0x9FFF }) return false // 纯数字、纯英文、纯符号
        // "3年" and "3.5米" are a number with a unit: the same leak as a bare "3", one word longer.
        if (core.count { it.code in 0x4E00..0x9FFF } == 1 && core.any { it.isDigit() }) return false
        return true
    }

    /** The stem alone, ready to be spoken the moment it appears. */
    fun forStem(prompt: String): String = forTts(prompt)

    /** The options worth hearing, ready to be queued behind the stem; empty when none is. */
    fun forOptions(options: List<String>): String {
        val parts = options.mapIndexedNotNull { i, option ->
            if (isSpeakableOption(option)) "选项" + ('A' + i) + "：" + option else null
        }
        return if (parts.isEmpty()) "" else forTts(parts.joinToString("。"))
    }

    /** The whole question in one string, for tests and for anywhere without chunked playback. */
    fun forQuestion(prompt: String, options: List<String>): String {
        val stem = forStem(prompt)
        val rest = forOptions(options)
        return if (rest.isEmpty()) stem else PERIOD_AFTER_STOP.replace("$stem。$rest", "$1")
    }

    fun forTts(raw: String): String {
        var s = raw
        // A long dash is a pause, not a minus: settle it before the sign table runs.
        s = DASH.replace(s, "，")
        for ((from, to) in PHRASES) s = s.replace(from, to)
        s = PERCENT.replace(s) { "百分之" + it.groupValues[1] }
        s = POWER.replace(s) { "的" + it.groupValues[1] + "次方" }
        s = FRACTION.replace(s) { m -> m.groupValues[2] + "分之" + m.groupValues[1] }
        s = RATIO.replace(s) { m -> m.groupValues[1] + "比" + m.groupValues[2] }
        s = ROOT_INDEX.replace(s) { m -> if (m.value.startsWith("²")) "二次" else "三次" }
        // Signs are settled while the operands are still digits and letters: after the bracket rule they
        // would sit next to 括号, and after the number rule next to 十, and neither reads as a minus.
        s = MINUS_BETWEEN.replace(s, "减")
        s = LEADING_MINUS.replace(s, "负")
        s = REMAINING_MINUS.replace(s, "")
        // Brackets carry meaning in algebra and mark a blank in a fill-in question: say 括号 or 空白
        // instead of dropping them, which used to glue both sides of "(x-3)(x-4)" into one word.
        s = BLANK_PAIR.replace(s, "空白")
        s = OPEN_BRACKET.replace(s, "括号")
        s = CLOSE_BRACKET.replace(s, "括号")
        s = DOUBLE_BRACKET.replace(s, "括号")
        for ((from, to) in SYMBOLS) s = s.replace(from, to)
        s = BOOK_TITLES.replace(s, "")
        s = COMMA_LIKE.replace(s, "，")
        s = DOT.replace(s, "。")
        s = QUESTION.replace(s, "？")
        s = EXCLAIM.replace(s, "！")
        s = TILDE.replace(s, "到")
        s = ELLIPSIS.replace(s, "，")
        // A remaining slash divides. Between two Chinese words it is a rate ("340 米/秒" is 340 米每秒);
        // between letters it is a division ("k/x" is 开除以艾克斯), which "每" would get wrong.
        s = CJK_SLASH.replace(s, "每")
        s = s.replace("/", "除以")
        s = SPACES.replace(s, " ")
        s = REPEATED_COMMA.replace(s, "，")
        s = REPEATED_PERIOD.replace(s, "。")
        s = PERIOD_AFTER_STOP.replace(s, "$1")
        s = SPACE_BEFORE_PUNCT.replace(s, "$1")
        s = SPACE_AFTER_PUNCT.replace(s, "")
        s = SPACE_AROUND_OPERATOR.replace(s, "")
        s = DECIMAL.replace(s) { m ->
            val whole = m.groupValues[1]
            val head = if (whole.length <= 4) numberToChinese(whole.toInt())
            else whole.map { DIGITS[it - '0'] }.joinToString("")
            head + "点" + m.groupValues[2].map { DIGITS[it - '0'] }.joinToString("")
        }
        s = SHORT_NUMBER.replace(s) { m -> numberToChinese(m.value.toInt()) }
        s = LONE_LETTER.replace(s) { m -> LETTER_SOUND[m.value.uppercase()[0]] ?: m.value }
        s = SUBSCRIPT.entries.fold(s) { acc, (from, to) -> acc.replace(from.toString(), to) }
        // Latin gets a space on each side: engines otherwise glue "选项A" into one unknown word and skip it.
        // The spacing is invisible on screen; it only exists in the spoken string.
        s = CJK_THEN_LATIN.replace(s, "$1 $2")
        s = LATIN_THEN_CJK.replace(s, "$1 $2")
        return s.trim().trimStart('，', '。', '、', ' ')
    }

    /** 0..9999 the way a Chinese speaker says it: 24 → 二十四, 105 → 一百零五, 2026 → 二千零二十六. */
    fun numberToChinese(n: Int): String {
        if (n == 0) return "零"
        val units = arrayOf("", "十", "百", "千")
        val sb = StringBuilder()
        var pendingZero = false
        n.toString().reversed().forEachIndexed { i, c ->
            val d = c - '0'
            if (d == 0) {
                pendingZero = sb.isNotEmpty()
                return@forEachIndexed
            }
            if (pendingZero) {
                sb.insert(0, "零")
                pendingZero = false
            }
            sb.insert(0, units[i])
            sb.insert(0, DIGITS[d])
        }
        // 10 to 19 are spoken 十X, never 一十X: drop the leading 一, keep the 十.
        if (sb.length > 1 && sb[0] == '一' && sb[1] == '十') sb.deleteCharAt(0)
        return sb.toString()
    }
}
