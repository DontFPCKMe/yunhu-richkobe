package com.juhao.murexide.data.local

import android.content.Context
import androidx.room.Dao
import androidx.room.Database
import androidx.room.Entity
import androidx.room.Index
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.Transaction
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import kotlinx.coroutines.flow.Flow

@Entity(
    tableName = "cached_conversations",
    primaryKeys = ["accountId", "chatType", "chatId"],
    indices = [
        Index(value = ["accountId", "listPosition"]),
        Index(value = ["accountId", "sendTimestamp"])
    ]
)
data class CachedConversationEntity(
    val accountId: String,
    val chatType: Int,
    val chatId: String,
    val name: String,
    val remark: String?,
    val chatContent: String,
    val timestampMs: Long,
    val unreadMessage: Int,
    val at: Int,
    val avatarUrl: String,
    val doNotDisturb: Int,
    val certificationLevel: Int,
    val sendTimestamp: Long,
    val latestMessageId: String?,
    val latestMessageSeq: Long,
    val latestContentType: Int,
    val listPosition: Int
)

@Entity(
    tableName = "cached_sticky_conversations",
    primaryKeys = ["accountId", "id"],
    indices = [
        Index(value = ["accountId", "listPosition"]),
        Index(value = ["accountId", "chatType", "chatId"])
    ]
)
data class CachedStickyEntity(
    val accountId: String,
    val id: Long,
    val chatType: Int,
    val chatId: String,
    val chatName: String,
    val avatarUrl: String,
    val certificationLevel: Int,
    val listPosition: Int
)

/** Message payloads contain text and remote media metadata only; never media bytes. */
@Entity(
    tableName = "cached_messages",
    primaryKeys = ["accountId", "msgId"],
    indices = [
        Index(value = ["accountId", "chatType", "chatId", "timestamp"]),
        Index(value = ["accountId", "chatType", "chatId", "msgSeq"]),
        Index(value = ["accountId", "chatType", "chatId", "updateTimestamp"])
    ]
)
data class CachedMessageEntity(
    val accountId: String,
    val msgId: String,
    val chatId: String,
    val chatType: Int,
    val senderId: String,
    val senderType: Int,
    val timestamp: Long,
    val msgSeq: Long,
    val updateTimestamp: Long,
    val payload: String
)

@Entity(
    tableName = "cached_message_senders",
    primaryKeys = ["accountId", "chatType", "chatId", "senderType", "senderId"]
)
data class CachedMessageSenderEntity(
    val accountId: String,
    val chatType: Int,
    val chatId: String,
    val senderType: Int,
    val senderId: String,
    val name: String,
    val avatarUrl: String,
    val tagsJson: String
)

data class CachedMessageRow(
    val accountId: String,
    val msgId: String,
    val chatId: String,
    val chatType: Int,
    val senderId: String,
    val senderType: Int,
    val timestamp: Long,
    val msgSeq: Long,
    val updateTimestamp: Long,
    val payload: String,
    val senderName: String?,
    val senderAvatarUrl: String?,
    val senderTagsJson: String?
)

/** Small JSON snapshots for contacts, profiles, details, member pages and requests. */
@Entity(
    tableName = "cached_payloads",
    primaryKeys = ["accountId", "kind", "scope"]
)
data class CachedPayloadEntity(
    val accountId: String,
    val kind: String,
    val scope: String,
    val payload: String,
    val updatedAt: Long,
    val expiresAt: Long
)

@Entity(tableName = "cache_sync_state", primaryKeys = ["accountId", "key"])
data class CacheSyncStateEntity(
    val accountId: String,
    val key: String,
    val value: String = "",
    val updatedAt: Long = 0L
)

@Dao
interface ConversationCacheDao {
    @Query("SELECT * FROM cached_conversations WHERE accountId = :accountId ORDER BY listPosition ASC, chatType ASC, chatId ASC")
    fun observeConversations(accountId: String): Flow<List<CachedConversationEntity>>

