package dev.plume.web

import android.content.Context
import android.content.Intent
import android.os.Handler
import android.os.Looper
import dev.plume.quiz.QuestionBank
import dev.plume.quiz.QuizActivity
import dev.plume.quiz.QuizDeviceOwner
import dev.plume.quiz.QuizPrefs
import dev.plume.quiz.QuizScheduler
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedInputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.net.NetworkInterface
import java.net.ServerSocket
import java.net.Socket
import java.net.URLDecoder
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Collections

/**
 * A small built-in admin console for the LAN: one page, password-protected, no external libraries.
 * Serves the control page and a few JSON endpoints. Runs while the launcher is alive.
 */
object WebAdminServer {
    private const val PREFS = "web_admin"
    private const val KEY_HASH = "password_hash"
    private const val KEY_SALT = "password_salt"
    private const val COOKIE = "session"
    private const val SESSION_MS = 24L * 60 * 60 * 1000
    private const val MIN_PASSWORD = 6

    private val sessions = HashMap<String, Long>()
    private val random = SecureRandom()
    private val main = Handler(Looper.getMainLooper())

    @Volatile private var server: ServerSocket? = null
    @Volatile private var running = false

    val port: Int get() = server?.localPort ?: 8081

    fun start(context: Context) {
        if (running) return
        val app = context.applicationContext
        server = try {
            ServerSocket(8081)
        } catch (_: IOException) {
            ServerSocket(0) // 8081 busy: any free port, the settings row shows it
        }
        running = true
        Thread({ serve(app) }, "web-admin").start()
    }

    fun stop() {
        running = false
        try {
            server?.close()
        } catch (_: IOException) {
        }
        server = null
        sessions.clear()
    }

    fun url(): String {
        val address = localIpAddress() ?: return "http://localhost:$port"
        return "http://$address:$port"
    }

