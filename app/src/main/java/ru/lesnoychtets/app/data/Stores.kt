package ru.lesnoychtets.app.data

import android.content.Context
import android.content.SharedPreferences
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** Где остановилась книга: номер главы, позиция внутри неё и длительность главы. */
data class Progress(
    val track: Int = 0,
    val positionMs: Long = 0,
    val durationMs: Long = 0,
    val finished: Boolean = false,
) {
    val started: Boolean get() = !finished && (track > 0 || positionMs > 1000)

    fun encode(): String = "$track;$positionMs;$durationMs;${if (finished) 1 else 0}"

    companion object {
        fun decode(s: String): Progress? {
            val p = s.split(';')
            if (p.size < 4) return null
            return Progress(
                p[0].toIntOrNull() ?: 0,
                p[1].toLongOrNull() ?: 0,
                p[2].toLongOrNull() ?: 0,
                p[3] == "1",
            )
        }
    }
}

/** Память позиций всех книг. Один экземпляр на процесс: им пользуются и плеер, и экран. */
object ProgressStore {
    private lateinit var prefs: SharedPreferences
    private val state = MutableStateFlow<Map<String, Progress>>(emptyMap())
    val all: StateFlow<Map<String, Progress>> = state.asStateFlow()

    fun init(context: Context) {
        if (::prefs.isInitialized) return
        prefs = context.getSharedPreferences("progress", Context.MODE_PRIVATE)
        state.value = prefs.all.mapNotNull { (k, v) ->
            (v as? String)?.let(Progress::decode)?.let { k to it }
        }.toMap()
    }

    fun get(key: String): Progress? = state.value[key]

    @Synchronized
    fun save(key: String, progress: Progress) {
        if (state.value[key] == progress) return
        state.value = state.value + (key to progress)
        prefs.edit().putString(key, progress.encode()).apply()
    }

    fun markFinished(key: String) {
        val old = get(key) ?: Progress()
        save(key, old.copy(track = 0, positionMs = 0, finished = true))
    }
}

/** Настройки, которые задают родственники, и служебное состояние. */
object AppPrefs {
    private lateinit var prefs: SharedPreferences

    val syncRunning = MutableStateFlow(false)
    val syncMessage = MutableStateFlow("")

    fun init(context: Context) {
        if (::prefs.isInitialized) return
        prefs = context.getSharedPreferences("settings", Context.MODE_PRIVATE)
        syncMessage.value = prefs.getString("sync_message", "") ?: ""
    }

    var treeUri: String?
        get() = prefs.getString("tree_uri", null)
        set(v) = prefs.edit().putString("tree_uri", v).apply()

    var driveLink: String
        get() = prefs.getString("drive_link", "") ?: ""
        set(v) = prefs.edit().putString("drive_link", v).apply()

    var wifiOnly: Boolean
        get() = prefs.getBoolean("wifi_only", true)
        set(v) = prefs.edit().putBoolean("wifi_only", v).apply()

    var sortAscending: Boolean
        get() = prefs.getBoolean("sort_asc", true)
        set(v) = prefs.edit().putBoolean("sort_asc", v).apply()

    var lastBookKey: String?
        get() = prefs.getString("last_book", null)
        set(v) = prefs.edit().putString("last_book", v).apply()

    fun setSyncMessage(message: String) {
        syncMessage.value = message
        prefs.edit().putString("sync_message", message).apply()
    }
}