    @Query("SELECT * FROM cached_conversations WHERE accountId = :accountId ORDER BY listPosition ASC, chatType ASC, chatId ASC")
    suspend fun getConversations(accountId: String): List<CachedConversationEntity>

    @Query("SELECT * FROM cached_conversations WHERE accountId = :accountId AND chatId = :chatId AND chatType = :chatType LIMIT 1")
    suspend fun getConversation(
        accountId: String,
        chatId: String,
        chatType: Int
    ): CachedConversationEntity?

    @Query(
        "SELECT * FROM cached_conversations WHERE accountId = :accountId AND chatType = :chatType " +
            "AND (chatId = :chatId OR (:chatType = 1 AND chatId = :senderId)) LIMIT 1"
    )
    suspend fun getConversationForMessage(
        accountId: String,
        chatId: String,
        chatType: Int,
        senderId: String
    ): CachedConversationEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertConversations(items: List<CachedConversationEntity>)

    @Query("DELETE FROM cached_conversations WHERE accountId = :accountId")
    suspend fun clearConversations(accountId: String)

    @Query("UPDATE cached_conversations SET unreadMessage = 0, at = 0 WHERE accountId = :accountId AND chatId = :chatId AND chatType = :chatType")
    suspend fun clearUnread(accountId: String, chatId: String, chatType: Int)

    @Query("UPDATE cached_conversations SET doNotDisturb = :doNotDisturb WHERE accountId = :accountId AND chatId = :chatId AND chatType = :chatType")
    suspend fun setDoNotDisturb(accountId: String, chatId: String, chatType: Int, doNotDisturb: Int)

    @Query("DELETE FROM cached_conversations WHERE accountId = :accountId AND chatId = :chatId AND chatType = :chatType")
    suspend fun deleteConversation(accountId: String, chatId: String, chatType: Int)

    @Query(
        "UPDATE cached_conversations SET listPosition = listPosition + 1 " +
            "WHERE accountId = :accountId AND NOT (chatType = :chatType AND chatId = :chatId)"
    )
    suspend fun shiftForMoveToFront(accountId: String, chatType: Int, chatId: String)

    @Query("SELECT * FROM cached_sticky_conversations WHERE accountId = :accountId ORDER BY listPosition ASC, id ASC")
    fun observeSticky(accountId: String): Flow<List<CachedStickyEntity>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertSticky(items: List<CachedStickyEntity>)

    @Query("DELETE FROM cached_sticky_conversations WHERE accountId = :accountId")
    suspend fun clearSticky(accountId: String)

    @Transaction
    suspend fun replaceConversations(accountId: String, items: List<CachedConversationEntity>) {
        clearConversations(accountId)
        if (items.isNotEmpty()) upsertConversations(items)
    }

    @Transaction
    suspend fun replaceSticky(accountId: String, items: List<CachedStickyEntity>) {
        clearSticky(accountId)
        if (items.isNotEmpty()) upsertSticky(items)
    }

    @Transaction
    suspend fun moveConversationToFront(item: CachedConversationEntity) {
        shiftForMoveToFront(item.accountId, item.chatType, item.chatId)
        upsertConversations(listOf(item.copy(listPosition = 0)))
    }
}

@Dao
interface MessageCacheDao {
    @Query(
        "SELECT m.*, s.name AS senderName, s.avatarUrl AS senderAvatarUrl, s.tagsJson AS senderTagsJson " +
            "FROM cached_messages m LEFT JOIN cached_message_senders s " +
            "ON m.accountId = s.accountId AND m.chatType = s.chatType AND m.chatId = s.chatId " +
            "AND m.senderType = s.senderType AND m.senderId = s.senderId " +
            "WHERE m.accountId = :accountId AND m.chatId = :chatId AND m.chatType = :chatType " +
            "ORDER BY m.timestamp DESC, m.msgSeq DESC, m.msgId DESC LIMIT :limit"
    )
    fun observeMessages(accountId: String, chatId: String, chatType: Int, limit: Int): Flow<List<CachedMessageRow>>

