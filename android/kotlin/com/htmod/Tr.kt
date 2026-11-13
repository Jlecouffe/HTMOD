/*
 * HTMOD Android : langue de l'interface (meme table i18n.txt que la version Windows ; le texte francais
 * sert de cle). Appele par le coeur Java partage (Radio, Pocsag, Decoders...) : Tr.t(...).
 */
package com.htmod

import java.io.BufferedReader
import java.io.IOException
import java.io.InputStreamReader
import java.nio.charset.StandardCharsets
import java.util.Locale

object Tr {
    @JvmField val LANGS = arrayOf(arrayOf("fr", "Français"), arrayOf("en", "English"), arrayOf("it", "Italiano"))
    private const val PREF = "lang"

    @Volatile private var lang: String = pick()
    @Volatile private var dict: Map<String, String> = load(lang)

    private fun pick(): String {
        val saved = try { Prefs.get(PREF, "") } catch (e: IllegalStateException) { "" }
        if (known(saved)) return saved
        val sys = Locale.getDefault().language
        return if (known(sys)) sys else "en"
    }

    @JvmStatic fun known(code: String?): Boolean = LANGS.any { it[0] == code }

    @JvmStatic fun lang(): String = lang

    @JvmStatic fun save(code: String) = Prefs.commit(PREF, code)

    /** Relit la langue enregistree (changement de langue sans relancer le telephone). */
    @JvmStatic @Synchronized fun reload() {
        val l = pick()
        dict = load(l)
        lang = l
    }

    /** Traduction d'un texte francais (le texte lui-meme s'il n'a pas de traduction). */
    @JvmStatic fun t(s: String?): String? {
        if (s == null || dict.isEmpty()) return s
        var a = 0
        var b = s.length
        while (a < b && s[a] <= ' ') a++
        while (b > a && s[b - 1] <= ' ') b--
        val v = dict[s.substring(a, b)] ?: return s
        return if (a == 0 && b == s.length) v else s.substring(0, a) + v + s.substring(b)
    }

    /** Version Kotlin non nulle. */
    fun tr(s: String): String = t(s) ?: s

    @JvmStatic fun pad(s: String, width: Int): String {
        val b = StringBuilder(tr(s))
        while (b.length < width) b.append(' ')
        return b.toString()
    }

    private fun load(lang: String): Map<String, String> {
        val m = HashMap<String, String>()
        var col = -1
        for (i in 1 until LANGS.size) if (LANGS[i][0] == lang) col = i
        if (col < 0) return m
        try {
            for (row in table()) if (row.size > col && row[col].isNotEmpty()) m[row[0]] = row[col]
        } catch (e: IOException) {
            System.err.println("i18n.txt : $e")
        }
        return m
    }

    @JvmStatic fun table(): List<Array<String>> {
        val rows = ArrayList<Array<String>>()
        val input = Tr::class.java.getResourceAsStream("i18n.txt") ?: throw IOException("i18n.txt absent")
        BufferedReader(InputStreamReader(input, StandardCharsets.UTF_8)).use { r ->
            while (true) {
                val line = r.readLine() ?: break
                if (line.isEmpty() || line.startsWith("#")) continue
                val c = line.split(Regex(" \\|( |$)")).map { it.trim().replace("\\n", "\n") }.toTypedArray()
                rows.add(c)
            }
        }
        return rows
    }
}

/** Raccourci : texte francais traduit dans la langue de l'appli. */
fun tr(s: String): String = Tr.tr(s)
