package com.newax.aegis.db

import androidx.room.Room
import androidx.room.testing.MigrationTestHelper
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.newax.aegis.db.entity.ConversationEntity
import com.newax.aegis.db.entity.MessageBlockEntity
import com.newax.aegis.db.entity.MessageBlockType
import com.newax.aegis.db.entity.MessageEntity
import com.newax.aegis.db.entity.MessageRole
import com.newax.aegis.db.entity.SyncJournalEntity
import com.newax.aegis.db.entity.SyncVectorEntity
import kotlinx.coroutines.runBlocking
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.IOException

@RunWith(AndroidJUnit4::class)
class MigrationTest {

    private val TEST_DB = "aegis-migration-test"

    @get:Rule
    val helper = MigrationTestHelper(
        InstrumentationRegistry.getInstrumentation(),
        NewaxDatabase::class.java,
        emptyList(),
        FrameworkSQLiteOpenHelperFactory()
    )

    @Test
    @Throws(IOException::class)
    fun migrate1To2() {
        helper.createDatabase(TEST_DB, 1).apply { close() }
        helper.runMigrationsAndValidate(TEST_DB, 2, true, NewaxDatabase.MIGRATION_1_2)
    }

    @Test
    @Throws(IOException::class)
    fun migrate2To3() {
        helper.createDatabase(TEST_DB, 2).apply { close() }
        helper.runMigrationsAndValidate(TEST_DB, 3, true, NewaxDatabase.MIGRATION_2_3)
    }

    @Test
    @Throws(IOException::class)
    fun migrate3To4() {
        helper.createDatabase(TEST_DB, 3).apply { close() }
        helper.runMigrationsAndValidate(TEST_DB, 4, true, NewaxDatabase.MIGRATION_3_4)
    }

    @Test
    @Throws(IOException::class)
    fun migrate4To5() {
        helper.createDatabase(TEST_DB, 4).apply { close() }
        helper.runMigrationsAndValidate(TEST_DB, 5, true, NewaxDatabase.MIGRATION_4_5)
    }

    @Test
    @Throws(IOException::class)
    fun migrate5To6() {
        helper.createDatabase(TEST_DB, 5).apply { close() }
        helper.runMigrationsAndValidate(TEST_DB, 6, true, NewaxDatabase.MIGRATION_5_6)
    }

    @Test
    @Throws(IOException::class)
    fun migrate6To7() {
        helper.createDatabase(TEST_DB, 6).apply { close() }
        helper.runMigrationsAndValidate(TEST_DB, 7, true, NewaxDatabase.MIGRATION_6_7)
    }

    @Test
    @Throws(IOException::class)
    fun migrate7To8() {
        helper.createDatabase(TEST_DB, 7).apply { close() }
        helper.runMigrationsAndValidate(TEST_DB, 8, true, NewaxDatabase.MIGRATION_7_8)
    }

    @Test
    @Throws(IOException::class)
    fun migrate8To9() {
        helper.createDatabase(TEST_DB, 8).apply { close() }
        helper.runMigrationsAndValidate(TEST_DB, 9, true, NewaxDatabase.MIGRATION_8_9)
    }

    @Test
    @Throws(IOException::class)
    fun migrate9To10() {
        helper.createDatabase(TEST_DB, 9).apply {
            execSQL("INSERT INTO file_objects (path, filename, extension, mimeType, sizeBytes, indexState) VALUES ('test.pdf', 'test.pdf', 'pdf', 'application/pdf', 1024, 0)")
            close()
        }
        val db = helper.runMigrationsAndValidate(TEST_DB, 10, true, NewaxDatabase.MIGRATION_9_10)
        val cursor = db.query("SELECT contentUriString, mediaStoreId FROM file_objects WHERE path = 'test.pdf'")
        assert(cursor.moveToFirst())
        assert(cursor.getString(0) == "")
        assert(cursor.getLong(1) == 0L)
        cursor.close()
    }

    @Test
    @Throws(IOException::class)
    fun migrate10To11() {
        helper.createDatabase(TEST_DB, 10).apply {
            execSQL("INSERT INTO ui_procedures (packageName, versionRange, taskCapability, steps, confidence, successCount, failureCount, lastRunMs, needsValidation) VALUES ('com.test', '*', 'SEND_MESSAGE', '[]', 80, 0, 0, 0, 0)")
            close()
        }
        val db = helper.runMigrationsAndValidate(TEST_DB, 11, true, NewaxDatabase.MIGRATION_10_11)
        val cursor = db.query("SELECT prerequisites, recoveryPaths, successConditions FROM ui_procedures WHERE packageName = 'com.test'")
        assert(cursor.moveToFirst())
        assert(cursor.getString(0) == "")
        assert(cursor.getString(1) == "")
        assert(cursor.getString(2) == "")
        cursor.close()
    }

