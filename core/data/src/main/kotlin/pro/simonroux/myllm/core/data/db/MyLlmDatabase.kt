package pro.simonroux.myllm.core.data.db

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase

/**
 * The whole on-device store.
 *
 * Nothing here ever leaves the phone. There is no sync, no backup target and no
 * analytics table, which is deliberate: the app is useful precisely because the
 * conversation history is somewhere only its owner can read.
 *
 * Android's automatic cloud backup is disabled in the manifest for the same
 * reason, so this file is not silently copied off the device either.
 */
@Database(
    entities = [
        ConversationEntity::class,
        MessageEntity::class,
        SkillEntity::class,
        SkillRevisionEntity::class,
        NoteEntity::class,
        SkillMemoryEntity::class,
        ChangeRequestEntity::class,
        ModelEntity::class,
    ],
    version = 1,
    // Schema export is off until it is wired through the Room Gradle plugin.
    // Passing room.schemaLocation as a bare KSP argument makes Room 2.6.1 fail
    // with "Empty schema file" on the release variant. There is only one
    // version and no migration yet, so nothing is lost by waiting; see
    // ROADMAP.md before adding version 2.
    exportSchema = false,
)
abstract class MyLlmDatabase : RoomDatabase() {

    abstract fun conversations(): ConversationDao

    abstract fun messages(): MessageDao

    abstract fun skills(): SkillDao

    abstract fun notes(): NoteDao

    abstract fun skillMemory(): SkillMemoryDao

    abstract fun changeRequests(): ChangeRequestDao

    abstract fun models(): ModelDao

    companion object {

        private const val NAME = "myllm.db"

        fun create(context: Context): MyLlmDatabase =
            Room.databaseBuilder(context.applicationContext, MyLlmDatabase::class.java, NAME)
                // Write-ahead logging keeps a long streaming insert from
                // blocking the reads that drive the chat list.
                .setJournalMode(RoomDatabase.JournalMode.WRITE_AHEAD_LOGGING)
                .build()
    }
}
