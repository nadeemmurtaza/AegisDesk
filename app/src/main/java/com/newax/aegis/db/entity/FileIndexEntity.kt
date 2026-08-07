package com.newax.aegis.db.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Fts4
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "file_objects",
    indices = [
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
    val filename: String,
    val extension: String,
    val mimeType: String,
    val sizeBytes: Long,
    val createdMs: Long = 0,
    val modifiedMs: Long = 0,
    val receivedMs: Long = 0,
    val lastOpenedMs: Long = 0,
    val sourceApp: String = "",
    val folder: String = "",
    val sha256: String = "",
    val pHash: String = "",          // 16-char hex (64-bit average hash)
    val metadataJson: String = "",   // EXIF, GPS, camera, page count, author
    val thumbnailPath: String? = null,
    val entitiesJson: String = "",   // JSON array of entity labels
    val conceptsJson: String = "",   // JSON array of concept tags
    val graphEntityId: Long? = null, // this file's node in GraphStore
    val embeddingId: Long? = null,
    val indexState: Int = INDEX_STATE_BARE,
    val isDuplicate: Boolean = false, // true if another file has same sha256
    val canonicalId: Long? = null    // points to the canonical copy if isDuplicate
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
    val language: String = "",
    val pageCount: Int = 0,
    val wordCount: Int = 0,
    val extractedMs: Long = System.currentTimeMillis()
)

@Fts4
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
