package com.example.moci.update

import android.content.Context
import android.annotation.SuppressLint
import org.json.JSONObject

// Download records must be durable before the process can leave; commit is used from Dispatchers.IO.
@SuppressLint("ApplySharedPref")
class AndroidUpdatePreferences(context: Context, name: String = "updates") : UpdatePreferences {
    private val preferences = context.getSharedPreferences(name, Context.MODE_PRIVATE)

    override var autoCheckEnabled: Boolean
        get() = preferences.getBoolean("auto_check", true)
        set(value) { preferences.edit().putBoolean("auto_check", value).apply() }

    override fun readDownload(): DownloadRecord? {
        val value = preferences.getString("download", null) ?: return null
        try {
            val json = JSONObject(value)
            val release = ReleaseInfo(
                json.getString("tag"), json.getString("notes"), json.getString("asset"),
                json.getString("url"), json.getLong("size"),
                json.optString("digest").takeIf(String::isNotBlank),
                json.optString("checksums").takeIf(String::isNotBlank),
            )
            SemanticVersion.parse(release.tag)
            return DownloadRecord(json.getLong("id"), release, json.getString("sha256"), json.getString("file"))
        } catch (e: Exception) {
            preferences.edit().remove("download").commit()
            throw UpdateException("上次下载记录异常，请重新下载", e)
        }
    }

    override fun saveDownload(record: DownloadRecord?) {
        val edit = preferences.edit()
        if (record == null) {
            edit.remove("download")
        } else {
            val release = record.release
            val json = JSONObject()
                .put("id", record.id).put("file", record.fileName).put("sha256", record.sha256)
                .put("tag", release.tag).put("notes", release.notes).put("asset", release.assetName)
                .put("url", release.downloadUrl).put("size", release.size)
                .put("digest", release.sha256 ?: "").put("checksums", release.checksumUrl ?: "")
            edit.putString("download", json.toString())
        }
        if (!edit.commit()) throw UpdateException("下载记录保存失败，请重试")
    }
}
