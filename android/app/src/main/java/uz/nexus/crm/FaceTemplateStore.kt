package uz.nexus.crm

import android.content.Context
import android.util.Base64
import org.json.JSONArray

/**
 * Face template storage uses Android Keystore-backed EncryptedSharedPreferences.
 * Raw camera frames are never persisted by this class.
 */
object FaceTemplateStore {
    private const val KEY = "local_face_template_v1"

    fun save(context: Context, embedding: FloatArray) {
        require(embedding.size == 128) { "Yuz shabloni 128 qiymatdan iborat bo‘lishi kerak." }
        val json = JSONArray()
        embedding.forEach { json.put(it.toDouble()) }
        secureSessionPrefs(context).edit().putString(KEY, json.toString()).apply()
    }

    fun read(context: Context): FloatArray? {
        val raw = secureSessionPrefs(context).getString(KEY, null) ?: return null
        return try {
            val json = JSONArray(raw)
            if (json.length() != 128) return null
            FloatArray(json.length()) { json.getDouble(it).toFloat() }
        } catch (_: Exception) {
            null
        }
    }

    fun exists(context: Context): Boolean = read(context) != null

    fun clear(context: Context) {
        secureSessionPrefs(context).edit().remove(KEY).apply()
    }
}
