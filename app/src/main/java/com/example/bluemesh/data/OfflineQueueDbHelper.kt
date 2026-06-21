package com.example.bluemesh.data

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import com.example.bluemesh.data.models.ChatMessage

class OfflineQueueDbHelper(context: Context) : SQLiteOpenHelper(context, DATABASE_NAME, null, DATABASE_VERSION) {

    init {
        setWriteAheadLoggingEnabled(true)
    }

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

    fun resolveCanonicalUuid(contactUuid: String): String {
        val contacts = getContactsList()
        val match = contacts.find {
            com.example.bluemesh.utils.uuidsMatch(it.first, contactUuid)
        }
        return match?.first ?: contactUuid
    }

    fun getMessagesForContact(contactUuid: String): List<ChatMessage> {
        val resolvedUuid = resolveCanonicalUuid(contactUuid)
        val list = mutableListOf<ChatMessage>()
        val db = readableDatabase
        val cursor = db.rawQuery(
            "SELECT text, is_from_me, timestamp, status FROM QueuedMessages WHERE contact_uuid = ? ORDER BY timestamp ASC",
            arrayOf(resolvedUuid)
        )
        var hasData = cursor.moveToFirst()
        if (!hasData && resolvedUuid != contactUuid) {
            cursor.close()
            val cursorFallback = db.rawQuery(
                "SELECT text, is_from_me, timestamp, status FROM QueuedMessages WHERE contact_uuid = ? ORDER BY timestamp ASC",
                arrayOf(contactUuid)
            )
            if (cursorFallback.moveToFirst()) {
                do {
                    val text = cursorFallback.getString(0)
                    val isFromMe = cursorFallback.getInt(1) == 1
                    val timestamp = cursorFallback.getLong(2)
                    val status = cursorFallback.getString(3)
                    list.add(ChatMessage(text, isFromMe, timestamp, status))
                } while (cursorFallback.moveToNext())
            }
            cursorFallback.close()
            return list
        }
        if (hasData) {
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
        val resolvedUuid = resolveCanonicalUuid(contactUuid)
        val list = mutableListOf<Triple<Int, String, Long>>()
        val db = readableDatabase
        val cursor = db.rawQuery(
            "SELECT id, text, timestamp FROM QueuedMessages WHERE contact_uuid = ? AND status = 'PENDING' ORDER BY timestamp ASC",
            arrayOf(resolvedUuid)
        )
        var hasData = cursor.moveToFirst()
        if (!hasData && resolvedUuid != contactUuid) {
            cursor.close()
            val cursorFallback = db.rawQuery(
                "SELECT id, text, timestamp FROM QueuedMessages WHERE contact_uuid = ? AND status = 'PENDING' ORDER BY timestamp ASC",
                arrayOf(contactUuid)
            )
            if (cursorFallback.moveToFirst()) {
                do {
                    val id = cursorFallback.getInt(0)
                    val text = cursorFallback.getString(1)
                    val timestamp = cursorFallback.getLong(2)
                    list.add(Triple(id, text, timestamp))
                } while (cursorFallback.moveToNext())
            }
            cursorFallback.close()
            return list
        }
        if (hasData) {
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

    fun isDuplicateMessage(contactUuid: String, timestamp: Long): Boolean {
        val resolvedUuid = resolveCanonicalUuid(contactUuid)
        val db = readableDatabase
        val cursor = db.rawQuery(
            "SELECT 1 FROM QueuedMessages WHERE contact_uuid = ? AND timestamp = ? AND is_from_me = 0",
            arrayOf(resolvedUuid, timestamp.toString())
        )
        val exists = cursor.count > 0
        cursor.close()
        return exists
    }

    fun updateMessageContactUuid(oldUuid: String, newUuid: String) {
        val db = writableDatabase
        val values = ContentValues().apply {
            put("contact_uuid", newUuid)
        }
        db.update("QueuedMessages", values, "contact_uuid = ?", arrayOf(oldUuid))
    }

    fun markMessageAsSent(id: Int) {
        val db = writableDatabase
        val values = ContentValues().apply {
            put("status", "SENT")
        }
        db.update("QueuedMessages", values, "id = ?", arrayOf(id.toString()))
    }

    fun markMessageAsSent(timestamp: Long) {
        val db = writableDatabase
        val values = ContentValues().apply {
            put("status", "SENT")
        }
        db.update("QueuedMessages", values, "timestamp = ? AND is_from_me = 1", arrayOf(timestamp.toString()))
    }

    fun saveSessionKey(uuid: String, sessionKey: String) {
        val db = writableDatabase
        val cursor = db.rawQuery("SELECT name FROM Contacts WHERE uuid = ?", arrayOf(uuid))
        val exists = cursor.count > 0
        cursor.close()

        val values = ContentValues().apply {
            put("session_key", sessionKey)
        }
        if (exists) {
            db.update("Contacts", values, "uuid = ?", arrayOf(uuid))
        } else {
            values.put("uuid", uuid)
            values.put("name", "Temp_Peer")
            db.insert("Contacts", null, values)
        }
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

    fun upgradeContactUuid(oldUuid: String, newUuid: String) {
        val db = writableDatabase
        db.beginTransaction()
        try {
            val checkCursor = db.rawQuery("SELECT session_key, name, is_official FROM Contacts WHERE uuid = ?", arrayOf(newUuid))
            val newExists = checkCursor.count > 0
            var oldSessionKey: String? = null
            
            val oldCursor = db.rawQuery("SELECT session_key, name, is_official FROM Contacts WHERE uuid = ?", arrayOf(oldUuid))
            if (oldCursor.moveToFirst()) {
                oldSessionKey = oldCursor.getString(0)
            }
            oldCursor.close()

            if (newExists) {
                if (checkCursor.moveToFirst()) {
                    val newSessionKey = checkCursor.getString(0)
                    if (newSessionKey.isNullOrEmpty() && !oldSessionKey.isNullOrEmpty()) {
                        val values = ContentValues().apply {
                            put("session_key", oldSessionKey)
                        }
                        db.update("Contacts", values, "uuid = ?", arrayOf(newUuid))
                    }
                }
                db.delete("Contacts", "uuid = ?", arrayOf(oldUuid))
            } else {
                val values = ContentValues().apply {
                    put("uuid", newUuid)
                }
                db.update("Contacts", values, "uuid = ?", arrayOf(oldUuid))
            }
            checkCursor.close()
            
            updateMessageContactUuid(oldUuid, newUuid)
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
    }

    fun upgradeContactIfNeeded(newUuid: String) {
        val db = writableDatabase
        db.beginTransaction()
        try {
            // Find any contact in the database whose UUID matches newUuid (via uuidsMatch) but is not identical
            val contactsToUpgrade = mutableListOf<String>()
            val cursor = db.rawQuery("SELECT uuid FROM Contacts", null)
            if (cursor.moveToFirst()) {
                do {
                    val existingUuid = cursor.getString(0)
                    if (existingUuid != newUuid && com.example.bluemesh.utils.uuidsMatch(existingUuid, newUuid)) {
                        contactsToUpgrade.add(existingUuid)
                    }
                } while (cursor.moveToNext())
            }
            cursor.close()

            for (oldUuid in contactsToUpgrade) {
                val checkCursor = db.rawQuery("SELECT session_key, name, is_official FROM Contacts WHERE uuid = ?", arrayOf(newUuid))
                val newExists = checkCursor.count > 0
                var oldSessionKey: String? = null
                
                val oldCursor = db.rawQuery("SELECT session_key, name, is_official FROM Contacts WHERE uuid = ?", arrayOf(oldUuid))
                if (oldCursor.moveToFirst()) {
                    oldSessionKey = oldCursor.getString(0)
                }
                oldCursor.close()

                if (newExists) {
                    if (checkCursor.moveToFirst()) {
                        val newSessionKey = checkCursor.getString(0)
                        if (newSessionKey.isNullOrEmpty() && !oldSessionKey.isNullOrEmpty()) {
                            val values = ContentValues().apply {
                                put("session_key", oldSessionKey)
                            }
                            db.update("Contacts", values, "uuid = ?", arrayOf(newUuid))
                        }
                    }
                    db.delete("Contacts", "uuid = ?", arrayOf(oldUuid))
                } else {
                    val values = ContentValues().apply {
                        put("uuid", newUuid)
                    }
                    db.update("Contacts", values, "uuid = ?", arrayOf(oldUuid))
                }
                checkCursor.close()
                
                updateMessageContactUuid(oldUuid, newUuid)
            }

            // Also update any messages in QueuedMessages that might be associated with a matching short UUID,
            // even if the contact was not saved in the Contacts table
            val messagesUuidsToUpgrade = mutableListOf<String>()
            val msgCursor = db.rawQuery("SELECT DISTINCT contact_uuid FROM QueuedMessages", null)
            if (msgCursor.moveToFirst()) {
                do {
                    val msgUuid = msgCursor.getString(0)
                    if (msgUuid != newUuid && com.example.bluemesh.utils.uuidsMatch(msgUuid, newUuid)) {
                        messagesUuidsToUpgrade.add(msgUuid)
                    }
                } while (msgCursor.moveToNext())
            }
            msgCursor.close()

            for (oldUuid in messagesUuidsToUpgrade) {
                updateMessageContactUuid(oldUuid, newUuid)
            }

            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
    }
}