    /**
     * v12 replaces the Callback-created person_facts_fts with a Room-managed FTS entity.
     * Seeds a fact first so the rebuild is exercised, not just the CREATE.
     */
    @Test
    @Throws(IOException::class)
    fun migrate11To12() {
        helper.createDatabase(TEST_DB, 11).apply {
            execSQL("INSERT INTO persons (name, importanceScore, sourceCount, totalMentions, lastSeenMs, profileBuilt) VALUES ('Ayesha', 0.5, 1, 1, 0, 0)")
            execSQL("INSERT INTO person_facts (personId, fact, category, confidence, source, timestampMs) VALUES (1, 'lives in Karachi', 'personal', 0.9, 'test', 0)")
            close()
        }
        val db = helper.runMigrationsAndValidate(TEST_DB, 12, true, NewaxDatabase.MIGRATION_11_12)
        val cursor = db.query(
            "SELECT rowid FROM person_facts_fts WHERE person_facts_fts MATCH 'Karachi'"
        )
        cursor.use { assert(it.moveToFirst()) { "FTS index was not rebuilt from person_facts" } }
    }

    /**
     * v13 adds the sync substrate: two new tables (sync_journal, sync_vector) and
     * four sync columns on every syncable table. Seeds rows in several syncable
     * tables first so the ALTERs land on non-empty data and the defaults are
     * asserted, then verifies the sync tables exist and the DAOs round-trip.
     */
    @Test
    @Throws(IOException::class)
    fun migrate12To13() {
        helper.createDatabase(TEST_DB, 12).apply {
            execSQL("INSERT INTO persons (name, importanceScore, sourceCount, totalMentions, lastSeenMs, profileBuilt) VALUES ('Ayesha', 0.5, 1, 1, 0, 0)")
            execSQL("INSERT INTO memory_records (type, content, category, subject, source, confidence, importance, createdAt, updatedAt) VALUES (1, 'sync substrate test', 'personal', 'Ayesha', 'test', 80, 50, 0, 0)")
            close()
        }
        val db = helper.runMigrationsAndValidate(TEST_DB, 13, true, NewaxDatabase.MIGRATION_12_13)

        // Existing rows carry the pre-sync baseline defaults (never win a merge).
        // Column names are verbatim property names (Room convention — no snake_case).
        val person = db.query("SELECT syncHcWall, syncHcCounter, syncDeviceId, syncTombstone FROM persons WHERE name = 'Ayesha'")
        assert(person.moveToFirst())
        assert(person.getLong(0) == 0L)
        assert(person.getLong(1) == 0L)
        assert(person.getString(2) == "")
        assert(person.getLong(3) == 0L)
        person.close()
        val memory = db.query("SELECT syncTombstone FROM memory_records WHERE content = 'sync substrate test'")
        assert(memory.moveToFirst())
        assert(memory.getLong(0) == 0L)
        memory.close()

        // New rows accept real sync metadata.
        db.execSQL("INSERT INTO persons (name, importanceScore, sourceCount, totalMentions, lastSeenMs, profileBuilt, syncHcWall, syncHcCounter, syncDeviceId, syncTombstone) VALUES ('Bilal', 0.5, 1, 1, 0, 0, 42, 7, 'dev-w', 0)")
        val stamped = db.query("SELECT syncHcWall, syncHcCounter, syncDeviceId FROM persons WHERE name = 'Bilal'")
        assert(stamped.moveToFirst())
        assert(stamped.getLong(0) == 42L)
        assert(stamped.getLong(1) == 7L)
        assert(stamped.getString(2) == "dev-w")
        stamped.close()

        // Sync tables exist with the expected columns.
        val journal = db.query("SELECT opId, deviceId, hlcWall, hlcCounter, kind, tableName, key, tombstone FROM sync_journal")
        assert(!journal.moveToFirst())
        journal.close()
        val vector = db.query("SELECT peerDeviceId, lastAppliedHlcWall, lastAppliedHlcCounter FROM sync_vector")
        assert(!vector.moveToFirst())
        vector.close()
    }

