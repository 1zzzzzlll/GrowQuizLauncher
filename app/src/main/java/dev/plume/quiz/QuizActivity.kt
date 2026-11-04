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
        val next = quiz.next(QuizPrefs(this).age, current?.id)
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
    }

    private fun answer(index: Int) {
        val question = current ?: return
        if (changing || solved) return
        changing = true
        answerButtons.forEach { it.isEnabled = false }
        if (QuizRules.isCorrect(question, index)) {
            solved = true
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
}
