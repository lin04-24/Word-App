package com.example.moci.dictionary

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import java.io.File
import java.util.Locale

/** Read-only lookup against the standalone ECDICT SQLite file in app-private storage. */
class EcdictDictionary private constructor(private val file: File) {
    constructor(context: Context) : this(File(context.applicationContext.filesDir, "dictionary/ecdict.sqlite"))

    fun lookup(word: String): String? = lookupAll(listOf(word))[word.trim().lowercase(Locale.ROOT)]

    fun lookupAll(words: Collection<String>): Map<String, String> {
        val file = file
        val requested = words.map { it.trim() }.filter(String::isNotEmpty)
            .distinctBy { it.lowercase(Locale.ROOT) }
        if (!file.isFile || requested.isEmpty()) return emptyMap()

        val database = SQLiteDatabase.openDatabase(file.absolutePath, null, SQLiteDatabase.OPEN_READONLY)
        try {
            val results = HashMap<String, String>()
            for (batch in requested.chunked(MAX_QUERY_WORDS)) {
                val placeholders = List(batch.size) { "?" }.joinToString(",")
                database.rawQuery(
                    "SELECT word, translation FROM stardict WHERE word IN ($placeholders)",
                    batch.toTypedArray(),
                ).use { cursor ->
                    val wordIndex = cursor.getColumnIndexOrThrow("word")
                    val meaningIndex = cursor.getColumnIndexOrThrow("translation")
                    while (cursor.moveToNext()) {
                        val meaning = cursor.getString(meaningIndex)?.trim().orEmpty()
                        if (meaning.isNotEmpty()) {
                            results[cursor.getString(wordIndex).lowercase(Locale.ROOT)] = meaning
                        }
                    }
                }
            }
            return results
        } finally {
            database.close()
        }
    }

    fun isInstalled(): Boolean = file.isFile && file.length() > SQLITE_HEADER.size && runCatching {
        file.inputStream().use { input ->
            val header = ByteArray(SQLITE_HEADER.size)
            input.read(header) == header.size && header.contentEquals(SQLITE_HEADER)
        }
    }.getOrDefault(false)

    companion object {
        private const val MAX_QUERY_WORDS = 400
        private val SQLITE_HEADER = "SQLite format 3\u0000".toByteArray(Charsets.US_ASCII)

        internal fun atFile(file: File) = EcdictDictionary(file)
    }
}
