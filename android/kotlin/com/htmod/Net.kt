/*
 * HTMOD Android : acces reseau du catalogue des satellites, appele par la copie Android de
 * SatCatalog.java : GET (CelesTrak) et appel gRPC (serveur Benshi), qui exige HTTP/2 : petit client
 * HTTP/2 sur TLS (ALPN « h2 ») pour cette seule requete.
 */
package com.htmod

import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.nio.charset.StandardCharsets
import javax.net.ssl.SSLSocket
import javax.net.ssl.SSLSocketFactory

object Net {
    @JvmStatic fun readAll(input: InputStream): ByteArray {
        val o = ByteArrayOutputStream()
        val b = ByteArray(16384)
        while (true) {
            val n = input.read(b)
            if (n <= 0) break
            o.write(b, 0, n)
        }
        return o.toByteArray()
    }

    @JvmStatic fun get(url: String): String {
        val c = URL(url).openConnection() as HttpURLConnection
        c.setRequestProperty("User-Agent", "HTMod/2.0 (Android; radioamateur)")
        c.connectTimeout = 10000
        c.readTimeout = 30000
        try {
            val code = c.responseCode
            if (code != 200) throw IOException("HTTP $code")
            return c.inputStream.use { String(readAll(it), StandardCharsets.UTF_8) }
        } finally {
            c.disconnect()
        }
    }

    /** Appel gRPC unaire (corps deja encadre) ; renvoie le corps de la reponse. */
    @JvmStatic fun grpc(url: String, body: ByteArray): ByteArray = h2Post(URL(url), "application/grpc", body)

    private fun frame(o: OutputStream, type: Int, flags: Int, stream: Int, p: ByteArray) {
        val n = p.size
        o.write(byteArrayOf((n shr 16).toByte(), (n shr 8).toByte(), n.toByte(), type.toByte(), flags.toByte(),
            (stream shr 24).toByte(), (stream shr 16).toByte(), (stream shr 8).toByte(), stream.toByte()))
        o.write(p)
    }

    /** En-tete HPACK « litteral sans indexation, nom nouveau » (chaines courtes, sans Huffman). */
    private fun hpack(b: ByteArrayOutputStream, name: String, value: String) {
        val n = name.toByteArray(StandardCharsets.US_ASCII)
        val v = value.toByteArray(StandardCharsets.US_ASCII)
        b.write(0); b.write(n.size); b.write(n, 0, n.size); b.write(v.size); b.write(v, 0, v.size)
    }

    private fun u32(v: Int) = byteArrayOf((v shr 24).toByte(), (v shr 16).toByte(), (v shr 8).toByte(), v.toByte())

    private fun readFully(input: InputStream, b: ByteArray) {
        var off = 0
        while (off < b.size) {
            val k = input.read(b, off, b.size - off)
            if (k <= 0) throw IOException("connexion fermée")
            off += k
        }
    }

    private fun h2Post(url: URL, type: String, body: ByteArray): ByteArray {
        val port = if (url.port > 0) url.port else 443
        val s = SSLSocketFactory.getDefault().createSocket(url.host, port) as SSLSocket
        try {
            s.soTimeout = 30000
            val sp = s.sslParameters
            sp.applicationProtocols = arrayOf("h2")
            s.sslParameters = sp
            s.startHandshake()
            if (s.applicationProtocol != "h2") throw IOException("HTTP/2 refusé par le serveur")
            val o = BufferedOutputStream(s.outputStream)
            val input = BufferedInputStream(s.inputStream)
            o.write("PRI * HTTP/2.0\r\n\r\nSM\r\n\r\n".toByteArray(StandardCharsets.US_ASCII))
            frame(o, 4, 0, 0, byteArrayOf(0, 4, 0, 0x7F, 0xFF.toByte(), 0xFF.toByte()))
            frame(o, 8, 0, 0, u32(0x7F000000))
            val h = ByteArrayOutputStream()
            hpack(h, ":method", "POST")
            hpack(h, ":scheme", "https")
            hpack(h, ":path", url.file)
            hpack(h, ":authority", url.host + if (url.port > 0) ":" + url.port else "")
            hpack(h, "content-type", type)
            hpack(h, "te", "trailers")
            hpack(h, "user-agent", "HTMod-android/2.0")
            frame(o, 1, 0x4, 1, h.toByteArray())
            frame(o, 0, 0x1, 1, body)
            o.flush()
            val data = ByteArrayOutputStream()
            val hd = ByteArray(9)
            while (true) {
                readFully(input, hd)
                val len = ((hd[0].toInt() and 0xFF) shl 16) or ((hd[1].toInt() and 0xFF) shl 8) or (hd[2].toInt() and 0xFF)
                val ft = hd[3].toInt() and 0xFF
                val fl = hd[4].toInt() and 0xFF
                val st = ((hd[5].toInt() and 0x7F) shl 24) or ((hd[6].toInt() and 0xFF) shl 16) or ((hd[7].toInt() and 0xFF) shl 8) or (hd[8].toInt() and 0xFF)
                if (len > (1 shl 24)) throw IOException("trame HTTP/2 invalide")
                val p = ByteArray(len)
                readFully(input, p)
                when (ft) {
                    0 -> {
                        if (st == 1) {
                            var off = 0
                            var end = len
                            if ((fl and 0x8) != 0 && len > 0) { off = 1; end = len - (p[0].toInt() and 0xFF) }
                            if (end > off) data.write(p, off, end - off)
                            if (len > 0) { frame(o, 8, 0, 0, u32(len)); frame(o, 8, 0, 1, u32(len)); o.flush() }
                            if ((fl and 0x1) != 0) return data.toByteArray()
                        }
                    }
                    1 -> if (st == 1 && (fl and 0x1) != 0) return data.toByteArray()
                    3 -> throw IOException("flux HTTP/2 annulé par le serveur")
                    4 -> if ((fl and 0x1) == 0) { frame(o, 4, 0x1, 0, ByteArray(0)); o.flush() }
                    6 -> if ((fl and 0x1) == 0) { frame(o, 6, 0x1, 0, p); o.flush() }
                    7 -> { if (data.size() > 0) return data.toByteArray(); throw IOException("HTTP/2 GOAWAY") }
                }
            }
        } finally {
            try { s.close() } catch (e: IOException) {}
        }
    }
}
