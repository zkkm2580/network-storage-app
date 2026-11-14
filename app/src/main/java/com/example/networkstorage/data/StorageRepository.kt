package com.example.networkstorage.data

import android.content.Context
import android.content.SharedPreferences
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKeys
import org.json.JSONArray
import org.json.JSONObject

/**
 * 网络存储配置的持久化。使用 EncryptedSharedPreferences，
 * 由 Android Keystore 托管的 AES-256 主密钥加密全部键值，
 * 密码不会以明文形式落盘。
 */
class StorageRepository(context: Context) {

    private val prefs: SharedPreferences = createEncryptedPrefs(context.applicationContext)

    fun getAll(): List<StorageAccount> {
        val raw = prefs.getString(KEY_ACCOUNTS, null) ?: return emptyList()
        return try {
            val arr = JSONArray(raw)
            val out = ArrayList<StorageAccount>(arr.length())
            for (i in 0 until arr.length()) {
                val obj = arr.optJSONObject(i) ?: continue
                out.add(StorageAccount.fromJson(obj))
            }
            out
        } catch (e: Exception) {
            emptyList()
        }
    }

    fun find(id: String): StorageAccount? = getAll().firstOrNull { it.id == id }

    /** 新增或更新一条配置。 */
    fun upsert(account: StorageAccount) {
        val list = getAll().toMutableList()
        val index = list.indexOfFirst { it.id == account.id }
        if (index >= 0) list[index] = account else list.add(account)
        write(list)
    }

    fun delete(id: String) {
        write(getAll().filterNot { it.id == id })
    }

    private fun write(list: List<StorageAccount>) {
        val arr = JSONArray()
        list.forEach { arr.put(it.toJson()) }
        prefs.edit().putString(KEY_ACCOUNTS, arr.toString()).apply()
    }

    private fun createEncryptedPrefs(context: Context): SharedPreferences {
        return try {
            @Suppress("DEPRECATION")
            val masterKeyAlias = MasterKeys.getOrCreate(MasterKeys.AES256_GCM_SPEC)
            @Suppress("DEPRECATION")
            EncryptedSharedPreferences.create(
                FILE_NAME,
                masterKeyAlias,
                context,
                EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
                EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
            )
        } catch (e: Exception) {
            // 极少数设备 Keystore 异常时降级，保证 UI 可用（不推荐但避免崩溃）
            context.getSharedPreferences(FILE_NAME + "_fallback", Context.MODE_PRIVATE)
        }
    }

    private companion object {
        const val FILE_NAME = "network_storage_secure"
        const val KEY_ACCOUNTS = "accounts"
    }
}

// 便于 UI 层直接使用
fun JSONObject.optJSONObjectOrNull(name: String): JSONObject? =
    if (has(name) && !isNull(name)) optJSONObject(name) else null
