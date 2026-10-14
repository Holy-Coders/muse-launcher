package dev.puck

import android.content.Context
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.room.*
import dev.puck.core.*
import kotlinx.coroutines.flow.map
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

private val Context.preferences by preferencesDataStore("puck_preferences")
class DraftStore(context: Context, preferencesStore: DataStore<Preferences>? = null) {
    private val store = preferencesStore ?: context.applicationContext.preferences
    private val draft = stringPreferencesKey("typed_draft")
    private val voice = stringPreferencesKey("voice_review")
    private val googleVoiceKey = booleanPreferencesKey("google_voice_handoffs")
    val googleVoice = store.data.map { it[googleVoiceKey] ?: false }
    suspend fun setGoogleVoice(value: Boolean) { store.edit { it[googleVoiceKey] = value } }
    private val speaking = booleanPreferencesKey("speak_replies")
    val input = store.data.map { it[draft] ?: "" }
    val voiceReview = store.data.map { it[voice] ?: "" }
    private val motionKey = booleanPreferencesKey("companion_motion")
    private val readKey = stringPreferencesKey("read_updates")
    private val dismissedKey = androidx.datastore.preferences.core.stringSetPreferencesKey("dismissed_home_updates")
    private val checkInKey = booleanPreferencesKey("home_check_ins")
    private val checkInTime = longPreferencesKey("home_check_in_time")
    val homeCheckIns = store.data.map { it[checkInKey] ?: true }
    suspend fun setHomeCheckIns(value: Boolean) { store.edit { it[checkInKey] = value } }
    suspend fun claimHomeCheckIn(now: Long): Boolean {
        var claimed = false
        store.edit { prefs ->
            if ((prefs[checkInKey] ?: true) && HomeCheckIn.due(now, prefs[checkInTime] ?: 0L)) {
                prefs[checkInTime] = now
                claimed = true
            }
        }
        return claimed
    }
    val motion = store.data.map { it[motionKey] ?: true }
    val readUpdates = store.data.map { it[readKey]?.split("|")?.filter(String::isNotBlank)?.toSet() ?: emptySet() }
    suspend fun setMotion(value: Boolean) { store.edit { it[motionKey] = value } }
    suspend fun markRead(id: String) { store.edit { prefs ->
        val ids = prefs[readKey]?.split("|")?.filter(String::isNotBlank).orEmpty()
        prefs[readKey] = (ids.filter { it != id } + id).takeLast(256).joinToString("|")
    } }
    val dismissedUpdates = store.data.map { it[dismissedKey] ?: emptySet() }
    suspend fun dismissUpdate(id: String) { store.edit { prefs ->
        prefs[dismissedKey] = (prefs[dismissedKey].orEmpty() + id)
        val read = prefs[readKey]?.split("|")?.filter(String::isNotBlank).orEmpty()
        prefs[readKey] = (read.filter { it != id } + id).takeLast(256).joinToString("|")
    } }
    val speak = store.data.map { it[speaking] ?: false }
    suspend fun setInput(value: String) { store.edit { it[draft] = value } }
    suspend fun setVoice(value: String) { store.edit { it[voice] = value } }
    suspend fun setSpeak(value: Boolean) { store.edit { it[speaking] = value } }
}

@Entity(tableName = "tasks")
data class TaskEntity(@PrimaryKey val id: String, val json: String, val createdAt: Long, val archived: Boolean = false)
@Dao
interface TaskDao {
    @Query("SELECT * FROM tasks WHERE archived = 0 ORDER BY createdAt DESC, id DESC") fun observe(): kotlinx.coroutines.flow.Flow<List<TaskEntity>>
    @Query("SELECT * FROM tasks WHERE id = :id") suspend fun get(id: String): TaskEntity?
    @Query("SELECT * FROM tasks") suspend fun all(): List<TaskEntity>
    @Insert(onConflict = OnConflictStrategy.IGNORE) suspend fun insert(entity: TaskEntity): Long
    @Query("UPDATE tasks SET json = :newJson WHERE id = :id AND json = :oldJson") suspend fun compareAndSet(id: String, oldJson: String, newJson: String): Int
    @Query("UPDATE tasks SET archived = 1") suspend fun clear()
}
@Database(entities = [TaskEntity::class], version = 1, exportSchema = true)
abstract class PuckDatabase : RoomDatabase() {
    abstract fun tasks(): TaskDao
    companion object {
        @Volatile private var instance: PuckDatabase? = null
        fun get(context: Context): PuckDatabase = instance ?: synchronized(this) {
            instance ?: Room.databaseBuilder(context.applicationContext, PuckDatabase::class.java, "puck.db").build().also { instance = it }
        }
    }
}
class TaskRepository(private val dao: TaskDao) {
    private val codec = Json { ignoreUnknownKeys = false }
    val tasks = dao.observe().map { rows -> rows.map { decode(it) } }
    private fun decode(row: TaskEntity) = codec.decodeFromString<PuckTask>(row.json)
    suspend fun get(id: String) = dao.get(id)?.let(::decode)
    suspend fun pendingContact() = dao.all().map(::decode).firstOrNull { it.contactPicking && it.phase == TaskPhase.AWAITING_APPROVAL }
    suspend fun insert(task: PuckTask) = dao.insert(TaskEntity(task.id, codec.encodeToString(task), task.createdAt)) != -1L
    /** Durable compare-and-set is the execution gate, including rapid double taps and duplicate IDs. */
    suspend fun update(id: String, transform: (PuckTask) -> PuckTask?): PuckTask? {
        val old = dao.get(id) ?: return null
        val next = transform(decode(old)) ?: return null
        return if (dao.compareAndSet(id, old.json, codec.encodeToString(next)) == 1) next else null
    }
    suspend fun recoverInterrupted() {
        dao.all().forEach { row ->
            update(row.id) { task -> when {
                task.phase == TaskPhase.DISPATCHING -> TaskMachine.move(task, TaskPhase.FAILED, "Interrupted during handoff. Outcome unknown; not replayed.")
                task.phase == TaskPhase.PREPARING -> TaskMachine.move(task, TaskPhase.FAILED, "Preparation interrupted. Please make a new request.")
                else -> task
            } }
        }
    }
    suspend fun clear() = dao.clear()
}
