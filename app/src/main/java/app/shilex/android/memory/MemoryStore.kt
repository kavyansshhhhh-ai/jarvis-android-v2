package app.shilex.android.memory

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

/**
 * Notes and routines in JARVIS's private storage on this phone. Never backed up or moved to
 * another device (see data_extraction_rules.xml); a note reaches Gemini only when recalled.
 */
class MemoryStore(context: Context) {
    private val prefs = context.getSharedPreferences("memory", Context.MODE_PRIVATE)
    private val lock = Any()
    private var lastNotesRaw: String? = null
    private var cachedNotes: List<Note>? = null
    private var lastRoutinesRaw: String? = null
    private var cachedRoutines: List<Routine>? = null

    fun notes(): List<Note> = synchronized(lock) {
        val raw = prefs.getString(NOTES, "[]") ?: "[]"
        cachedNotes?.takeIf { lastNotesRaw == raw }?.let { return it }
        val a = runCatching { JSONArray(raw) }.getOrDefault(JSONArray())
        val parsed = (0 until a.length()).mapNotNull { i ->
            a.optJSONObject(i)?.let { obj ->
                Note(
                    text = obj.optString("t"),
                    atMillis = obj.optLong("at"),
                    id = obj.optString("id").ifEmpty { UUID.randomUUID().toString() },
                    updatedAtMillis = obj.optLong("up", obj.optLong("at")),
                )
            }
        }
        lastNotesRaw = raw
        cachedNotes = parsed
        parsed
    }

    fun addNote(text: String): String = synchronized(lock) {
        val id = UUID.randomUUID().toString()
        val kept = (notes() + Note(text, System.currentTimeMillis(), id)).takeLast(MemoryRules.MAX_NOTES)
        saveNotes(kept)
        id
    }

    /** Returns the note with the given [id], or null if not found. */
    fun getNote(id: String): Note? = notes().firstOrNull { it.id == id }

    /**
     * Updates the text of an existing note, preserving its [id] and original [atMillis].
     * Returns true if the note was found and updated; false if no note with [id] exists.
     * Never duplicates the note.
     */
    fun updateNote(id: String, newText: String): Boolean = synchronized(lock) {
        val all = notes().toMutableList()
        val idx = all.indexOfFirst { it.id == id }
        if (idx < 0) return false
        val existing = all[idx]
        all[idx] = existing.copy(text = newText, updatedAtMillis = System.currentTimeMillis())
        saveNotes(all)
        true
    }

    /** Removes the notes matching [query] (all of them for "all"); returns how many. */
    fun forget(query: String): Int = synchronized(lock) {
        val all = notes()
        val gone = if (query.trim().equals("all", ignoreCase = true)) all else MemoryRules.find(query, all, limit = 1)
        if (gone.isNotEmpty()) saveNotes(all - gone.toSet())
        gone.size
    }

    fun routines(): List<Routine> = synchronized(lock) {
        val raw = prefs.getString(ROUTINES, "[]") ?: "[]"
        cachedRoutines?.takeIf { lastRoutinesRaw == raw }?.let { return it }
        val a = runCatching { JSONArray(raw) }.getOrDefault(JSONArray())
        val saved = (0 until a.length()).mapNotNull { i ->
            a.optJSONObject(i)?.let { o ->
                val steps = o.optJSONArray("steps") ?: JSONArray()
                Routine(o.optString("name"), (0 until steps.length()).map { steps.optString(it) })
            }
        }
        val builtIn = MemoryRules.GOOD_MORNING.takeUnless { gm -> saved.any { MemoryRules.key(it.name) == MemoryRules.key(gm.name) } }
        val result = listOfNotNull(builtIn) + saved
        lastRoutinesRaw = raw
        cachedRoutines = result
        result
    }

    /** Saves or replaces the routine with the same name. */
    fun saveRoutine(routine: Routine) = synchronized(lock) {
        val others = savedRoutines().filter { MemoryRules.key(it.name) != MemoryRules.key(routine.name) }
        writeRoutines((others + routine).takeLast(MemoryRules.MAX_ROUTINES))
    }

    fun deleteRoutine(name: String) = synchronized(lock) {
        writeRoutines(savedRoutines().filter { MemoryRules.key(it.name) != MemoryRules.key(name) })
    }

    fun clearNotes() = synchronized(lock) {
        saveNotes(emptyList())
    }

    private fun savedRoutines() = routines().filter { it !== MemoryRules.GOOD_MORNING }

    private fun saveNotes(notes: List<Note>) {
        val encoded = JSONArray(notes.map { n ->
            JSONObject()
                .put("t", n.text)
                .put("at", n.atMillis)
                .put("id", n.id)
                .put("up", n.updatedAtMillis)
        }).toString()
        lastNotesRaw = encoded
        cachedNotes = notes
        prefs.edit().putString(NOTES, encoded).apply()
    }

    private fun writeRoutines(routines: List<Routine>) {
        val encoded = JSONArray(routines.map { JSONObject().put("name", it.name).put("steps", JSONArray(it.steps)) }).toString()
        lastRoutinesRaw = null
        cachedRoutines = null
        prefs.edit().putString(ROUTINES, encoded).apply()
    }

    private companion object {
        const val NOTES = "notes"
        const val ROUTINES = "routines"
    }
}
