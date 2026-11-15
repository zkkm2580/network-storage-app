package com.example.networkstorage.data

import android.content.Context
import android.content.SharedPreferences
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKeys
import org.json.JSONArray
import org.json.JSONObject

/**
 * 网络存储配置的持久化。全部键值由 Android Keystore 托管的 AES-256 主密钥加密。
 * 若 Keystore 不可用，只退化为「内存态」并对外暴露 isMemoryOnly，
 * 绝不把密码以明文写入磁盘。
 */
class StorageRepository(context: Context) {

    @Volatile
    private var memoryOnly: String? = null

    private val prefs: SharedPreferences? = try {
        createEncryptedPrefs(context.applicationContext)
    } catch (e: Exception) {
        null
    }

    /** true 表示设备加密不可用：配置只在本次进程内有效，退出即丢失。 */
    val isMemoryOnly: Boolean get() = prefs == null

    fun getAll(): List<StorageAccount> {
        val raw = prefs?.getString(KEY_ACCOUNTS, null) ?: memoryOnly ?: return emptyList()
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
        val json = arr.toString()
        val target = prefs
        if (target != null) {
            target.edit().putString(KEY_ACCOUNTS, json).apply()
        } else {
            memoryOnly = json
        }
    }

    @Suppress("DEPRECATION")
    private fun createEncryptedPrefs(context: Context): SharedPreferences {
        val masterKeyAlias = MasterKeys.getOrCreate(MasterKeys.AES256_GCM_SPEC)
        return EncryptedSharedPreferences.create(
            FILE_NAME,
            masterKeyAlias,
            context,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
        )
    }

    private companion object {
        const val FILE_NAME = "network_storage_secure"
        const val KEY_ACCOUNTS = "accounts"
    }
}