    @Query(
        "SELECT m.*, s.name AS senderName, s.avatarUrl AS senderAvatarUrl, s.tagsJson AS senderTagsJson " +
            "FROM cached_messages m LEFT JOIN cached_message_senders s " +
            "ON m.accountId = s.accountId AND m.chatType = s.chatType AND m.chatId = s.chatId " +
            "AND m.senderType = s.senderType AND m.senderId = s.senderId " +
            "WHERE m.accountId = :accountId AND m.chatId = :chatId AND m.chatType = :chatType " +
            "AND (m.timestamp < :beforeTimestamp " +
            "OR (m.timestamp = :beforeTimestamp AND m.msgSeq < :beforeMsgSeq) " +
            "OR (m.timestamp = :beforeTimestamp AND m.msgSeq = :beforeMsgSeq AND m.msgId < :beforeMsgId)) " +
            "ORDER BY m.timestamp DESC, m.msgSeq DESC, m.msgId DESC LIMIT :limit"
    )
    suspend fun getMessagesBefore(
        accountId: String,
        chatId: String,
        chatType: Int,
        beforeTimestamp: Long,
        beforeMsgSeq: Long,
        beforeMsgId: String,
        limit: Int
    ): List<CachedMessageRow>

    @Query("SELECT MAX(updateTimestamp) FROM cached_messages WHERE accountId = :accountId AND chatId = :chatId AND chatType = :chatType")
    suspend fun latestUpdateTimestamp(accountId: String, chatId: String, chatType: Int): Long?

    @Query("SELECT msgId FROM cached_messages WHERE accountId = :accountId AND chatId = :chatId AND chatType = :chatType ORDER BY timestamp ASC, msgSeq ASC LIMIT -1 OFFSET :keep")
    suspend fun oldestMessageIdsAfter(accountId: String, chatId: String, chatType: Int, keep: Int): List<String>

    @Query("SELECT msgId FROM cached_messages WHERE accountId = :accountId ORDER BY timestamp ASC, msgSeq ASC LIMIT -1 OFFSET :keep")
    suspend fun accountOldestMessageIdsAfter(accountId: String, keep: Int): List<String>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertMessages(items: List<CachedMessageEntity>)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertSenders(items: List<CachedMessageSenderEntity>)

    @Query("DELETE FROM cached_messages WHERE accountId = :accountId AND msgId IN (:messageIds)")
    suspend fun deleteMessages(accountId: String, messageIds: List<String>)

    @Transaction
    suspend fun upsertBatch(
        messages: List<CachedMessageEntity>,
        senders: List<CachedMessageSenderEntity>
    ) {
        if (senders.isNotEmpty()) upsertSenders(senders)
        if (messages.isNotEmpty()) upsertMessages(messages)
    }
}

@Dao
interface PayloadCacheDao {
    @Query("SELECT * FROM cached_payloads WHERE accountId = :accountId AND kind = :kind AND scope = :scope LIMIT 1")
    suspend fun get(accountId: String, kind: String, scope: String = ""): CachedPayloadEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun put(entity: CachedPayloadEntity)

    @Query("DELETE FROM cached_payloads WHERE accountId = :accountId AND kind = :kind AND scope = :scope")
    suspend fun delete(accountId: String, kind: String, scope: String = "")

    @Query("DELETE FROM cached_payloads WHERE accountId = :accountId AND kind = :kind AND scope LIKE :scopePrefix")
    suspend fun deleteByScopePrefix(accountId: String, kind: String, scopePrefix: String)
}

@Dao
interface CacheStateDao {
    @Query("SELECT * FROM cache_sync_state WHERE accountId = :accountId AND key = :key LIMIT 1")
    suspend fun get(accountId: String, key: String): CacheSyncStateEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun put(entity: CacheSyncStateEntity)

