package com.example.bluemesh.data

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import com.example.bluemesh.data.models.ChatMessage

class OfflineQueueDbHelper(context: Context) : SQLiteOpenHelper(context, DATABASE_NAME, null, DATABASE_VERSION) {

    companion object {
        private const val DATABASE_NAME = "bluemesh_offline.db"
        private const val DATABASE_VERSION = 3
    }

    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL("CREATE TABLE Contacts (\n    uuid TEXT PRIMARY KEY,\n    name TEXT,\n    session_key TEXT,\n    is_official INTEGER DEFAULT 0\n)")
        db.execSQL("CREATE TABLE QueuedMessages (\n    id INTEGER PRIMARY KEY AUTOINCREMENT,\n    contact_uuid TEXT,\n    text TEXT,\n    timestamp INTEGER,\n    status TEXT,\n    is_from_me INTEGER\n)")
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        var currentVersion = oldVersion
        if (currentVersion < 2) {
            try {
                db.execSQL("ALTER TABLE Contacts ADD COLUMN session_key TEXT")
            } catch (e: Exception) {
                // Ignore if already exists
            }
            currentVersion = 2
        }
        if (currentVersion < 3) {
            try {
                db.execSQL("ALTER TABLE Contacts ADD COLUMN is_official INTEGER DEFAULT 0")
            } catch (e: Exception) {
                // Ignore if already exists
            }
            currentVersion = 3
        }
    }

    fun saveContact(uuid: String, name: String, isOfficial: Boolean) {
        val db = writableDatabase
        val values = ContentValues().apply {
            put("uuid", uuid)
            put("name", name)
            put("is_official", if (isOfficial) 1 else 0)
        }
        db.insertWithOnConflict("Contacts", null, values, SQLiteDatabase.CONFLICT_REPLACE)
    }

    fun deleteContact(uuid: String) {
        val db = writableDatabase
        db.delete("Contacts", "uuid = ?", arrayOf(uuid))
    }

    fun isContact(uuid: String): Boolean {
        val db = readableDatabase
        val cursor = db.rawQuery("SELECT 1 FROM Contacts WHERE uuid = ?", arrayOf(uuid))
        val exists = cursor.count > 0
        cursor.close()
        return exists
    }

    fun getContactsList(): List<Triple<String, String, Boolean>> {
        val list = mutableListOf<Triple<String, String, Boolean>>()
        val db = readableDatabase
        val cursor = db.rawQuery("SELECT uuid, name, is_official FROM Contacts", null)
        if (cursor.moveToFirst()) {
            do {
                val uuid = cursor.getString(0)
                val name = cursor.getString(1)
                val isOfficial = cursor.getInt(2) == 1
                list.add(Triple(uuid, name, isOfficial))
            } while (cursor.moveToNext())
        }
        cursor.close()
        return list
    }

    fun insertMessage(contactUuid: String, text: String, timestamp: Long, status: String, isFromMe: Boolean): Long {
        val db = writableDatabase
        val values = ContentValues().apply {
            put("contact_uuid", contactUuid)
            put("text", text)
            put("timestamp", timestamp)
            put("status", status)
            put("is_from_me", if (isFromMe) 1 else 0)
        }
        return db.insert("QueuedMessages", null, values)
    }

    fun getMessagesForContact(contactUuid: String): List<ChatMessage> {
        val list = mutableListOf<ChatMessage>()
        val db = readableDatabase
        val cursor = db.rawQuery(
            "SELECT text, is_from_me, timestamp, status FROM QueuedMessages WHERE contact_uuid = ? ORDER BY timestamp ASC",
            arrayOf(contactUuid)
        )
        if (cursor.moveToFirst()) {
            do {
                val text = cursor.getString(0)
                val isFromMe = cursor.getInt(1) == 1
                val timestamp = cursor.getLong(2)
                val status = cursor.getString(3)
                list.add(ChatMessage(text, isFromMe, timestamp, status))
            } while (cursor.moveToNext())
        }
        cursor.close()
        return list
    }

    fun getPendingMessages(contactUuid: String): List<Triple<Int, String, Long>> {
        val list = mutableListOf<Triple<Int, String, Long>>()
        val db = readableDatabase
        val cursor = db.rawQuery(
            "SELECT id, text, timestamp FROM QueuedMessages WHERE contact_uuid = ? AND status = 'PENDING' ORDER BY timestamp ASC",
            arrayOf(contactUuid)
        )
        if (cursor.moveToFirst()) {
            do {
                val id = cursor.getInt(0)
                val text = cursor.getString(1)
                val timestamp = cursor.getLong(2)
                list.add(Triple(id, text, timestamp))
            } while (cursor.moveToNext())
        }
        cursor.close()
        return list
    }

    fun markMessageAsSent(id: Int) {
        val db = writableDatabase
        val values = ContentValues().apply {
            put("status", "SENT")
        }
        db.update("QueuedMessages", values, "id = ?", arrayOf(id.toString()))
    }

    fun saveSessionKey(uuid: String, sessionKey: String) {
        val db = writableDatabase
        val values = ContentValues().apply {
            put("session_key", sessionKey)
        }
        db.update("Contacts", values, "uuid = ?", arrayOf(uuid))
    }

    fun getSessionKey(uuid: String): String? {
        val db = readableDatabase
        val cursor = db.rawQuery("SELECT session_key FROM Contacts WHERE uuid = ?", arrayOf(uuid))
        var key: String? = null
        if (cursor.moveToFirst()) {
            key = cursor.getString(0)
        }
        cursor.close()
        return key
    }
}
