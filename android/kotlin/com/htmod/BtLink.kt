/*
 * HTMOD Android : liaison Bluetooth avec le poste, equivalent de btbridge.exe (Windows) :
 *  - canal 0, commandes : RFCOMM SPP (trames GAIA) ; a defaut BLE (service 00001100-d102-..., ecriture
 *    1101, indications 1102 : messages bruts) ;
 *  - canal 1, audio : RFCOMM service « BS AOC » (39144315-...), audio SBC ;
 *  - canal 2 : evenements texte (« connected 0 gaia|ble », « connected 1 », « closed 1 », « error ... »).
 * Radio (coeur Java partage) lit et ecrit les memes trames [canal][longueur 4 octets][donnees] que sur Windows.
 */
package com.htmod

import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.bluetooth.BluetoothSocket
import android.content.Context
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.nio.charset.StandardCharsets
import java.util.UUID
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.Semaphore
import java.util.concurrent.TimeUnit

@SuppressLint("MissingPermission")
class BtLink(ctx: Context, private val mac: String) : Radio.Link {
    private val ctx = ctx.applicationContext
    private val inQ = LinkedBlockingQueue<ByteArray>()
    @Volatile private var closed = false
    @Volatile private var cmd: BluetoothSocket? = null
    @Volatile private var audio: BluetoothSocket? = null
    @Volatile private var cmdOut: OutputStream? = null
    @Volatile private var audioOut: OutputStream? = null
    @Volatile private var gatt: BluetoothGatt? = null
    @Volatile private var bleWrite: BluetoothGattCharacteristic? = null
    private val bleWriteDone = Semaphore(0)
    @Volatile private var bleWriteOk = false
    private val bleStep = Semaphore(0)
    @Volatile private var bleError: String? = null

    init {
        Thread({ open() }, "htmod-bt-ouverture").apply { isDaemon = true; start() }
    }

    // ------------------------------------------------------------------ Radio.Link

    private val input = object : InputStream() {
        private var cur = ByteArray(0)
        private var pos = 0

        override fun read(): Int {
            if (!fill()) return -1
            return cur[pos++].toInt() and 0xFF
        }

        override fun read(b: ByteArray, off: Int, len: Int): Int {
            if (len == 0) return 0
            if (!fill()) return -1
            val n = Math.min(len, cur.size - pos)
            System.arraycopy(cur, pos, b, off, n)
            pos += n
            return n
        }

        private fun fill(): Boolean {
            while (pos >= cur.size) {
                cur = try { inQ.take() } catch (e: InterruptedException) { throw IOException("interrompu") }
                pos = 0
                if (cur === EOF) return false
            }
            return true
        }
    }

    /** Trames ecrites par Radio : en-tete de 5 octets puis donnees, aiguillees vers le bon canal. */
    private val output = object : OutputStream() {
        private val acc = ByteArrayOutputStream()

        override fun write(b: Int) { acc.write(b); pump() }

        override fun write(b: ByteArray, off: Int, len: Int) { acc.write(b, off, len); pump() }

        private fun pump() {
            val a = acc.toByteArray()
            var off = 0
            while (a.size - off >= 5) {
                val ch = a[off].toInt() and 0xFF
                val len = ((a[off + 1].toInt() and 0xFF) shl 24) or ((a[off + 2].toInt() and 0xFF) shl 16) or
                    ((a[off + 3].toInt() and 0xFF) shl 8) or (a[off + 4].toInt() and 0xFF)
                if (len < 0 || len > (1 shl 20)) { off = a.size; break }
                if (a.size - off - 5 < len) break
                val d = a.copyOfRange(off + 5, off + 5 + len)
                off += 5 + len
                send(ch, d)
            }
            acc.reset()
            acc.write(a, off, a.size - off)
        }
    }

    override fun input(): InputStream = input
    override fun output(): OutputStream = output

    override fun close() {
        if (closed) return
        closed = true
        closeQuiet(cmd)
        closeQuiet(audio)
        gatt?.let { try { it.disconnect(); it.close() } catch (e: Exception) {} }
        inQ.offer(EOF)
    }

    private fun closeQuiet(s: BluetoothSocket?) { try { s?.close() } catch (e: IOException) {} }

    // ------------------------------------------------------------------ Trames vers Radio

    private fun push(ch: Int, data: ByteArray, len: Int) {
        if (closed) return
        val f = ByteArray(5 + len)
        f[0] = ch.toByte()
        f[1] = (len shr 24).toByte(); f[2] = (len shr 16).toByte(); f[3] = (len shr 8).toByte(); f[4] = len.toByte()
        System.arraycopy(data, 0, f, 5, len)
        inQ.offer(f)
    }

