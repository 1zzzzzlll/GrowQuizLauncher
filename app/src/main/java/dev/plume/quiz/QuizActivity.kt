package dev.plume.quiz

import android.app.Activity
import android.content.Intent
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.StateListDrawable
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.speech.tts.TextToSpeech
import java.util.Locale
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.view.WindowInsets
import android.view.WindowInsetsController
import android.view.WindowManager
import android.widget.Button
import android.widget.FrameLayout
import android.widget.GridLayout
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast

/** Full-screen, remote-first quiz. A wrong answer advances to a different question. */
class QuizActivity : Activity() {
    private val ui = Handler(Looper.getMainLooper())
    private lateinit var questionText: TextView
    private lateinit var progressText: TextView
    private lateinit var feedbackText: TextView
    private lateinit var answerButtons: List<Button>
    private var bank: QuestionBank? = null
    private var current: Question? = null
    private var solved = false
    private var changing = false
    private var lockActive = false

    // GrowQuiz: the question is read aloud for children who cannot read yet, then repeated every 20 seconds.
    private var tts: TextToSpeech? = null
    private var ttsReady = false
    private var ttsNoted = false // the "no TTS engine" toast shows once
    private val speakLoop: Runnable = object : Runnable {
        override fun run() {
            speakQuestion()
            ui.postDelayed(this, READ_REPEAT_MS)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        setContentView(createContent())
        immersive()
        if (QuizDeviceOwner.configure(this)) {
            startLockTask()
            lockActive = true
        }
        Thread {
            val loaded = QuestionBank(applicationContext)
            runOnUiThread {
                if (isFinishing || isDestroyed) return@runOnUiThread
                bank = loaded
                showNextQuestion()
            }
        }.start()
        tts = TextToSpeech(applicationContext) { status ->
            val engine = tts
            if (status != TextToSpeech.SUCCESS || engine == null) {
                android.util.Log.w("GrowQuiz", "tts init failed: $status")
                runOnUiThread { ttsMissing() }
                return@TextToSpeech
            }
            // zh-CN first, then plain Chinese, then the device's own locale: an engine that only has
            // the latter still beats silence, and some engines miss the Chinese data until installed.
            val ok = listOf(Locale.SIMPLIFIED_CHINESE, Locale.CHINESE, Locale.getDefault())
                .any { l -> engine.setLanguage(l) >= TextToSpeech.LANG_AVAILABLE }
            ttsReady = ok
            android.util.Log.i("GrowQuiz", "tts ready=$ok lang=${engine.language}")
            if (!ok) runOnUiThread { ttsMissing() }
            // The bank may already be showing a question by the time the engine is up.
            runOnUiThread { if (current != null && !solved && !isFinishing) startReading() }
        }
    }

    /** No TTS engine (or no usable language) on this device: say so once, instead of reading nothing in silence. */
    private fun ttsMissing() {
        if (isFinishing || ttsNoted) return
        if (!QuizPrefs(this).voice) return // narration is off anyway: no engine toast would only confuse
        ttsNoted = true
        Toast.makeText(this, "本机没有可用的语音（TTS）引擎，无法朗读题目", Toast.LENGTH_LONG).show()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        if (solved) {
            solved = false
            changing = false
            bank?.let { showNextQuestion() }
        }
    }

    private fun createContent(): View {
        val root = FrameLayout(this).apply { setBackgroundColor(Color.rgb(16, 42, 67)) }
        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(dp(52), dp(28), dp(52), dp(24))
        }
        progressText = text("成长问答 · 正在准备题目", 18f, Color.rgb(255, 209, 102)).apply {
            gravity = Gravity.CENTER
        }
        questionText = text("请稍候…", 32f, Color.WHITE).apply {
            gravity = Gravity.CENTER
            minHeight = dp(118)
            maxLines = 4
        }
        feedbackText = text("用遥控器方向键选择，按确认键作答", 18f, Color.rgb(184, 199, 217)).apply {
            gravity = Gravity.CENTER
            minHeight = dp(48)
        }
        val grid = GridLayout(this).apply {
            columnCount = 2
            rowCount = 2
        }
        answerButtons = List(4) { index ->
            Button(this).apply {
                textSize = 21f
                setTextColor(Color.rgb(16, 42, 67))
                isAllCaps = false
                gravity = Gravity.CENTER
                minHeight = dp(88)
                background = answerBackground()
                setOnClickListener { answer(index) }
                setOnFocusChangeListener { view, focused ->
                    view.animate().scaleX(if (focused) 1.025f else 1f).scaleY(if (focused) 1.025f else 1f).setDuration(100).start()
                }
                grid.addView(this, GridLayout.LayoutParams().apply {
                    width = 0
                    height = ViewGroup.LayoutParams.WRAP_CONTENT
                    columnSpec = GridLayout.spec(GridLayout.UNDEFINED, 1f)
                    setMargins(dp(10), dp(10), dp(10), dp(10))
                })
            }
        }
        content.addView(progressText)
        content.addView(questionText, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 0.85f))
        content.addView(grid, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1.35f))
        content.addView(feedbackText, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        root.addView(content, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        return root
    }

    private fun showNextQuestion() {
        val quiz = bank ?: return
        changing = false
        val prefs = QuizPrefs(this)
        val next = quiz.next(prefs.age, prefs.categories, current?.id)
        current = next
        progressText.text = "${next.age} 岁 · ${next.category} · 答对后即可返回"
        questionText.text = next.prompt
        feedbackText.text = "请选择正确答案"
        feedbackText.setTextColor(Color.rgb(184, 199, 217))
        answerButtons.forEachIndexed { index, button ->
            button.text = "${'A' + index}  ${next.options[index]}"
            button.isEnabled = true
            button.backgroundTintList = null
        }
        answerButtons.first().requestFocus()
        startReading()
    }

    /** Reads the current question and its options aloud, then keeps repeating every 20 seconds. */
    private fun startReading() {
        stopReading()
        if (!QuizPrefs(this).voice) return // the parents turned narration off in the web console
        speakLoop.run()
    }

    private fun stopReading() {
        ui.removeCallbacks(speakLoop)
        try { tts?.stop() } catch (e: Exception) {}
    }

    private fun speakQuestion() {
        val q = current ?: return
        val engine = tts ?: return
        if (!ttsReady) return
        val text = buildString {
            append("题目：").append(q.prompt)
            q.options.forEachIndexed { i, option -> append("。选项").append('A' + i).append("：").append(option) }
        }
        try { engine.speak(text, TextToSpeech.QUEUE_FLUSH, null, "quiz_question") } catch (e: Exception) {}
    }

    private fun answer(index: Int) {
        val question = current ?: return
        if (changing || solved) return
        changing = true
        stopReading() // the child answered; the next question (or the exit) restarts narration as needed
        answerButtons.forEach { it.isEnabled = false }
        if (QuizRules.isCorrect(question, index)) {
            solved = true
            bank?.registerCorrect(question.id) // mastered questions retire themselves after enough correct answers
            answerButtons[index].backgroundTintList = ColorStateList.valueOf(Color.rgb(46, 158, 107))
            feedbackText.text = "回答正确！${question.explanation}"
            feedbackText.setTextColor(Color.rgb(160, 235, 190))
            ui.postDelayed({ finishAfterCorrect() }, 1_300L)
        } else {
            answerButtons[index].backgroundTintList = ColorStateList.valueOf(Color.rgb(217, 83, 79))
            feedbackText.text = "这题不对，换一题再试试"
            feedbackText.setTextColor(Color.rgb(255, 180, 170))
            ui.postDelayed({ if (!isFinishing) showNextQuestion() }, 750L)
        }
    }

    private fun finishAfterCorrect() {
        justFinished = true // MainActivity skips the admin prompt right after a quiz
        QuizPrefs(this).bootPending = false // the boot quiz is owed until a question is answered
        if (lockActive) {
            stopLockTask()
            lockActive = false
        }
        finish()
    }

    // TV remotes still dispatch the back key; gesture-back migration would require AndroidX.
    @android.annotation.SuppressLint("GestureBackNavigation")
    override fun onBackPressed() {
        feedbackText.text = "答对一题后才能退出"
    }

    override fun onUserLeaveHint() {
        super.onUserLeaveHint()
        // Best-effort recovery for ordinary installations; Device Owner uses real lock-task enforcement.
        if (!solved && !lockActive) {
            ui.postDelayed({
                if (!solved && !isFinishing) {
                    startActivity(Intent(this, QuizActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_REORDER_TO_FRONT))
                }
            }, 250L)
        }
    }

    private fun immersive() {
        if (Build.VERSION.SDK_INT >= 30) {
            window.setDecorFitsSystemWindows(false)
            window.insetsController?.let {
                it.hide(WindowInsets.Type.systemBars())
                it.systemBarsBehavior = WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            }
        } else {
            @Suppress("DEPRECATION")
            window.decorView.systemUiVisibility = (
                View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY or View.SYSTEM_UI_FLAG_FULLSCREEN or
                    View.SYSTEM_UI_FLAG_HIDE_NAVIGATION or View.SYSTEM_UI_FLAG_LAYOUT_STABLE or
                    View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN or View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
                )
        }
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) immersive()
    }

    override fun onDestroy() {
        ui.removeCallbacksAndMessages(null)
        stopReading()
        try { tts?.shutdown() } catch (e: Exception) {}
        tts = null
        super.onDestroy()
    }

    private fun text(value: String, size: Float, color: Int) = TextView(this).apply {
        text = value
        textSize = size
        setTextColor(color)
    }

    private fun answerBackground() = StateListDrawable().apply {
        addState(intArrayOf(android.R.attr.state_focused), rounded(Color.rgb(255, 248, 231), Color.rgb(255, 209, 102), 4))
        addState(intArrayOf(), rounded(Color.WHITE, Color.rgb(184, 199, 217), 2))
    }

    private fun rounded(fill: Int, stroke: Int, width: Int) = GradientDrawable().apply {
        setColor(fill)
        cornerRadius = dp(18).toFloat()
        setStroke(dp(width), stroke)
    }

    private fun dp(value: Int) = (value * resources.displayMetrics.density).toInt()

    companion object {
        private const val READ_REPEAT_MS = 20_000L // after the first reading, repeat question + options every 20 s

        /** Set when a quiz is completed; MainActivity reads (and clears) it to skip the admin prompt then. */
        @Volatile var justFinished = false
    }
}
