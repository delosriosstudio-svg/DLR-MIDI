package pe.dlr.midi

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.net.wifi.WifiManager
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.os.Looper
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.Inet4Address
import java.net.InetAddress
import java.net.InetSocketAddress
import java.nio.ByteBuffer
import java.util.concurrent.ConcurrentLinkedQueue
import kotlin.random.Random

/**
 * MIDI por Wi-Fi con el protocolo "Red MIDI" de Apple (RTP-MIDI / AppleMIDI).
 *
 * - El telefono se anuncia en la red como "DLR MIDI" (servicio _apple-midi._udp).
 *   En el Mac: Configuracion de Audio MIDI > Ventana > Mostrar Estudio MIDI > Red.
 * - Ademas busca las sesiones de red del Mac y se conecta solo a la primera que encuentra.
 * - Puertos: control 5004 y datos 5005 (o los siguientes libres).
 */
class Wifi(private val context: Context, private val alCambiar: (texto: String?, conectado: Boolean) -> Unit) {

    private val NOMBRE = "DLR MIDI"
    private val main = Handler(Looper.getMainLooper())
    private var hilo: HandlerThread? = null
    @Volatile private var red: Handler? = null
    @Volatile private var ctrl: DatagramSocket? = null
    @Volatile private var datos: DatagramSocket? = null
    private var nsd: NsdManager? = null
    private var registro: NsdManager.RegistrationListener? = null
    private var busqueda: NsdManager.DiscoveryListener? = null
    private var multicast: WifiManager.MulticastLock? = null
    @Volatile private var activo = false

    private val ssrc = Random.nextInt()
    private var seq = Random.nextInt(0, 65535)
    private val t0 = System.nanoTime()
    private fun ahora100us(): Long = (System.nanoTime() - t0) / 100_000L

    /** la sesion abierta (Mac) */
    private class Par(val nombre: String, val ip: InetAddress, val ctrlPort: Int, var datosPort: Int, val token: Int, val ssrc: Int) {
        var datosOk = false
        var ultimoCk = System.currentTimeMillis()
        var yoInvito = false
    }
    @Volatile private var par: Par? = null
    /** invitacion que mandamos nosotros y espera respuesta */
    private var invitando: Par? = null
    private val candidatos = ConcurrentLinkedQueue<NsdServiceInfo>()
    /** sesiones del Mac ya encontradas (para reconectar solo) */
    private val conocidos = LinkedHashMap<String, Triple<String, InetAddress, Int>>()
    private var turno = 0
    private var resolviendo = false

    val conectado: Boolean get() = par?.datosOk == true
    val nombrePar: String get() = par?.nombre ?: ""

    // ------------------------------------------------------------------ inicio / fin
    fun iniciar() {
        if (activo) return
        activo = true
        val h = HandlerThread("dlr-wifi").apply { start() }
        hilo = h
        red = Handler(h.looper)
        red?.post { abrirPuertos() }
    }

    fun detener() {
        if (!activo) return
        activo = false
        val r = red
        val c = ctrl; val d = datos; val p = par
        par = null; invitando = null; ctrl = null; datos = null
        // en el mismo hilo de red, DESPUES de los mensajes pendientes (p. ej. soltar notas)
        r?.post {
            p?.let { despedir(it, c) }
            try { c?.close() } catch (_: Exception) {}
            try { d?.close() } catch (_: Exception) {}
        }
        main.post {
            try { registro?.let { nsd?.unregisterService(it) } } catch (_: Exception) {}
            try { busqueda?.let { nsd?.stopServiceDiscovery(it) } } catch (_: Exception) {}
            registro = null; busqueda = null
            try { multicast?.release() } catch (_: Exception) {}
            multicast = null
        }
        val viejo = hilo
        hilo = null; red = null
        r?.postDelayed({ viejo?.quitSafely() }, 300)
        avisar(null, false)
    }

    private fun avisar(t: String?, ok: Boolean) = main.post { alCambiar(t, ok) }

