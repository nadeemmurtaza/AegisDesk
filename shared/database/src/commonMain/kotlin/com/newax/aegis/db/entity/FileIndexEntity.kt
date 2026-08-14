package com.newax.aegis.db.entity

import com.newax.aegis.db.currentTimeMillis

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Fts4
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "file_objects",
    indices = [
        Index(value = ["path"], unique = true),
        Index("sha256"),
        Index("pHash"),
        Index("extension"),
        Index("folder"),
        Index("mimeType"),
        Index("sourceApp"),
        Index("modifiedMs"),
        Index("createdMs"),
        Index("receivedMs"),
        Index("indexState"),
        Index("graphEntityId")
    ]
)
data class FileObject(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val path: String,
    @ColumnInfo(defaultValue = "''")
    val contentUriString: String = "",
    @ColumnInfo(defaultValue = "0")
    val mediaStoreId: Long = 0,
    val filename: String,
    val extension: String,
    val mimeType: String,
    val sizeBytes: Long,
    @ColumnInfo(defaultValue = "0")
    val createdMs: Long = 0,
    @ColumnInfo(defaultValue = "0")
    val modifiedMs: Long = 0,
    @ColumnInfo(defaultValue = "0")
    val receivedMs: Long = 0,
    @ColumnInfo(defaultValue = "0")
    val lastOpenedMs: Long = 0,
    @ColumnInfo(defaultValue = "''")
    val sourceApp: String = "",
    @ColumnInfo(defaultValue = "''")
    val folder: String = "",
    @ColumnInfo(defaultValue = "''")
    val sha256: String = "",
    @ColumnInfo(defaultValue = "''")
    val pHash: String = "",
    @ColumnInfo(defaultValue = "''")
    val metadataJson: String = "",
    val thumbnailPath: String? = null,
    @ColumnInfo(defaultValue = "''")
    val entitiesJson: String = "",
    @ColumnInfo(defaultValue = "''")
    val conceptsJson: String = "",
    val graphEntityId: Long? = null,
    val embeddingId: Long? = null,
    @ColumnInfo(defaultValue = "0")
    val indexState: Int = INDEX_STATE_BARE,
    @ColumnInfo(defaultValue = "0")
    val isDuplicate: Boolean = false,
    val canonicalId: Long? = null,
    // ── sync metadata (docs/SYNC_DESIGN.md §4): LWW merge ordering + tombstone ──
    @ColumnInfo(defaultValue = "0")
    val syncHcWall: Long = 0,
    @ColumnInfo(defaultValue = "0")
    val syncHcCounter: Long = 0,
    @ColumnInfo(defaultValue = "''")
    val syncDeviceId: String = "",
    @ColumnInfo(defaultValue = "0")
    val syncTombstone: Boolean = false
) {
    companion object {
        const val INDEX_STATE_BARE     = 0   // metadata + hash only
        const val INDEX_STATE_TEXT     = 1   // text extracted
        const val INDEX_STATE_ENTITIES = 2   // entity extraction done
        const val INDEX_STATE_VISUAL   = 4   // pHash + thumbnail
        const val INDEX_STATE_EMBEDDED = 8   // embedding done
        const val INDEX_STATE_FULL     = 15
    }
}

@Entity(tableName = "file_text_content")
data class FileTextContent(
    @PrimaryKey val fileId: Long,
    val text: String,
    @ColumnInfo(defaultValue = "''")
    val language: String = "",
    @ColumnInfo(defaultValue = "0")
    val pageCount: Int = 0,
    @ColumnInfo(defaultValue = "0")
    val wordCount: Int = 0,
    val extractedMs: Long = currentTimeMillis()
)

@Fts4(contentEntity = Any::class)
@Entity(tableName = "file_text_fts")
data class FileTextFts(
    @PrimaryKey @ColumnInfo(name = "rowid") val rowId: Long = 0,
    val text: String
)

@Entity(
    tableName = "file_entity_links",
    primaryKeys = ["fileId", "entityLabel"],
    indices = [Index("entityLabel"), Index("entityType"), Index("graphEntityId")]
)
data class FileEntityLink(
    val fileId: Long,
    val entityLabel: String,
    val entityType: String,       // PERSON | COMPANY | PROJECT | INVOICE | PHONE | EMAIL | DATE | KEYWORD
    val graphEntityId: Long? = null
)