    private fun event(s: String) {
        val b = s.toByteArray(StandardCharsets.UTF_8)
        push(2, b, b.size)
    }

    private fun send(ch: Int, d: ByteArray) {
        try {
            if (ch == 0) {
                if (gatt != null) bleSend(d)
                else cmdOut?.let {
                    try { it.write(d); it.flush() }
                    catch (e: IOException) { event("error canal commandes : " + e.message); close() } // liaison morte : reconnexion
                }
            } else if (ch == 1) {
                audioOut?.let { it.write(d); it.flush() }
            }
        } catch (e: IOException) {
            event("error envoi canal $ch : ${e.message}")
        }
    }

    // ------------------------------------------------------------------ Ouverture

    private fun open() {
        val bm = ctx.getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager?
        val ad = bm?.adapter
        if (ad == null || !ad.isEnabled) {
            event("error canal commandes : Bluetooth du téléphone désactivé")
            close()
            return
        }
        try { ad.cancelDiscovery() } catch (e: Exception) {}
        val dev = ad.getRemoteDevice(mac)
        var sppWhy = ""
        try {
            val s = rfcomm(dev, arrayOf(SPP), intArrayOf(1))
            cmd = s
            cmdOut = s.outputStream
        } catch (e: Exception) {
            sppWhy = e.message ?: e.toString()
        }
        if (closed) return
        val c = cmd
        if (c != null) {
            event("connected 0 gaia")
            reader(c, 0)
        } else if (sppWhy.contains("read failed")) {
            // le poste repond mais refuse : deja relie a un autre appareil (le BLE serait refuse aussi)
            event("error canal commandes : le poste refuse la liaison (déjà connecté à un autre appareil : HTMod sur le PC, appli du constructeur ?)")
            close()
            return
        } else {
            event("info Bluetooth classique indisponible ($sppWhy), essai en BLE")
            val why = openBle(dev)
            if (why != null) {
                event("error canal commandes : $sppWhy ; BLE : $why")
                close()
                return
            }
            event("connected 0 ble")
        }
        var attempt = 1
        while (attempt <= 3 && audio == null && !closed) {
            try {
                val s = rfcomm(dev, arrayOf(BS_AOC, GENERIC_AUDIO), intArrayOf(2, 3, 4, 5, 6, 7, 8, 9, 10, 11, 12))
                audio = s
                audioOut = s.outputStream
            } catch (e: Exception) {
                event("error canal audio (essai $attempt/3) : ${e.message}")
                try { Thread.sleep(700) } catch (ie: InterruptedException) { return }
            }
            attempt++
        }
        val a = audio
        if (a != null && !closed) {
            event("connected 1")
            reader(a, 1)
        }
    }

    /** RFCOMM par UUID de service (SDP), puis par numero de canal si la recherche SDP echoue. */
    private fun rfcomm(dev: BluetoothDevice, uuids: Array<UUID>, ports: IntArray): BluetoothSocket {
        var last: IOException? = null
        for (u in uuids) {
            if (closed) throw IOException("fermé")
            for (secure in booleanArrayOf(true, false)) {
                var s: BluetoothSocket? = null
                try {
                    s = if (secure) dev.createRfcommSocketToServiceRecord(u) else dev.createInsecureRfcommSocketToServiceRecord(u)
                    s.connect()
                    return s
                } catch (e: IOException) {
                    last = e
                    closeQuiet(s)
                }
            }
        }
        for (p in ports) {
            if (closed) throw IOException("fermé")
            var s: BluetoothSocket? = null
            try {
                val m = dev.javaClass.getMethod("createRfcommSocket", Int::class.javaPrimitiveType)
                s = m.invoke(dev, p) as BluetoothSocket
                s.connect()
                return s
            } catch (e: IOException) {
                last = e
                closeQuiet(s)
            } catch (e: Exception) {
                last = IOException(e.toString())
                closeQuiet(s)
                break
            }
        }
        throw last ?: IOException("aucun service")
    }

    private fun reader(s: BluetoothSocket, ch: Int) {
        Thread({
            val buf = ByteArray(4096)
            try {
                val input = s.inputStream
                while (!closed) {
                    val n = input.read(buf)
                    if (n <= 0) break
                    push(ch, buf, n)
                }
            } catch (e: IOException) {
            }
            if (!closed) {
                event("closed $ch")
                if (ch == 0) close() // sans canal de commandes, plus rien a faire
            }
        }, "htmod-bt-lecture-$ch").apply { isDaemon = true; start() }
    }