    private fun abrirPuertos() {
        var p = 5004
        while (p < 5040) {
            try {
                val c = DatagramSocket(null).apply { reuseAddress = true; bind(InetSocketAddress(p)) }
                val d = try { DatagramSocket(null).apply { reuseAddress = true; bind(InetSocketAddress(p + 1)) } }
                        catch (e: Exception) { c.close(); throw e }
                ctrl = c; datos = d
                break
            } catch (_: Exception) { p += 2 }
        }
        val c = ctrl; val d = datos
        if (c == null || d == null) { avisar("Wi-Fi: no hay puertos libres", false); return }
        escuchar(c, true)
        escuchar(d, false)
        main.post { anunciar(c.localPort) }
        red?.postDelayed(latido, 2000)
    }

    // ------------------------------------------------------------------ Bonjour (anunciarse y buscar el Mac)
    private fun anunciar(puerto: Int) {
        if (!activo) return
        val m = context.getSystemService(Context.NSD_SERVICE) as NsdManager? ?: return
        nsd = m
        try {
            val wm = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
            multicast = wm.createMulticastLock("dlr-midi").apply { setReferenceCounted(false); acquire() }
        } catch (_: Exception) {}
        val info = NsdServiceInfo().apply { serviceName = NOMBRE; serviceType = "_apple-midi._udp"; port = puerto }
        val reg = object : NsdManager.RegistrationListener {
            override fun onServiceRegistered(i: NsdServiceInfo) {}
            override fun onRegistrationFailed(i: NsdServiceInfo, e: Int) {}
            override fun onServiceUnregistered(i: NsdServiceInfo) {}
            override fun onUnregistrationFailed(i: NsdServiceInfo, e: Int) {}
        }
        registro = reg
        try { m.registerService(info, NsdManager.PROTOCOL_DNS_SD, reg) } catch (_: Exception) {}
        val bus = object : NsdManager.DiscoveryListener {
            override fun onDiscoveryStarted(t: String) {}
            override fun onDiscoveryStopped(t: String) {}
            override fun onStartDiscoveryFailed(t: String, e: Int) {}
            override fun onStopDiscoveryFailed(t: String, e: Int) {}
            override fun onServiceLost(i: NsdServiceInfo) {}
            override fun onServiceFound(i: NsdServiceInfo) {
                if (i.serviceName.startsWith(NOMBRE)) return        // nosotros mismos
                candidatos.add(i); resolverSiguiente()
            }
        }
        busqueda = bus
        try { m.discoverServices("_apple-midi._udp", NsdManager.PROTOCOL_DNS_SD, bus) } catch (_: Exception) {}
    }

    @Suppress("DEPRECATION")
    private fun resolverSiguiente() {
        main.post {
            if (resolviendo || !activo) return@post
            val i = candidatos.poll() ?: return@post
            val m = nsd ?: return@post
            resolviendo = true
            try {
                m.resolveService(i, object : NsdManager.ResolveListener {
                    override fun onResolveFailed(s: NsdServiceInfo, e: Int) { resolviendo = false; resolverSiguiente() }
                    override fun onServiceResolved(s: NsdServiceInfo) {
                        resolviendo = false
                        val ip = if (Build.VERSION.SDK_INT >= 34) (s.hostAddresses.firstOrNull { it is Inet4Address } ?: s.hostAddresses.firstOrNull()) else s.host
                        if (ip != null && !esPropia(ip)) red?.post {
                            conocidos[s.serviceName + "@" + ip.hostAddress] = Triple(s.serviceName, ip, s.port)
                            invitar(s.serviceName, ip, s.port)
                        }
                        resolverSiguiente()
                    }
                })
            } catch (_: Exception) { resolviendo = false }
        }
    }

    private fun esPropia(ip: InetAddress): Boolean = try {
        java.net.NetworkInterface.getNetworkInterfaces().toList().any { n -> n.inetAddresses.toList().any { it == ip } }
    } catch (_: Exception) { false }

