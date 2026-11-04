package dev.plume.quiz

import android.app.Activity
import android.app.AlertDialog
import android.content.ActivityNotFoundException
import android.content.Intent
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.StateListDrawable
import android.os.Bundle
import android.provider.Settings
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import java.text.DateFormat
import java.util.Date

/** Parent-facing settings, reachable from the launcher's settings panel. */
class QuizSettingsActivity : Activity() {
    private lateinit var prefs: QuizPrefs
    private lateinit var ageButton: Button
    private lateinit var categoryButton: Button
    private lateinit var intervalButton: Button
    private lateinit var enabledButton: Button
    private lateinit var nextText: TextView
    private lateinit var ownerText: TextView
    private var selectedAge = 7
    private var selectedInterval = 30
    private var selectedEnabled = true
    private var selectedCategories: MutableSet<String> = mutableSetOf()
    private var bank: QuestionBank? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        prefs = QuizPrefs(this)
        selectedAge = prefs.age
        selectedInterval = INTERVALS.minByOrNull { kotlin.math.abs(it - prefs.intervalMinutes) } ?: 30
        selectedEnabled = prefs.enabled
        selectedCategories = prefs.categories.toMutableSet()
        setContentView(createContent())
        QuizDeviceOwner.configure(this)
        QuizScheduler.ensureScheduled(this)
        refresh()
    }

    override fun onResume() {
        super.onResume()
        if (::ownerText.isInitialized) refresh()
    }

    private fun createContent(): View {
        val scroll = ScrollView(this).apply {
            isFillViewport = true
            setBackgroundColor(Color.rgb(16, 42, 67))
        }
        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(64), dp(30), dp(64), dp(36))
        }
        content.addView(label("儿童定时问答", 34f, Color.WHITE))
        content.addView(label("5–14 岁离线题库，每个年龄 800 题。答错自动换题，答对后返回。", 18f, Color.rgb(184, 199, 217)), margins(bottom = 18))
        content.addView(label("儿童年龄", 18f, Color.rgb(255, 209, 102)))
        ageButton = actionButton("") { selectedAge = if (selectedAge == 14) 5 else selectedAge + 1; refreshChoices() }
        installStepper(ageButton, { selectedAge = (selectedAge - 1).coerceAtLeast(5); refreshChoices() }, { selectedAge = (selectedAge + 1).coerceAtMost(14); refreshChoices() })
        content.addView(ageButton, margins(bottom = 12))
        content.addView(label("题目分类（多选，不选为全部）", 18f, Color.rgb(255, 209, 102)))
        categoryButton = actionButton("") { pickCategories() }
        content.addView(categoryButton, margins(bottom = 12))
        content.addView(label("答题时间间隔", 18f, Color.rgb(255, 209, 102)))
        intervalButton = actionButton("") { stepInterval(1) }
        installStepper(intervalButton, { stepInterval(-1) }, { stepInterval(1) })
        content.addView(intervalButton, margins(bottom = 12))
        content.addView(label("定时开关", 18f, Color.rgb(255, 209, 102)))
        enabledButton = actionButton("") { selectedEnabled = !selectedEnabled; refreshChoices() }
        content.addView(enabledButton, margins(bottom = 14))
        nextText = label("", 17f, Color.WHITE)
        ownerText = label("", 16f, Color.rgb(184, 199, 217))
        content.addView(nextText, margins(bottom = 4))
        content.addView(ownerText, margins(bottom = 18))
        content.addView(actionButton("保存并重新计时") {
            prefs.save(selectedAge, selectedInterval, selectedEnabled, selectedCategories)
            QuizScheduler.reschedule(this)
            QuizDeviceOwner.configure(this)
            refresh()
            Toast.makeText(this, "设置已保存", Toast.LENGTH_SHORT).show()
        }, margins(bottom = 10))
        content.addView(actionButton("立即测试一题") {
            startActivity(Intent(this, QuizActivity::class.java))
        }, margins(bottom = 10))
        content.addView(actionButton("设为或更改默认电视桌面") {
            try {
                startActivity(Intent(Settings.ACTION_HOME_SETTINGS))
            } catch (_: ActivityNotFoundException) {
                Toast.makeText(this, "请在系统设置中选择默认主屏幕应用", Toast.LENGTH_LONG).show()
            }
        }, margins(bottom = 10))
        content.addView(actionButton("设备所有者配置说明") {
            val component = "$packageName/${QuizDeviceAdminReceiver::class.java.name}"
            AlertDialog.Builder(this)
                .setTitle("增强锁定模式")
                .setMessage("Device Owner 模式可限制答题页的 Home 和退出导航，但不是 root。需在未配置其他设备所有者的设备上，通过 ADB 执行：\n\nadb shell dpm set-device-owner $component\n\n部分电视要求先移除账号或恢复出厂设置。设置前请备份数据，并先确认新桌面能够正常使用。")
                .setPositiveButton("知道了", null)
                .show()
        })
        content.addView(label("普通模式：可替换默认桌面并定时答题，但系统主页键限制因电视而异。\n增强模式：Device Owner + Lock Task，答对后自动解除锁定。", 15f, Color.rgb(184, 199, 217)), margins(top = 18))
        scroll.addView(content, ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        return scroll
    }

    private fun stepInterval(direction: Int) {
        val index = INTERVALS.indexOf(selectedInterval).coerceAtLeast(0)
        selectedInterval = INTERVALS[(index + direction + INTERVALS.size) % INTERVALS.size]
        refreshChoices()
    }

    private fun installStepper(button: Button, left: () -> Unit, right: () -> Unit) {
        button.setOnKeyListener { _, code, event ->
            if (event.action != KeyEvent.ACTION_DOWN) return@setOnKeyListener false
            when (code) {
                KeyEvent.KEYCODE_DPAD_LEFT -> { left(); true }
                KeyEvent.KEYCODE_DPAD_RIGHT -> { right(); true }
                else -> false
            }
        }
    }

    /** Category multi-select: the bank loads on a worker thread, then a checked dialog does the picking. */
    private fun pickCategories() {
        val b = bank ?: QuestionBank(applicationContext).also { bank = it }
        Toast.makeText(this, "正在读取题库分类…", Toast.LENGTH_SHORT).show()
        Thread {
            val cats = b.categories(selectedAge)
            runOnUiThread {
                if (isFinishing || isDestroyed) return@runOnUiThread
                if (cats.isEmpty()) {
                    Toast.makeText(this, "$selectedAge 岁题库暂无分类", Toast.LENGTH_SHORT).show()
                    return@runOnUiThread
                }
                val checked = BooleanArray(cats.size) { cats[it] in selectedCategories }
                AlertDialog.Builder(this)
                    .setTitle("选择题目分类（不选为全部）")
                    .setMultiChoiceItems(cats.toTypedArray(), checked) { _, which, isChecked -> checked[which] = isChecked }
                    .setPositiveButton("确定") { _, _ ->
                        selectedCategories = cats.filterIndexed { i, _ -> checked[i] }.toMutableSet()
                        refreshChoices()
                    }
                    .setNeutralButton("全部") { _, _ ->
                        selectedCategories.clear()
                        refreshChoices()
                    }
                    .setNegativeButton("取消", null)
                    .show()
            }
        }.start()
    }

    private fun refreshChoices() {
        ageButton.text = "$selectedAge 岁  （左右键调整）"
        categoryButton.text = if (selectedCategories.isEmpty()) {
            "全部分类  （点击多选）"
        } else {
            "已选 ${selectedCategories.size} 类：${selectedCategories.joinToString("、").take(28)}"
        }
        intervalButton.text = "$selectedInterval 分钟  （左右键调整）"
        enabledButton.text = if (selectedEnabled) "已开启" else "已停用"
    }

    private fun refresh() {
        refreshChoices()
        val next = prefs.nextTriggerAt
        nextText.text = when {
            !prefs.enabled -> "下次答题：已停用"
            next <= 0L -> "下次答题：尚未安排"
            else -> "下次答题：${DateFormat.getDateTimeInstance().format(Date(next))}"
        }
        ownerText.text = if (QuizDeviceOwner.isOwner(this)) {
            "权限状态：设备所有者已启用，将使用系统锁定任务"
        } else {
            "权限状态：普通模式（可通过 ADB 配置增强锁定）"
        }
    }

    private fun label(value: String, size: Float, color: Int) = TextView(this).apply {
        text = value
        textSize = size
        setTextColor(color)
        setLineSpacing(0f, 1.15f)
    }

    private fun actionButton(value: String, action: () -> Unit) = Button(this).apply {
        text = value
        textSize = 19f
        setTextColor(Color.rgb(16, 42, 67))
        isAllCaps = false
        gravity = Gravity.CENTER_VERTICAL or Gravity.START
        minHeight = dp(58)
        setPadding(dp(22), 0, dp(22), 0)
        background = StateListDrawable().apply {
            addState(intArrayOf(android.R.attr.state_focused), buttonBackground(Color.rgb(255, 209, 102)))
            addState(intArrayOf(), buttonBackground(Color.WHITE))
        }
        setOnClickListener { action() }
    }

    private fun buttonBackground(color: Int) = GradientDrawable().apply {
        setColor(color)
        cornerRadius = dp(14).toFloat()
    }

    private fun margins(top: Int = 0, bottom: Int = 0) = LinearLayout.LayoutParams(
        ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT,
    ).apply {
        topMargin = dp(top)
        bottomMargin = dp(bottom)
    }

    private fun dp(value: Int) = (value * resources.displayMetrics.density).toInt()

    companion object {
        private val INTERVALS = listOf(1, 5, 10, 15, 20, 30, 45, 60, 90, 120)
    }
}