    // ------------------------------------------------------------------ BLE

    @Suppress("DEPRECATION")
    private fun openBle(dev: BluetoothDevice): String? {
        val cb = object : BluetoothGattCallback() {
            override fun onConnectionStateChange(g: BluetoothGatt, status: Int, state: Int) {
                if (state == BluetoothProfile.STATE_CONNECTED) {
                    g.requestMtu(512)
                } else if (state == BluetoothProfile.STATE_DISCONNECTED) {
                    if (bleWrite == null) {
                        bleError = if (status == 147 || status == 8) "pas de réponse du poste (délai dépassé)" else "déconnecté (état $status)"
                        bleStep.release()
                    } else if (!closed) {
                        event("closed 0")
                        close()
                    }
                }
            }

            override fun onMtuChanged(g: BluetoothGatt, mtu: Int, status: Int) { g.discoverServices() }

            override fun onServicesDiscovered(g: BluetoothGatt, status: Int) {
                val s = g.getService(BLE_SERVICE)
                if (s == null) { bleError = "service du poste absent"; bleStep.release(); return }
                val w = s.getCharacteristic(BLE_WRITE)
                val ind = s.getCharacteristic(BLE_INDICATE)
                if (w == null || ind == null) { bleError = "caractéristiques absentes"; bleStep.release(); return }
                g.setCharacteristicNotification(ind, true)
                val d = ind.getDescriptor(CCCD)
                bleWrite = w
                if (d == null) { bleStep.release(); return }
                d.value = if ((ind.properties and BluetoothGattCharacteristic.PROPERTY_INDICATE) != 0)
                    BluetoothGattDescriptor.ENABLE_INDICATION_VALUE else BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
                if (!g.writeDescriptor(d)) bleStep.release()
            }

            override fun onDescriptorWrite(g: BluetoothGatt, d: BluetoothGattDescriptor, status: Int) {
                if (status != BluetoothGatt.GATT_SUCCESS) bleError = "abonnement refusé (état $status)"
                bleStep.release()
            }

            override fun onCharacteristicChanged(g: BluetoothGatt, c: BluetoothGattCharacteristic) {
                val v = c.value
                if (v != null) push(0, v, v.size)
            }

            override fun onCharacteristicWrite(g: BluetoothGatt, c: BluetoothGattCharacteristic, status: Int) {
                bleWriteOk = status == BluetoothGatt.GATT_SUCCESS
                bleWriteDone.release()
            }
        }
        val g = dev.connectGatt(ctx, false, cb, BluetoothDevice.TRANSPORT_LE) ?: return "connexion GATT impossible"
        gatt = g
        try {
            if (!bleStep.tryAcquire(25, TimeUnit.SECONDS)) bleError = "pas de réponse"
        } catch (e: InterruptedException) {
            bleError = "interrompu"
        }
        if (bleError != null || bleWrite == null) {
            val w = bleError ?: "caractéristique d'écriture absente"
            try { g.disconnect(); g.close() } catch (e: Exception) {}
            gatt = null
            return w
        }
        return null
    }

    @Suppress("DEPRECATION")
    @Synchronized private fun bleSend(d: ByteArray) {
        val g = gatt
        val w = bleWrite
        if (g == null || w == null) throw IOException("BLE fermé")
        repeat(3) {
            bleWriteDone.drainPermits()
            w.writeType = BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
            w.value = d
            if (!g.writeCharacteristic(w)) {
                try { Thread.sleep(100) } catch (e: InterruptedException) { return }
            } else {
                try {
                    if (bleWriteDone.tryAcquire(5, TimeUnit.SECONDS) && bleWriteOk) return
                } catch (e: InterruptedException) {
                    return
                }
            }
        }
        throw IOException("écriture BLE refusée")
    }

    companion object {
        private val SPP = UUID.fromString("00001101-0000-1000-8000-00805F9B34FB")
        private val BS_AOC = UUID.fromString("39144315-32FA-40DB-85ED-FBFEBA2D86E6")
        private val GENERIC_AUDIO = UUID.fromString("00001203-0000-1000-8000-00805F9B34FB")
        private val BLE_SERVICE = UUID.fromString("00001100-d102-11e1-9b23-00025b00a5a5")
        private val BLE_WRITE = UUID.fromString("00001101-d102-11e1-9b23-00025b00a5a5")
        private val BLE_INDICATE = UUID.fromString("00001102-d102-11e1-9b23-00025b00a5a5")
        private val CCCD = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")
        private val EOF = ByteArray(0)
    }
}
