package com.gh0u1l5.wechatmagician.backend.storage

import android.content.*
import android.net.Uri
import com.gh0u1l5.wechatmagician.Global.ACTION_UPDATE_PREF
import com.gh0u1l5.wechatmagician.Global.FOLDER_SHARED_PREFS
import com.gh0u1l5.wechatmagician.Global.MAGICIAN_BASE_DIR
import com.gh0u1l5.wechatmagician.Global.PREFERENCE_PROVIDER_AUTHORITY
import com.gh0u1l5.wechatmagician.Global.PREFERENCE_STRING_LIST_KEYS
import com.gh0u1l5.wechatmagician.spellbook.base.WaitChannel
import com.gh0u1l5.wechatmagician.spellbook.util.BasicUtil.tryAsynchronously
import com.gh0u1l5.wechatmagician.spellbook.util.BasicUtil.tryVerbosely
import de.robv.android.xposed.XSharedPreferences
import java.io.File
import java.util.concurrent.ConcurrentHashMap

class Preferences(private val preferencesName: String) : SharedPreferences {

    // loadChannel resumes all the threads waiting for the preference loading.
    private val loadChannel = WaitChannel()

    // listCache caches the string lists in memory to speed up getStringList()
    private val listCache: MutableMap<String, List<String>> = ConcurrentHashMap()

    // legacy is prepared for the fallback logic if ContentProvider is not working.
    private var legacy: XSharedPreferences? = null

    // content is the preferences generated by the frond end of Wechat Magician.
    private val content: MutableMap<String, Any?> = ConcurrentHashMap()

    // load reads the shared preferences or reloads the existing preferences
    fun load(context: Context) {
        tryAsynchronously {
            try {
                // Load the shared preferences using ContentProvider.
                val uri = Uri.parse("content://$PREFERENCE_PROVIDER_AUTHORITY/$preferencesName")
                val cursor = context.contentResolver.query(uri, null, null, null, null)
                cursor?.use {
                    while (cursor.moveToNext()) {
                        val key = cursor.getString(0)
                        val type = cursor.getString(2)
                        content[key] = when (type) {
                            "Int"     -> cursor.getInt(1)
                            "Long"    -> cursor.getLong(1)
                            "Float"   -> cursor.getFloat(1)
                            "Boolean" -> (cursor.getString(1) == "true")
                            "String"  -> cursor.getString(1)
                            else -> null
                        }
                    }
                }
            } catch (_: SecurityException) {
                // Failed to use the ContentProvider pattern, fallback to XSharedPreferences.
                if (loadChannel.isDone() && legacy != null) {
                    legacy?.reload()
                    return@tryAsynchronously
                }
                val preferencesDir = "$MAGICIAN_BASE_DIR/$FOLDER_SHARED_PREFS/"
                legacy = XSharedPreferences(File(preferencesDir, "$preferencesName.xml"))
            } finally {
                loadChannel.done()
                cacheStringList()
            }
        }
    }

    // listen registers the updateReceiver to listen the update events from the frontend.
    private val updateReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            loadChannel.wait(2000)
            // If we are using the legacy logic, then just stay with it.
            if (legacy != null) {
                legacy?.reload()
                cacheStringList()
                return
            }
            // Otherwise we completely follow the new ContentProvider pattern.
            if (intent != null) {
                val key = intent.getStringExtra("key") ?: return
                content[key] = intent.extras?.get("value")
            }
        }
    }

    fun listen(context: Context) {
        tryVerbosely {
            context.registerReceiver(updateReceiver, IntentFilter(ACTION_UPDATE_PREF))
        }
    }

    private fun cacheStringList() {
        PREFERENCE_STRING_LIST_KEYS.forEach { key ->
            listCache[key] = getString(key, "")?.split(" ", "|")?.filter { it.isNotEmpty() } ?: emptyList()
        }
    }

    override fun contains(key: String): Boolean = content.contains(key) || legacy?.contains(key) == true

    override fun getAll(): MutableMap<String, *>? = if (legacy != null) legacy!!.all else content

    private fun getValue(key: String): Any? {
        loadChannel.wait(100)
        return all?.get(key)
    }

    private inline fun <reified T>getValue(key: String, defValue: T) = getValue(key) as? T ?: defValue

    override fun getInt(key: String, defValue: Int): Int = getValue(key, defValue)

    override fun getLong(key: String, defValue: Long): Long = getValue(key, defValue)

    override fun getFloat(key: String, defValue: Float): Float = getValue(key, defValue)

    override fun getBoolean(key: String, defValue: Boolean): Boolean = getValue(key, defValue)

    override fun getString(key: String, defValue: String?): String? = getValue(key, defValue)

    override fun getStringSet(key: String, defValue: MutableSet<String>?): MutableSet<String>? = getValue(key, defValue)

    fun getStringList(key: String, defValue: List<String>): List<String> {
        loadChannel.wait(100)
        return listCache[key] ?: defValue
    }

    override fun edit(): SharedPreferences.Editor {
        throw UnsupportedOperationException()
    }

    override fun registerOnSharedPreferenceChangeListener(listener: SharedPreferences.OnSharedPreferenceChangeListener?) {
        throw UnsupportedOperationException()
    }

    override fun unregisterOnSharedPreferenceChangeListener(listener: SharedPreferences.OnSharedPreferenceChangeListener?) {
        throw UnsupportedOperationException()
    }
}