    /** True once the family has set the admin password; the TV-side settings entries gate on this too. */
    fun hasPassword(context: Context): Boolean =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY_HASH, null) != null

    /** Checks a password against the stored salted hash (used by the on-TV unlock dialog). */
    fun verifyPassword(context: Context, password: String): Boolean {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val salt = prefs.getString(KEY_SALT, null) ?: return false
        val stored = prefs.getString(KEY_HASH, null) ?: return false
        return sha256(salt + password) == stored
    }

    @Volatile private var bank: QuestionBank? = null

    /** One bank per server run; mutations reload it, and disabled state lives in shared preferences. */
    private fun bank(context: Context): QuestionBank =
        bank ?: QuestionBank(context.applicationContext).also { bank = it }

    private fun serve(context: Context) {
        val socket = server ?: return
        while (running) {
            val client = try {
                socket.accept()
            } catch (_: IOException) {
                return
            }
            try {
                client.soTimeout = 15_000
                handle(client, context)
            } catch (_: Exception) {
            } finally {
                try {
                    client.close()
                } catch (_: IOException) {
                }
            }
        }
    }

    private fun handle(socket: Socket, context: Context) {
        val input = BufferedInputStream(socket.getInputStream())
        val out = socket.getOutputStream()
        val request = readLine(input) ?: return
        val method = request.substringBefore(' ').uppercase()
        val target = request.substringAfter(' ').substringBefore(' ')
        val path = target.substringBefore('?')
        val query = parseQuery(target.substringAfter('?', ""))
        val headers = HashMap<String, String>()
        while (true) {
            val line = readLine(input) ?: break
            if (line.isEmpty()) break
            val key = line.substringBefore(':').trim().lowercase()
            val value = line.substringAfter(':').trim()
            headers[key] = value
        }
        val body = if (method == "POST") readBody(input, headers["content-length"]?.toLongOrNull() ?: 0L) else ""

        when {
            path == "/" && method == "GET" -> {
                val page = context.assets.open("web_admin.html").use { it.readBytes() }.toString(Charsets.UTF_8)
                reply(out, 200, "text/html; charset=utf-8", page)
            }

            path == "/api/status" && method == "GET" -> {
                val token = cookie(headers)
                val authed = valid(token)
                val prefs = QuizPrefs(context)
                val json = if (authed) {
                    JSONObject()
                        .put("initialized", initialized(context))
                        .put("authenticated", true)
                        .put("age", prefs.age)
                        .put("interval", prefs.intervalMinutes)
                        .put("enabled", prefs.enabled)
                        .put("categories", JSONArray(prefs.categories.toList()))
                        .put("next", prefs.nextTriggerAt)
                        .put("owner", QuizDeviceOwner.isOwner(context))
                        .put("tts", dev.plume.quiz.TtsProbe.describe())
                } else {
                    JSONObject()
                        .put("initialized", initialized(context))
                        .put("authenticated", false)
                }
                reply(out, 200, "application/json; charset=utf-8", json.toString())
            }

            path == "/api/setup" && method == "POST" -> {
                if (initialized(context)) return reply(out, 400, "application/json; charset=utf-8", error("already initialized"))
                val password = JSONObject(body.ifEmpty { "{}" }).optString("password")
                if (password.length < MIN_PASSWORD) return reply(out, 400, "application/json; charset=utf-8", error("password too short"))
                val salt = randomHex(16)
                val hash = sha256(salt + password)
                context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
                    .putString(KEY_SALT, salt)
                    .putString(KEY_HASH, hash)
                    .commit()
                val token = newSession()
                reply(out, 200, "application/json; charset=utf-8", ok(), cookie = sessionCookie(token))
            }

            path == "/api/login" && method == "POST" -> {
                if (!initialized(context)) return reply(out, 400, "application/json; charset=utf-8", error("not initialized"))
                val password = JSONObject(body.ifEmpty { "{}" }).optString("password")
                val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                val salt = prefs.getString(KEY_SALT, null) ?: return reply(out, 500, "application/json; charset=utf-8", error("server error"))
                val stored = prefs.getString(KEY_HASH, null) ?: return reply(out, 500, "application/json; charset=utf-8", error("server error"))
                if (sha256(salt + password) != stored) return reply(out, 401, "application/json; charset=utf-8", error("wrong password"))
                val token = newSession()
                reply(out, 200, "application/json; charset=utf-8", ok(), cookie = sessionCookie(token))
            }

            path == "/api/logout" && method == "POST" -> {
                val token = cookie(headers)
                if (token != null) sessions.remove(token)
                reply(out, 200, "application/json; charset=utf-8", ok(), cookie = clearCookie())
            }

            path == "/api/password_change" && method == "POST" -> {
                if (!valid(cookie(headers))) return reply(out, 401, "application/json; charset=utf-8", error("not logged in"))
                val json = JSONObject(body.ifEmpty { "{}" })
                val old = json.optString("old")
                val fresh = json.optString("new")
                if (!verifyPassword(context, old)) return reply(out, 403, "application/json; charset=utf-8", error("wrong password"))
                if (fresh.length < MIN_PASSWORD) return reply(out, 400, "application/json; charset=utf-8", error("password too short"))
                val salt = randomHex(16)
                context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
                    .putString(KEY_SALT, salt)
                    .putString(KEY_HASH, sha256(salt + fresh))
                    .commit()
                reply(out, 200, "application/json; charset=utf-8", ok())
            }

            path == "/api/settings" && method == "POST" -> {
                if (!valid(cookie(headers))) return reply(out, 401, "application/json; charset=utf-8", error("not logged in"))
                val json = JSONObject(body.ifEmpty { "{}" })
                val age = json.optInt("age", -1)
                val interval = json.optInt("interval", -1)
                val enabled = json.optBoolean("enabled", true)
                if (age !in 5..14) return reply(out, 400, "application/json; charset=utf-8", error("invalid age"))
                if (interval !in 1..1440) return reply(out, 400, "application/json; charset=utf-8", error("invalid interval"))
                val prefs = QuizPrefs(context)
                val categories = json.optJSONArray("categories")?.let { arr ->
                    (0 until arr.length()).map { arr.getString(it) }.filter { it.isNotBlank() }.toSet()
                } ?: prefs.categories
                prefs.save(age, interval, enabled, categories)
                QuizScheduler.reschedule(context)
                QuizDeviceOwner.configure(context)
                reply(out, 200, "application/json; charset=utf-8", ok())
            }

            path == "/api/categories" && method == "GET" -> {
                if (!valid(cookie(headers))) return reply(out, 401, "application/json; charset=utf-8", error("not logged in"))
                val b = bank(context)
                val age = query["age"]?.toIntOrNull()
                val questions = b.list(age = age)
                val disabled = b.disabledIds()
                val arr = JSONArray()
                for (name in b.categories(age)) {
                    val inCategory = if (name == QuestionBank.CUSTOM_CATEGORY) {
                        questions.filter { it.custom }
                    } else {
                        questions.filter { it.category == name }
                    }
                    arr.put(
                        JSONObject()
                            .put("name", name)
                            .put("count", inCategory.size)
                            .put("disabled", inCategory.count { it.id in disabled }),
                    )
                }
                val json = JSONObject()
                    .put("ok", true)
                    .put("categories", arr)
                    .put("selected", JSONArray(QuizPrefs(context).categories.toList()))
                reply(out, 200, "application/json; charset=utf-8", json.toString())
            }

            path == "/api/questions" && method == "GET" -> {
                if (!valid(cookie(headers))) return reply(out, 401, "application/json; charset=utf-8", error("not logged in"))
                val b = bank(context)
                val age = query["age"]?.toIntOrNull()
                val category = query["category"]?.takeIf { it.isNotEmpty() }
                val onlyDisabled = when (query["state"]) {
                    "enabled" -> false
                    "disabled" -> true
                    else -> null
                }
                val items = b.list(age, category, onlyDisabled)
                val pageSize = query["pageSize"]?.toIntOrNull()?.coerceIn(1, 100)
                val requestedPage = query["page"]?.toIntOrNull()?.coerceAtLeast(1)
                val page: Int
                val paged: List<dev.plume.quiz.Question>
                if (pageSize != null && requestedPage != null) {
                    val lastPage = maxOf(1, (items.size + pageSize - 1) / pageSize)
                    page = requestedPage.coerceAtMost(lastPage)
                    paged = items.drop((page - 1) * pageSize).take(pageSize)
                } else {
                    page = 1
                    paged = items
                }
                val disabled = b.disabledIds()
                val arr = JSONArray()
                for (q in paged) {
                    arr.put(
                        JSONObject()
                            .put("id", q.id)
                            .put("age", q.age)
                            .put("category", q.category)
                            .put("question", q.prompt)
                            .put("options", JSONArray(q.options))
                            .put("answerIndex", q.answerIndex)
                            .put("explanation", q.explanation)
                            .put("custom", q.custom)
                            .put("overridden", b.isOverridden(q.id))
                            .put("correct", b.correctCount(q.id))
                            .put("enabled", q.id !in disabled),
                    )
                }
                val json = JSONObject()
                    .put("ok", true)
                    .put("total", items.size)
                    .put("page", page)
                    .put("pageSize", pageSize ?: items.size)
                    .put("questions", arr)
                reply(out, 200, "application/json; charset=utf-8", json.toString())
            }

            path == "/api/question/add" && method == "POST" -> {
                if (!valid(cookie(headers))) return reply(out, 401, "application/json; charset=utf-8", error("not logged in"))
                val (draft, failure) = parseDraft(body)
                if (draft == null) return reply(out, 400, "application/json; charset=utf-8", error(failure ?: "invalid question"))
                val id = bank(context).addCustom(draft.age, draft.category, draft.prompt, draft.options, draft.answerIndex, draft.explanation)
                reply(out, 200, "application/json; charset=utf-8", JSONObject().put("ok", true).put("id", id).toString())
            }

            path == "/api/question/update" && method == "POST" -> {
                if (!valid(cookie(headers))) return reply(out, 401, "application/json; charset=utf-8", error("not logged in"))
                val id = JSONObject(body.ifEmpty { "{}" }).optString("id").trim()
                if (id.isEmpty()) return reply(out, 400, "application/json; charset=utf-8", error("id required"))
                val (draft, failure) = parseDraft(body)
                if (draft == null) return reply(out, 400, "application/json; charset=utf-8", error(failure ?: "invalid question"))
                val b = bank(context)
                val target = b.list().firstOrNull { it.id == id }
                    ?: return reply(out, 404, "application/json; charset=utf-8", error("question not found"))
                // Custom questions are rewritten in place; bundled ones get an override so the original stays intact.
                val saved = if (target.custom) {
                    b.updateCustom(id, draft.age, draft.category, draft.prompt, draft.options, draft.answerIndex, draft.explanation)
                } else {
                    b.saveOverride(id, draft.age, draft.category, draft.prompt, draft.options, draft.answerIndex, draft.explanation)
                }
                if (!saved) return reply(out, 400, "application/json; charset=utf-8", error("update failed"))
                reply(out, 200, "application/json; charset=utf-8", ok())
            }

            path == "/api/question/revert" && method == "POST" -> {
                if (!valid(cookie(headers))) return reply(out, 401, "application/json; charset=utf-8", error("not logged in"))
                val id = JSONObject(body.ifEmpty { "{}" }).optString("id")
                if (!bank(context).removeOverride(id)) {
                    return reply(out, 400, "application/json; charset=utf-8", error("only overridden bundled questions can be reverted"))
                }
                reply(out, 200, "application/json; charset=utf-8", ok())
            }

            path == "/api/question/delete" && method == "POST" -> {
                if (!valid(cookie(headers))) return reply(out, 401, "application/json; charset=utf-8", error("not logged in"))
                val id = JSONObject(body.ifEmpty { "{}" }).optString("id")
                if (!bank(context).deleteCustom(id)) {
                    return reply(out, 400, "application/json; charset=utf-8", error("only custom questions can be deleted"))
                }
                reply(out, 200, "application/json; charset=utf-8", ok())
            }

            path == "/api/questions/state" && method == "POST" -> {
                if (!valid(cookie(headers))) return reply(out, 401, "application/json; charset=utf-8", error("not logged in"))
                val json = JSONObject(body.ifEmpty { "{}" })
                val idsJson = json.optJSONArray("ids")
                    ?: return reply(out, 400, "application/json; charset=utf-8", error("ids required"))
                val enabled = json.optBoolean("enabled", true)
                val ids = (0 until idsJson.length()).map { idsJson.getString(it) }
                bank(context).setDisabled(ids, !enabled)
                reply(out, 200, "application/json; charset=utf-8", JSONObject().put("ok", true).put("count", ids.size).toString())
            }

            path == "/api/questions/state_all" && method == "POST" -> {
                if (!valid(cookie(headers))) return reply(out, 401, "application/json; charset=utf-8", error("not logged in"))
                val json = JSONObject(body.ifEmpty { "{}" })
                val enabled = json.optBoolean("enabled", true)
                val age = if (json.has("age")) json.optInt("age") else null
                val category = json.optString("category").ifEmpty { null }
                bank(context).setAllDisabled(!enabled, age, category)
                reply(out, 200, "application/json; charset=utf-8", ok())
            }

            path == "/api/start" && method == "POST" -> {
                if (!valid(cookie(headers))) return reply(out, 401, "application/json; charset=utf-8", error("not logged in"))
                val intent = Intent(context, QuizActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                main.post { context.startActivity(intent) }
                reply(out, 200, "application/json; charset=utf-8", ok())
            }

            path == "/api/tts_test" && method == "POST" -> {
                if (!valid(cookie(headers))) return reply(out, 401, "application/json; charset=utf-8", error("not logged in"))
                val spoke = dev.plume.quiz.TtsProbe.speak("语音测试：宝贝你好，我是你的答题小助手")
                val json = JSONObject().put("ok", true).put("spoke", spoke)
                reply(out, 200, "application/json; charset=utf-8", json.toString())
            }

            else -> reply(out, if (method == "GET" || method == "POST") 404 else 405, "application/json; charset=utf-8", error("not found"))
        }
    }

    private fun initialized(context: Context): Boolean = hasPassword(context)

    /** Validated question fields shared by the add and update endpoints. */
    private data class QuestionDraft(
        val age: Int,
        val category: String,
        val prompt: String,
        val options: List<String>,
        val answerIndex: Int,
        val explanation: String,
    )

    /** Parses a question body; on failure returns null draft plus an error message. */
    private fun parseDraft(body: String): Pair<QuestionDraft?, String?> {
        val json = JSONObject(body.ifEmpty { "{}" })
        val age = json.optInt("age", -1)
        val category = json.optString("category").trim()
        val prompt = json.optString("question").trim()
        val optionsJson = json.optJSONArray("options")
        val answerIndex = json.optInt("answerIndex", -1)
        val explanation = json.optString("explanation").trim()
        val options = optionsJson?.let { arr -> List(arr.length()) { arr.optString(it).trim() } }.orEmpty()
        val failure = when {
            age !in 5..14 -> "invalid age"
            category.isEmpty() -> "category required"
            prompt.isEmpty() -> "question required"
            options.size != 4 || options.any { it.isEmpty() } || options.toSet().size != 4 -> "four distinct options required"
            answerIndex !in 0..3 -> "answer index out of range"
            else -> null
        }
        return if (failure != null) {
            null to failure
        } else {
            QuestionDraft(age, category, prompt, options, answerIndex, explanation) to null
        }
    }

    private fun parseQuery(raw: String): Map<String, String> {
        if (raw.isEmpty()) return emptyMap()
        return raw.split('&')
            .filter { it.contains('=') }
            .associate {
                URLDecoder.decode(it.substringBefore('='), Charsets.UTF_8.name()) to
                    URLDecoder.decode(it.substringAfter('='), Charsets.UTF_8.name())
            }
    }

    private fun newSession(): String {
        val token = randomHex(32)
        sessions[token] = System.currentTimeMillis() + SESSION_MS
        return token
    }

    private fun valid(token: String?): Boolean {
        if (token == null) return false
        val expiry = sessions[token] ?: return false
        if (expiry < System.currentTimeMillis()) {
            sessions.remove(token)
            return false
        }
        return true
    }

    private fun cookie(headers: Map<String, String>): String? {
        val raw = headers["cookie"] ?: return null
        return raw.split(';')
            .map { it.trim() }
            .firstOrNull { it.startsWith("$COOKIE=") }
            ?.substringAfter('=')
    }

    private fun sessionCookie(token: String) =
        "$COOKIE=$token; Path=/; HttpOnly; SameSite=Strict; Max-Age=${SESSION_MS / 1000}"

    private fun clearCookie() = "$COOKIE=; Path=/; HttpOnly; SameSite=Strict; Max-Age=0"

    private fun ok() = JSONObject().put("ok", true).toString()

    private fun error(message: String) = JSONObject().put("ok", false).put("error", message).toString()

    private fun sha256(value: String): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(value.toByteArray(Charsets.UTF_8))
        return digest.joinToString("") { "%02x".format(it) }
    }

    private fun randomHex(bytes: Int): String {
        val data = ByteArray(bytes)
        random.nextBytes(data)
        return data.joinToString("") { "%02x".format(it) }
    }

    private fun localIpAddress(): String? {
        val interfaces = Collections.list(NetworkInterface.getNetworkInterfaces())
        for (nif in interfaces) {
            if (!nif.isUp || nif.isLoopback) continue
            for (address in Collections.list(nif.inetAddresses)) {
                if (!address.isLoopbackAddress && address is java.net.Inet4Address && address.isSiteLocalAddress) {
                    return address.hostAddress
                }
            }
        }
        return null
    }

    private fun readLine(input: InputStream): String? {
        val sb = StringBuilder()
        while (sb.length < 8192) {
            val c = input.read()
            if (c < 0) return if (sb.isEmpty()) null else sb.toString()
            if (c == '\n'.code) return sb.toString().trimEnd('\r')
            sb.append(c.toChar())
        }
        throw IOException("header line too long")
    }

    private fun readBody(input: InputStream, length: Long): String {
        if (length <= 0L || length > 64 * 1024) return ""
        val buffer = ByteArray(length.toInt())
        var read = 0
        while (read < buffer.size) {
            val n = input.read(buffer, read, buffer.size - read)
            if (n < 0) break
            read += n
        }
        return String(buffer, 0, read, Charsets.UTF_8)
    }

    private fun reply(out: OutputStream, status: Int, type: String, body: String, cookie: String? = null) {
        val bytes = body.toByteArray(Charsets.UTF_8)
        val head = StringBuilder()
            .append("HTTP/1.1 $status ${if (status == 200) "OK" else "Error"}\r\n")
            .append("Content-Type: $type\r\n")
            .append("Content-Length: ${bytes.size}\r\n")
            .append("Connection: close\r\n")
        cookie?.let { head.append("Set-Cookie: $it\r\n") }
        head.append("\r\n")
        out.write(head.toString().toByteArray(Charsets.UTF_8))
        out.write(bytes)
        out.flush()
    }
}