    @Query(
        "SELECT * FROM cache_sync_state WHERE accountId = :accountId " +
            "AND substr(`key`, 1, length(:prefix)) = :prefix AND updatedAt >= :updatedAfter"
    )
    suspend fun getRecentByPrefix(
        accountId: String,
        prefix: String,
        updatedAfter: Long
    ): List<CacheSyncStateEntity>

    @Query("DELETE FROM cache_sync_state WHERE accountId = :accountId AND `key` = :key")
    suspend fun delete(accountId: String, key: String)

    @Query(
        "DELETE FROM cache_sync_state WHERE accountId = :accountId " +
            "AND substr(`key`, 1, length(:prefix)) = :prefix AND updatedAt < :updatedBefore"
    )
    suspend fun deleteOlderThanByPrefix(
        accountId: String,
        prefix: String,
        updatedBefore: Long
    )

    @Query("DELETE FROM cached_conversations WHERE accountId = :accountId")
    suspend fun deleteConversations(accountId: String)

    @Query("DELETE FROM cached_sticky_conversations WHERE accountId = :accountId")
    suspend fun deleteSticky(accountId: String)

    @Query("DELETE FROM cached_messages WHERE accountId = :accountId")
    suspend fun deleteMessages(accountId: String)

    @Query("DELETE FROM cached_message_senders WHERE accountId = :accountId")
    suspend fun deleteSenders(accountId: String)

    @Query("DELETE FROM cached_payloads WHERE accountId = :accountId")
    suspend fun deletePayloads(accountId: String)

    @Query("DELETE FROM cache_sync_state WHERE accountId = :accountId")
    suspend fun deleteStates(accountId: String)

    @Transaction
    suspend fun deleteAccount(accountId: String) {
        deleteConversations(accountId)
        deleteSticky(accountId)
        deleteMessages(accountId)
        deleteSenders(accountId)
        deletePayloads(accountId)
        deleteStates(accountId)
    }
}

@Database(
    entities = [
        CachedConversationEntity::class,
        CachedStickyEntity::class,
        CachedMessageEntity::class,
        CachedMessageSenderEntity::class,
        CachedPayloadEntity::class,
        CacheSyncStateEntity::class
    ],
    version = 2,
    exportSchema = true
)
abstract class LocalCacheDatabase : RoomDatabase() {
    abstract fun conversations(): ConversationCacheDao
    abstract fun messages(): MessageCacheDao
    abstract fun payloads(): PayloadCacheDao
    abstract fun states(): CacheStateDao

    companion object {
        val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "ALTER TABLE cached_conversations " +
                        "ADD COLUMN listPosition INTEGER NOT NULL DEFAULT 2147483647"
                )
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS index_cached_conversations_accountId_listPosition " +
                        "ON cached_conversations(accountId, listPosition)"
                )
                db.execSQL(
                    "ALTER TABLE cached_sticky_conversations " +
                        "ADD COLUMN listPosition INTEGER NOT NULL DEFAULT 2147483647"
                )
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS index_cached_sticky_conversations_accountId_listPosition " +
                        "ON cached_sticky_conversations(accountId, listPosition)"
                )
                db.execSQL("DELETE FROM cache_sync_state WHERE `key` = 'conversation_md5'")
            }
        }

        @Volatile private var instance: LocalCacheDatabase? = null

        fun getInstance(context: Context): LocalCacheDatabase = instance ?: synchronized(this) {
            instance ?: Room.databaseBuilder(
                context.applicationContext,
                LocalCacheDatabase::class.java,
                "murexide-cache.db"
            )
                .setJournalMode(JournalMode.WRITE_AHEAD_LOGGING)
                .addMigrations(MIGRATION_1_2)
                .build()
                .also { instance = it }
        }
    }
}
