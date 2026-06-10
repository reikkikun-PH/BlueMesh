package com.example.bluemesh.data

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import com.example.bluemesh.data.models.ChatMessage

class OfflineQueueDbHelper(context: Context) : SQLiteOpenHelper(context, DATABASE_NAME, null, DATABASE_VERSION) {

    companion object {
        private const val DATABASE_NAME = "bluemesh_offline.db"
        private const val DATABASE_VERSION = 1
    }

    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL("CREATE TABLE Contacts (\n    uuid TEXT PRIMARY KEY,\n    name TEXT\n)")
        db.execSQL("CREATE TABLE QueuedMessages (\n    id INTEGER PRIMARY KEY AUTOINCREMENT,\n    contact_uuid TEXT,\n    text TEXT,\n    timestamp INTEGER,\n    status TEXT,\n    is_from_me INTEGER\n)")
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        db.execSQL("DROP TABLE IF EXISTS Contacts")
        db.execSQL("DROP TABLE IF EXISTS QueuedMessages")
        onCreate(db)
    }

    fun saveContact(uuid: String, name: String) {
        val db = writableDatabase
        val values = ContentValues().apply {
            put("uuid", uuid)
            put("name", name)
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

    fun getContactsList(): List<Pair<String, String>> {
        val list = mutableListOf<Pair<String, String>>()
        val db = readableDatabase
        val cursor = db.rawQuery("SELECT uuid, name FROM Contacts", null)
        if (cursor.moveToFirst()) {
            do {
                val uuid = cursor.getString(0)
                val name = cursor.getString(1)
                list.add(Pair(uuid, name))
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

    fun getPendingMessages(contactUuid: String): List<Pair<Int, String>> {
        val list = mutableListOf<Pair<Int, String>>()
        val db = readableDatabase
        val cursor = db.rawQuery(
            "SELECT id, text FROM QueuedMessages WHERE contact_uuid = ? AND status = 'PENDING' ORDER BY timestamp ASC",
            arrayOf(contactUuid)
        )
        if (cursor.moveToFirst()) {
            do {
                val id = cursor.getInt(0)
                val text = cursor.getString(1)
                list.add(Pair(id, text))
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
}