    @Test
    @Throws(IOException::class)
    fun migrate13To14() {
        helper.createDatabase(TEST_DB, 13).apply { close() }
        helper.runMigrationsAndValidate(TEST_DB, 14, true, NewaxDatabase.MIGRATION_13_14)
    }

    @Test
    @Throws(IOException::class)
    fun migrate14To15() {
        helper.createDatabase(TEST_DB, 14).apply { close() }
        helper.runMigrationsAndValidate(TEST_DB, 15, true, NewaxDatabase.MIGRATION_14_15)
    }

    @Test
    @Throws(IOException::class)
    fun migrate15To16() {
        helper.createDatabase(TEST_DB, 15).apply { close() }
        helper.runMigrationsAndValidate(TEST_DB, 16, true, NewaxDatabase.MIGRATION_15_16)
    }

    @Test
    @Throws(IOException::class)
    fun migrate16To17() {
        helper.createDatabase(TEST_DB, 16).apply { close() }
        helper.runMigrationsAndValidate(TEST_DB, 17, true, NewaxDatabase.MIGRATION_16_17)
    }

    @Test
    @Throws(IOException::class)
    fun migrate17To18() {
        helper.createDatabase(TEST_DB, 17).apply { close() }
        helper.runMigrationsAndValidate(TEST_DB, 18, true, NewaxDatabase.MIGRATION_17_18)
    }

    @Test
    @Throws(IOException::class)
    fun migrate18To19() {
        helper.createDatabase(TEST_DB, 18).apply { close() }
        helper.runMigrationsAndValidate(TEST_DB, 19, true, NewaxDatabase.MIGRATION_18_19)
    }

    /**
     * v20 adds the conversation tables. Seeds a v19 row first so the migration
     * is exercised on non-empty data, then verifies existing data survives and
     * the new tables accept the DAO's row shape (verbatim property names).
     */
    @Test
    @Throws(IOException::class)
    fun migrate19To20() {
        helper.createDatabase(TEST_DB, 19).apply {
            execSQL("INSERT INTO skills (skillId, name, description, category, version, source, packageDir) VALUES ('s1', 'skill one', 'd', 'c', '1', 'bundled', '/x')")
            close()
        }
        val db = helper.runMigrationsAndValidate(TEST_DB, 20, true, NewaxDatabase.MIGRATION_19_20)

        // Existing v19 data survives untouched.
        val skill = db.query("SELECT name FROM skills WHERE skillId = 's1'")
        assert(skill.moveToFirst())
        assert(skill.getString(0) == "skill one")
        skill.close()

        // The new tables accept the DAO's row shape.
        db.execSQL("INSERT INTO conversations (id, title, createdAtMs, updatedAtMs) VALUES ('c1', 'First chat', 1, 1)")
        db.execSQL("INSERT INTO messages (id, conversationId, fromUser, text, timestampMs, truncated) VALUES ('m1', 'c1', 1, 'hi', 1, 0)")
        val conv = db.query("SELECT title FROM conversations WHERE id = 'c1'")
        assert(conv.moveToFirst())
        assert(conv.getString(0) == "First chat")
        conv.close()
        val msg = db.query("SELECT text, fromUser, truncated FROM messages WHERE conversationId = 'c1'")
        assert(msg.moveToFirst())
        assert(msg.getString(0) == "hi")
        assert(msg.getLong(1) == 1L)
        assert(msg.getLong(2) == 0L)
        msg.close()
    }

    @Test
    @Throws(IOException::class)
    fun migrateFullPath_1To13() {
        helper.createDatabase(TEST_DB, 1).apply { close() }
        helper.runMigrationsAndValidate(
            TEST_DB, 13, true,
            NewaxDatabase.MIGRATION_1_2,
            NewaxDatabase.MIGRATION_2_3,
            NewaxDatabase.MIGRATION_3_4,
            NewaxDatabase.MIGRATION_4_5,
            NewaxDatabase.MIGRATION_5_6,
            NewaxDatabase.MIGRATION_6_7,
            NewaxDatabase.MIGRATION_7_8,
            NewaxDatabase.MIGRATION_8_9,
            NewaxDatabase.MIGRATION_9_10,
            NewaxDatabase.MIGRATION_10_11,
            NewaxDatabase.MIGRATION_11_12,
            NewaxDatabase.MIGRATION_12_13
        )
    }