    // ------------------------------------------------------------------ recepcion
    private fun escuchar(s: DatagramSocket, esCtrl: Boolean) {
        Thread {
            val buf = ByteArray(1500)
            while (activo && !s.isClosed) {
                try {
                    val pk = DatagramPacket(buf, buf.size)
                    s.receive(pk)
                    val copia = buf.copyOf(pk.length)
                    val ip = pk.address; val port = pk.port
                    red?.post { recibir(copia, ip, port, esCtrl) }
                } catch (_: Exception) { if (!activo) break }
            }
        }.apply { isDaemon = true; start() }
    }

    private fun cmd(b: ByteArray): String =
        if (b.size >= 4 && b[0] == 0xFF.toByte() && b[1] == 0xFF.toByte()) String(byteArrayOf(b[2], b[3]), Charsets.US_ASCII) else ""

    private fun recibir(b: ByteArray, ip: InetAddress, port: Int, esCtrl: Boolean) {
        val bb = ByteBuffer.wrap(b)
        when (cmd(b)) {
            "IN" -> {                                   // el Mac nos invita
                if (b.size < 16) return
                val token = bb.getInt(8); val suSsrc = bb.getInt(12)
                val nombre = leerNombre(b, 16)
                val actual = par
                if (esCtrl) {
                    if (actual != null && actual.datosOk && actual.ip != ip) { responder("NO", token, ip, port, true); return }
                    par = Par(nombre.ifEmpty { "Mac" }, ip, port, port + 1, token, suSsrc)
                    responder("OK", token, ip, port, true)
                } else {
                    val p0 = par
                    val p = if (p0 != null && p0.ip == ip) p0 else Par(nombre.ifEmpty { "Mac" }, ip, port - 1, port, token, suSsrc).also { par = it }
                    p.datosPort = port; p.datosOk = true; p.ultimoCk = System.currentTimeMillis()
                    responder("OK", token, ip, port, false)
                    avisar("Conectado por Wi-Fi · " + p.nombre, true)
                }
            }
            "OK" -> {                                   // el Mac acepto nuestra invitacion
                val inv = invitando ?: return
                if (ip != inv.ip) return
                if (esCtrl) {
                    enviarCrudo(intercambio("IN", inv.token), inv.ip, inv.datosPort, false)
                } else {
                    inv.datosOk = true; inv.yoInvito = true; inv.ultimoCk = System.currentTimeMillis()
                    par = inv; invitando = null
                    sincronizar(inv)
                    avisar("Conectado por Wi-Fi · " + inv.nombre, true)
                }
            }
            "NO" -> { if (invitando?.ip == ip) invitando = null }
            "BY" -> {
                val p = par
                if (p != null && p.ip == ip) { par = null; avisar("Wi-Fi: el Mac cerro la sesion", false) }
            }
            "CK" -> if (!esCtrl && b.size >= 36) {
                val n = b[8].toInt()
                val p = par
                if (p != null && p.ip == ip) p.ultimoCk = System.currentTimeMillis()
                when (n) {
                    0 -> { val ts1 = bb.getLong(12); enviarCrudo(reloj(1, ts1, ahora100us(), 0), ip, port, false) }
                    1 -> { val ts1 = bb.getLong(12); val ts2 = bb.getLong(20); enviarCrudo(reloj(2, ts1, ts2, ahora100us()), ip, port, false) }
                }
            }
            "RS" -> {}                                  // aviso de recepcion: no hace falta
        }
    }

    private fun leerNombre(b: ByteArray, desde: Int): String {
        var fin = desde
        while (fin < b.size && b[fin] != 0.toByte()) fin++
        return if (fin > desde) String(b, desde, fin - desde, Charsets.UTF_8) else ""
    }

    // ------------------------------------------------------------------ envio de control
    private fun intercambio(c: String, token: Int): ByteArray {
        val nom = NOMBRE.toByteArray(Charsets.UTF_8)
        val bb = ByteBuffer.allocate(16 + nom.size + 1)
        bb.put(0xFF.toByte()); bb.put(0xFF.toByte()); bb.put(c[0].code.toByte()); bb.put(c[1].code.toByte())
        bb.putInt(2); bb.putInt(token); bb.putInt(ssrc); bb.put(nom); bb.put(0.toByte())
        return bb.array()
    }

