package com.bazuka.vozcomida

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import java.util.Calendar

data class Entry(
    val id: Long,
    val ts: Long,
    val meal: String,
    val name: String,
    val qty: String,
    val kcal: Int?
)

class Db(context: Context) : SQLiteOpenHelper(context, "comidas.db", null, 1) {

    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL(
            "CREATE TABLE entries (id INTEGER PRIMARY KEY AUTOINCREMENT, ts INTEGER NOT NULL, " +
                "meal TEXT NOT NULL, name TEXT NOT NULL, qty TEXT NOT NULL, kcal INTEGER)"
        )
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {}

    fun insert(ts: Long, meal: String, item: ParsedItem): Long {
        val cv = ContentValues().apply {
            put("ts", ts)
            put("meal", meal)
            put("name", item.name)
            put("qty", item.qty)
            if (item.kcal != null) put("kcal", item.kcal) else putNull("kcal")
        }
        return writableDatabase.insert("entries", null, cv)
    }

    fun update(e: Entry) {
        val cv = ContentValues().apply {
            put("ts", e.ts); put("meal", e.meal); put("name", e.name); put("qty", e.qty)
            if (e.kcal != null) put("kcal", e.kcal) else putNull("kcal")
        }
        writableDatabase.update("entries", cv, "id = ?", arrayOf(e.id.toString()))
    }

    fun delete(id: Long) {
        writableDatabase.delete("entries", "id = ?", arrayOf(id.toString()))
    }

    fun deleteLast(): Boolean {
        readableDatabase.rawQuery("SELECT id FROM entries ORDER BY id DESC LIMIT 1", null).use {
            if (!it.moveToFirst()) return false
            delete(it.getLong(0))
            return true
        }
    }

    fun forDay(dayStart: Calendar): List<Entry> {
        val start = (dayStart.clone() as Calendar).apply {
            set(Calendar.HOUR_OF_DAY, 0); set(Calendar.MINUTE, 0)
            set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0)
        }.timeInMillis
        val end = start + 24L * 3600 * 1000
        return query("WHERE ts >= ? AND ts < ?", arrayOf(start.toString(), end.toString()))
    }

    fun all(): List<Entry> = query("", emptyArray())

    private fun query(where: String, args: Array<String>): List<Entry> {
        val out = mutableListOf<Entry>()
        readableDatabase.rawQuery(
            "SELECT id, ts, meal, name, qty, kcal FROM entries $where ORDER BY ts ASC", args
        ).use { c ->
            while (c.moveToNext()) {
                out += Entry(
                    c.getLong(0), c.getLong(1), c.getString(2), c.getString(3), c.getString(4),
                    if (c.isNull(5)) null else c.getInt(5)
                )
            }
        }
        return out
    }
}