    @Test
    @Throws(IOException::class)
    fun migrateFullPath_1To20() {
        helper.createDatabase(TEST_DB, 1).apply { close() }
        helper.runMigrationsAndValidate(
            TEST_DB, 20, true,
            NewaxDatabase.MIGRATION_1_2,
            NewaxDatabase.MIGRATION_2_3,
            NewaxDatabase.MIGRATION_3_4,
            NewaxDatabase.MIGRATION_4_5,
            NewaxDatabase.MIGRATION_5_6,
            NewaxDatabase.MIGRATION_6_7,
            NewaxDatabase.MIGRATION_7_8,
            NewaxDatabase.MIGRATION_8_9,
            NewaxDatabase.MIGRATION_9_10,
            NewaxDatabase.MIGRATION_10_11,
            NewaxDatabase.MIGRATION_11_12,
            NewaxDatabase.MIGRATION_12_13,
            NewaxDatabase.MIGRATION_13_14,
            NewaxDatabase.MIGRATION_14_15,
            NewaxDatabase.MIGRATION_15_16,
            NewaxDatabase.MIGRATION_16_17,
            NewaxDatabase.MIGRATION_17_18,
            NewaxDatabase.MIGRATION_18_19,
            NewaxDatabase.MIGRATION_19_20
        )
    }

