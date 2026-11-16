package cd.codesbarres.lot

import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL

/** Import d'une liste de valeurs depuis un lien (Google Sheets, CSV ou texte en ligne). */
object OnlineImport {

    private const val MAX_BYTES = 2 * 1024 * 1024

    /** Transforme un lien Google Sheets ordinaire en lien d'export CSV. */
    fun toDownloadUrl(link: String): String {
        var u = link.trim()
        if (!u.startsWith("http://") && !u.startsWith("https://")) u = "https://$u"
        if (!u.contains("docs.google.com/spreadsheets")) return u
        val gid = Regex("[#&?]gid=(\\d+)").find(u)?.groupValues?.get(1)
        // Feuille publiée sur le Web : .../d/e/<id>/pubhtml → .../pub?output=csv
        if (u.contains("/d/e/")) {
            val base = u.substringBefore("?").substringBefore("#")
                .replace(Regex("/pub(html)?$"), "") + "/pub"
            return base + "?output=csv" + (gid?.let { "&gid=$it" } ?: "")
        }
        val id = Regex("/spreadsheets/d/([A-Za-z0-9_-]+)").find(u)?.groupValues?.get(1) ?: return u
        return "https://docs.google.com/spreadsheets/d/$id/export?format=csv" + (gid?.let { "&gid=$it" } ?: "")
    }

    fun download(link: String): String {
        var url = URL(toDownloadUrl(link))
        var redirects = 0
        while (true) {
            val c = url.openConnection() as HttpURLConnection
            c.connectTimeout = 10000
            c.readTimeout = 15000
            c.instanceFollowRedirects = false
            c.setRequestProperty("User-Agent", "LotCodesBarres/${BuildConfig.VERSION_NAME} (Android)")
            try {
                val code = c.responseCode
                if (code in 300..399) {
                    val loc = c.getHeaderField("Location") ?: throw IOException("redirection sans adresse")
                    if (++redirects > 5) throw IOException("trop de redirections")
                    url = URL(url, loc)
                    continue
                }
                if (code == 401 || code == 403) throw IOException("accès refusé : partagez la feuille « Tous les utilisateurs disposant du lien »")
                if (code == 404) throw IOException("lien introuvable (404)")
                if (code !in 200..299) throw IOException("le serveur a répondu $code")
                val type = c.contentType ?: ""
                val bytes = c.inputStream.use { input ->
                    val buf = java.io.ByteArrayOutputStream()
                    val chunk = ByteArray(8192)
                    while (true) {
                        val n = input.read(chunk)
                        if (n < 0) break
                        buf.write(chunk, 0, n)
                        if (buf.size() > MAX_BYTES) throw IOException("fichier trop volumineux (2 Mo maximum)")
                    }
                    buf.toByteArray()
                }
                val text = String(bytes, Charsets.UTF_8).removePrefix("﻿")
                if (type.contains("html") || text.trimStart().startsWith("<")) {
                    throw IOException("ce lien renvoie une page Web, pas une liste. Pour Google Sheets, partagez la feuille avec « Tous les utilisateurs disposant du lien »")
                }
                return text
            } finally {
                c.disconnect()
            }
        }
    }

    /** Garde la 1re colonne de chaque ligne (CSV avec , ; ou tabulation, ou texte simple). */
    fun parseFirstColumn(text: String, skipHeader: Boolean): List<String> {
        val rows = text.lines().map { firstField(it).trim() }.filter { it.isNotEmpty() }
        return if (skipHeader) rows.drop(1) else rows
    }

    private fun firstField(line: String): String {
        if (line.startsWith("\"")) {
            val sb = StringBuilder()
            var i = 1
            while (i < line.length) {
                val ch = line[i]
                if (ch == '"') {
                    if (i + 1 < line.length && line[i + 1] == '"') { sb.append('"'); i += 2; continue }
                    break
                }
                sb.append(ch); i++
            }
            return sb.toString()
        }
        val cut = line.indexOfFirst { it == ',' || it == ';' || it == '\t' }
        return if (cut >= 0) line.substring(0, cut) else line
    }
}
