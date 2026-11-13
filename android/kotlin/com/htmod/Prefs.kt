/*
 * HTMOD Android : reglages persistants (SharedPreferences), memes cles que la version Windows.
 * Appele aussi par la copie Android de SatCatalog.java (d'ou @JvmStatic).
 */
package com.htmod

import android.content.Context
import android.content.SharedPreferences

object Prefs {
    @Volatile private var sp: SharedPreferences? = null

    @JvmStatic fun init(c: Context) {
        if (sp == null) sp = c.applicationContext.getSharedPreferences("htmod", Context.MODE_PRIVATE)
    }

    private fun sp(): SharedPreferences = sp ?: throw IllegalStateException("Prefs.init manquant")

    @JvmStatic fun get(k: String, def: String): String = sp().getString(k, def) ?: def
    @JvmStatic fun put(k: String, v: String) = sp().edit().putString(k, v).apply()
    @JvmStatic fun getInt(k: String, def: Int): Int = try { sp().getInt(k, def) } catch (e: ClassCastException) { def }
    @JvmStatic fun putInt(k: String, v: Int) = sp().edit().putInt(k, v).apply()
    @JvmStatic fun getBoolean(k: String, def: Boolean): Boolean = try { sp().getBoolean(k, def) } catch (e: ClassCastException) { def }
    @JvmStatic fun putBoolean(k: String, v: Boolean) = sp().edit().putBoolean(k, v).apply()
    @JvmStatic fun remove(k: String) = sp().edit().remove(k).apply()
    @JvmStatic fun commit(k: String, v: String) { sp().edit().putString(k, v).commit() }
}