    /**
     * The sync DAOs round-trip against the v13 schema: journal dedup by opId,
     * delta scan ordering, and vector upsert. Runs on an in-memory DB (same
     * pattern as validateCurrentSchema) so the generated DAO implementations
     * are exercised, not just the tables.
     */
    @Test
    @Throws(IOException::class)
    fun syncDaosRoundTrip() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val db = Room.inMemoryDatabaseBuilder(context, NewaxDatabase::class.java).build()
        try {
            val journalDao = db.syncJournalDao()
            val vectorDao = db.syncVectorDao()

            val e1 = SyncJournalEntity(
                opId = "op-1", deviceId = "dev-w", hlcWall = 1, hlcCounter = 1,
                kind = SyncJournalEntity.KIND_RECORD, tableName = "persons",
                key = "1", payload = byteArrayOf(1, 2, 3)
            )
            val e2 = e1.copy(
                opId = "op-2", hlcWall = 2, hlcCounter = 5, key = "2",
                payload = byteArrayOf(4)
            )
            runBlocking {
                // Dedup: same opId inserted twice applies once.
                assert(journalDao.insert(e1) == 1L)
                // Room's @Insert(onConflict = IGNORE) returns -1 when the row
                // is ignored (SQLite rowid semantics) — not 0.
                assert(journalDao.insert(e1) == -1L)
                journalDao.insertAll(listOf(e2, e1)) // e1 already present — ignored
                assert(journalDao.count() == 2L)

                // Delta scan: strictly after (1,1) → only op-2.
                val after = journalDao.entriesAfter(1, 1)
                assert(after.map { it.opId } == listOf("op-2"))

                // Per-record history.
                val history = journalDao.entriesFor("persons", "2")
                assert(history.map { it.opId } == listOf("op-2"))
                assert(journalDao.getByOpId("op-1")!!.payload.contentEquals(byteArrayOf(1, 2, 3)))

                // Vector upsert replaces (advances) the watermark.
                vectorDao.upsert(SyncVectorEntity(peerDeviceId = "dev-m", lastAppliedHlcWall = 3, lastAppliedHlcCounter = 9))
                vectorDao.upsert(SyncVectorEntity(peerDeviceId = "dev-m", lastAppliedHlcWall = 5, lastAppliedHlcCounter = 2))
                val v = vectorDao.getByPeer("dev-m")!!
                assert(v.lastAppliedHlcWall == 5L)
                assert(v.lastAppliedHlcCounter == 2L)
            }
        } finally {
            db.close()
        }
    }

    /**
     * The conversation DAOs round-trip against the v20 schema: recent-first
     * list, oldest-first transcript, touch/rename, and the transactional
     * delete that removes messages with the conversation. Runs on an
     * in-memory DB (same pattern as syncDaosRoundTrip) so the generated DAO
     * implementations are exercised, not just the tables.
     */
    @Test
    @Throws(IOException::class)
    fun conversationDaosRoundTrip() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val db = Room.inMemoryDatabaseBuilder(context, NewaxDatabase::class.java).build()
        try {
            val dao = db.conversationDao()
            runBlocking {
                dao.upsertConversation(ConversationEntity(id = "c1", title = "First chat", createdAtMs = 1, updatedAtMs = 1))
                dao.upsertConversation(ConversationEntity(id = "c2", title = "Second chat", createdAtMs = 2, updatedAtMs = 2))
                dao.upsertMessage(MessageEntity(id = "m1", conversationId = "c1", fromUser = MessageRole.USER, text = "hi", timestampMs = 1))
                dao.upsertMessage(MessageEntity(id = "m2", conversationId = "c1", fromUser = MessageRole.ASSISTANT, text = "hello there", timestampMs = 2))

                // Recent-first list.
                val convs = dao.recentConversations(10)
                assert(convs.map { it.id } == listOf("c2", "c1"))

                // Transcript oldest-first.
                val msgs = dao.messagesFor("c1")
                assert(msgs.map { it.text } == listOf("hi", "hello there"))

                // Touch bubbles c1 above c2.
                dao.touchConversation("c1", 99)
                assert(dao.recentConversations(10).first().id == "c1")

                // Rename.
                dao.renameConversation("c1", "Renamed", 100)
                assert(dao.conversationById("c1")!!.title == "Renamed")

                // Delete removes the conversation AND its messages in one transaction.
                dao.deleteConversation("c1")
                assert(dao.conversationById("c1") == null)
                assert(dao.messageCount("c1") == 0L)
                assert(dao.messageCount("c2") == 0L)
            }
        } finally {
            db.close()
        }
    }

    /**
     * Stacked content blocks (docs/UI_DESIGN.md §7) round-trip: render order,
     * kind filtering, the plain-text message that stores no blocks at all, and
     * the transactional delete leaving no orphans behind.
     */
    @Test
    @Throws(IOException::class)
    fun messageBlocksRoundTrip() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val db = Room.inMemoryDatabaseBuilder(context, NewaxDatabase::class.java).build()
        try {
            val dao = db.conversationDao()
            runBlocking {
                dao.upsertConversation(ConversationEntity(id = "c1", title = "Chat", createdAtMs = 1, updatedAtMs = 1))
                dao.upsertMessage(MessageEntity(id = "m1", conversationId = "c1", fromUser = MessageRole.USER, text = "show me the code", timestampMs = 1))
                dao.upsertMessage(MessageEntity(id = "m2", conversationId = "c1", fromUser = MessageRole.ASSISTANT, text = "Here it is. print(1)", timestampMs = 2))

                // m1 is plain text and deliberately stores no blocks.
                assert(dao.blocksFor("m1").isEmpty())

                // m2 stacks three blocks, inserted out of order to prove that
                // `position` and not insertion order drives rendering.
                dao.upsertBlocks(
                    listOf(
                        MessageBlockEntity(id = "b2", messageId = "m2", position = 1, type = MessageBlockType.CODE, content = "print(1)", metadata = """{"language":"python"}"""),
                        MessageBlockEntity(id = "b0", messageId = "m2", position = 0, type = MessageBlockType.THOUGHT, content = "they want the snippet"),
                        MessageBlockEntity(id = "b1", messageId = "m2", position = 2, type = MessageBlockType.TEXT, content = "Here it is."),
                    )
                )

                val blocks = dao.blocksFor("m2")
                assert(blocks.map { it.id } == listOf("b0", "b2", "b1"))
                assert(blocks.map { it.position } == listOf(0, 1, 2))
                assert(blocks[1].metadata == """{"language":"python"}""")

                // Kind filtering across the conversation — the artifact panel's query.
                assert(dao.blocksOfType("c1", MessageBlockType.CODE).map { it.id } == listOf("b2"))
                assert(dao.blocksOfType("c1", MessageBlockType.IMAGE).isEmpty())

                // An unknown kind round-trips rather than being dropped, so a
                // message written by a newer build survives an older reader.
                dao.upsertBlocks(listOf(MessageBlockEntity(id = "b9", messageId = "m2", position = 3, type = "kind_from_the_future")))
                assert(dao.blocksFor("m2").last().type == "kind_from_the_future")

                // Deleting the conversation leaves no orphaned blocks.
                dao.deleteConversation("c1")
                assert(dao.messageCount("c1") == 0L)
                assert(dao.blocksFor("m2").isEmpty())
            }
        } finally {
            db.close()
        }
    }

    @Test
    @Throws(IOException::class)
    fun validateCurrentSchema() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val db = Room.inMemoryDatabaseBuilder(context, NewaxDatabase::class.java).build()
        db.close()
    }
}