    private fun responder(c: String, token: Int, ip: InetAddress, port: Int, esCtrl: Boolean) =
        enviarCrudo(intercambio(c, token), ip, port, esCtrl)

    private fun reloj(n: Int, a: Long, b: Long, c: Long): ByteArray {
        val bb = ByteBuffer.allocate(36)
        bb.put(0xFF.toByte()); bb.put(0xFF.toByte()); bb.put('C'.code.toByte()); bb.put('K'.code.toByte())
        bb.putInt(ssrc); bb.put(n.toByte()); bb.put(0.toByte()); bb.put(0.toByte()); bb.put(0.toByte())
        bb.putLong(a); bb.putLong(b); bb.putLong(c)
        return bb.array()
    }

    private fun invitar(nombre: String, ip: InetAddress, ctrlPort: Int) {
        if (!activo || conectado || invitando != null) return
        val inv = Par(nombre, ip, ctrlPort, ctrlPort + 1, Random.nextInt(), 0)
        invitando = inv
        avisar("Wi-Fi: conectando con " + nombre + "...", false)
        enviarCrudo(intercambio("IN", inv.token), ip, ctrlPort, true)
        // si no responde en 3 s, se prueba con el siguiente
        red?.postDelayed({ if (invitando === inv) { invitando = null; avisar(null, false) } }, 3000)
    }

    private fun sincronizar(p: Par) = enviarCrudo(reloj(0, ahora100us(), 0, 0), p.ip, p.datosPort, false)

    private fun despedir(p: Par, s: DatagramSocket?) {
        val bb = ByteBuffer.allocate(16)
        bb.put(0xFF.toByte()); bb.put(0xFF.toByte()); bb.put('B'.code.toByte()); bb.put('Y'.code.toByte())
        bb.putInt(2); bb.putInt(p.token); bb.putInt(ssrc)
        try { s?.send(DatagramPacket(bb.array(), 16, p.ip, p.ctrlPort)) } catch (_: Exception) {}
    }

    /** cada 10 s: si nosotros invitamos, mantenemos el reloj; si el Mac dejo de hablar, se da por cerrada */
    private val latido: Runnable = object : Runnable {
        override fun run() {
            if (!activo) return
            val p = par
            if (p != null && p.datosOk) {
                if (p.yoInvito) sincronizar(p)
                if (System.currentTimeMillis() - p.ultimoCk > 75_000) { par = null; avisar("Wi-Fi: se perdio la conexion", false) }
            }
            // sin conexion: se vuelve a intentar con las sesiones del Mac ya conocidas
            if (par == null && invitando == null && conocidos.isNotEmpty()) {
                val l = conocidos.values.toList()
                val (n, ip, port) = l[turno++ % l.size]
                invitar(n, ip, port)
            }
            red?.postDelayed(this, 10_000)
        }
    }

    private fun enviarCrudo(b: ByteArray, ip: InetAddress, port: Int, esCtrl: Boolean) {
        val s = (if (esCtrl) ctrl else datos) ?: return
        try { s.send(DatagramPacket(b, b.size, ip, port)) } catch (_: Exception) {}
    }

    // ------------------------------------------------------------------ MIDI
    /** manda un mensaje MIDI (hasta 15 bytes: notas, CC, program change, SysEx corto como MMC) */
    fun enviar(midi: ByteArray, n: Int) {
        val p = par ?: return
        val s = datos ?: return
        val r = red ?: return
        if (!p.datosOk || n <= 0 || n > 15) return
        val bytes = midi.copyOf(n)
        r.post {
            val bb = ByteBuffer.allocate(12 + 1 + n)
            seq = (seq + 1) and 0xFFFF
            bb.put(0x80.toByte()); bb.put(0x61.toByte())                 // RTP v2, tipo 97 (MIDI)
            bb.putShort(seq.toShort()); bb.putInt(ahora100us().toInt()); bb.putInt(ssrc)
            bb.put(n.toByte())                                  // cabecera corta, sin journal
            bb.put(bytes)
            try { s.send(DatagramPacket(bb.array(), bb.capacity(), p.ip, p.datosPort)) } catch (_: Exception) {}
        }
    }
}
