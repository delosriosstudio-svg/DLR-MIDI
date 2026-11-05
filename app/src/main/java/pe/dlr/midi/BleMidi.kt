package pe.dlr.midi

import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothGattServer
import android.bluetooth.BluetoothGattServerCallback
import android.bluetooth.BluetoothGattService
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.bluetooth.le.AdvertiseCallback
import android.bluetooth.le.AdvertiseData
import android.bluetooth.le.AdvertiseSettings
import android.bluetooth.le.BluetoothLeAdvertiser
import android.content.Context
import android.os.Build
import android.os.ParcelUuid
import java.util.UUID

/**
 * MIDI por Bluetooth (BLE MIDI estandar de Apple/MIDI Association).
 * El telefono se anuncia como un dispositivo "DLR MIDI". En el Mac:
 * Configuracion de Audio MIDI > Ventana > Mostrar Estudio MIDI > icono Bluetooth > DLR MIDI > Conectar.
 * Despues aparece en REAPER como entrada MIDI "DLR MIDI".
 */
@SuppressLint("MissingPermission")
class BleMidi(private val context: Context, private val aviso: (String?, Boolean) -> Unit) {

    private val main = android.os.Handler(android.os.Looper.getMainLooper())
    /** los avisos llegan desde hilos de Bluetooth: se pasan al hilo principal */
    private fun alCambiar(t: String?, ok: Boolean) { main.post { aviso(t, ok) } }

    companion object {
        val SERVICIO: UUID = UUID.fromString("03B80E5A-EDE8-4B33-A751-6CE34EC4C700")
        val CARACTERISTICA: UUID = UUID.fromString("7772E5DB-3868-4112-A1A9-F2669D106BF3")
        val CCCD: UUID = UUID.fromString("00002902-0000-1000-8000-00805F9B34FB")

        fun permisos(): Array<String> =
            if (Build.VERSION.SDK_INT >= 31)
                arrayOf(android.Manifest.permission.BLUETOOTH_ADVERTISE, android.Manifest.permission.BLUETOOTH_CONNECT)
            else emptyArray()
    }

    private val manager = context.getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager?
    private var servidor: BluetoothGattServer? = null
    private var anunciador: BluetoothLeAdvertiser? = null
    private var caract: BluetoothGattCharacteristic? = null
    @Volatile private var central: BluetoothDevice? = null
    @Volatile private var suscrito = false
    @Volatile private var mtu = 23
    private var activo = false
    private var nombre = ""
    private val t0 = System.nanoTime()

    val conectado: Boolean get() = central != null && suscrito
    val nombrePar: String get() = central?.name ?: "Bluetooth"

    private fun tienePermiso(): Boolean {
        if (Build.VERSION.SDK_INT < 31) return true
        return permisos().all { context.checkSelfPermission(it) == android.content.pm.PackageManager.PERMISSION_GRANTED }
    }

    fun iniciar() {
        if (activo) return
        val m = manager
        val bt = m?.adapter
        if (m == null || bt == null || !context.packageManager.hasSystemFeature(android.content.pm.PackageManager.FEATURE_BLUETOOTH_LE)) {
            alCambiar("Este telefono no tiene Bluetooth LE", false); return
        }
        if (!tienePermiso()) { alCambiar("Falta el permiso de Bluetooth", false); return }
        if (!bt.isEnabled) { alCambiar("Bluetooth apagado", false); return }
        activo = true
        try {
            val srv = m.openGattServer(context, callbackServidor) ?: run { activo = false; alCambiar("No se pudo abrir Bluetooth", false); return }
            servidor = srv
            val c = BluetoothGattCharacteristic(
                CARACTERISTICA,
                BluetoothGattCharacteristic.PROPERTY_READ or BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE or
                    BluetoothGattCharacteristic.PROPERTY_WRITE or BluetoothGattCharacteristic.PROPERTY_NOTIFY,
                BluetoothGattCharacteristic.PERMISSION_READ or BluetoothGattCharacteristic.PERMISSION_WRITE)
            c.addDescriptor(BluetoothGattDescriptor(CCCD, BluetoothGattDescriptor.PERMISSION_READ or BluetoothGattDescriptor.PERMISSION_WRITE))
            caract = c
            val s = BluetoothGattService(SERVICIO, BluetoothGattService.SERVICE_TYPE_PRIMARY)
            s.addCharacteristic(c)
            srv.addService(s)   // al terminar llama onServiceAdded -> empieza a anunciarse
        } catch (e: Exception) { activo = false; alCambiar("Bluetooth: " + (e.message ?: "error"), false) }
    }

    fun detener() {
        activo = false
        try { anunciador?.stopAdvertising(callbackAnuncio) } catch (_: Exception) {}
        try { servidor?.close() } catch (_: Exception) {}
        servidor = null; anunciador = null; caract = null; central = null; suscrito = false
    }

    private fun anunciar() {
        val bt = manager?.adapter ?: return
        if (!activo) return
        try { bt.name = "DLR MIDI" } catch (_: Exception) {}
        val a = bt.bluetoothLeAdvertiser ?: run { alCambiar("Este telefono no puede anunciarse por Bluetooth", false); return }
        anunciador = a
        val ajustes = AdvertiseSettings.Builder()
            .setAdvertiseMode(AdvertiseSettings.ADVERTISE_MODE_LOW_LATENCY)
            .setTxPowerLevel(AdvertiseSettings.ADVERTISE_TX_POWER_HIGH)
            .setConnectable(true).setTimeout(0).build()
        val datos = AdvertiseData.Builder().addServiceUuid(ParcelUuid(SERVICIO)).setIncludeDeviceName(false).build()
        val respuesta = AdvertiseData.Builder().setIncludeDeviceName(true).build()
        try { a.stopAdvertising(callbackAnuncio); a.startAdvertising(ajustes, datos, respuesta, callbackAnuncio) }
        catch (e: Exception) { alCambiar("Bluetooth: " + (e.message ?: "error"), false) }
    }

    private val callbackAnuncio = object : AdvertiseCallback() {
        override fun onStartSuccess(settingsInEffect: AdvertiseSettings?) {
            if (central == null) alCambiar("Bluetooth listo · conecta \"DLR MIDI\" desde el Mac", false)
        }
        override fun onStartFailure(errorCode: Int) { alCambiar("No se pudo anunciar por Bluetooth ($errorCode)", false) }
    }

    private val callbackServidor = object : BluetoothGattServerCallback() {
        override fun onServiceAdded(status: Int, service: BluetoothGattService?) { if (status == BluetoothGatt.GATT_SUCCESS) anunciar() }

        override fun onConnectionStateChange(device: BluetoothDevice, status: Int, newState: Int) {
            if (newState == BluetoothProfile.STATE_CONNECTED) {
                central = device; mtu = 23
                try { anunciador?.stopAdvertising(callbackAnuncio) } catch (_: Exception) {}
                alCambiar(null, false)
            } else if (device.address == central?.address) {
                central = null; suscrito = false
                alCambiar("Bluetooth desconectado", false)
                anunciar()   // volver a anunciarse para que el Mac reconecte
            }
        }

        override fun onMtuChanged(device: BluetoothDevice, m: Int) { mtu = m }

        override fun onCharacteristicReadRequest(d: BluetoothDevice, id: Int, offset: Int, c: BluetoothGattCharacteristic) {
            // la especificacion pide que un read devuelva una respuesta vacia valida
            servidor?.sendResponse(d, id, BluetoothGatt.GATT_SUCCESS, 0, byteArrayOf())
        }

        override fun onDescriptorReadRequest(d: BluetoothDevice, id: Int, offset: Int, desc: BluetoothGattDescriptor) {
            val v = if (suscrito) BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE else BluetoothGattDescriptor.DISABLE_NOTIFICATION_VALUE
            servidor?.sendResponse(d, id, BluetoothGatt.GATT_SUCCESS, 0, v)
        }

        override fun onDescriptorWriteRequest(d: BluetoothDevice, id: Int, desc: BluetoothGattDescriptor,
                                              preparar: Boolean, respuesta: Boolean, offset: Int, valor: ByteArray?) {
            if (desc.uuid == CCCD) {
                suscrito = valor != null && valor.isNotEmpty() && (valor[0].toInt() and 0x01) != 0
                central = d
                alCambiar(null, false)   // Midi.kt vuelve a calcular el estado
            }
            if (respuesta) servidor?.sendResponse(d, id, BluetoothGatt.GATT_SUCCESS, 0, valor)
        }

        override fun onCharacteristicWriteRequest(d: BluetoothDevice, id: Int, c: BluetoothGattCharacteristic, preparar: Boolean,
                                                  respuesta: Boolean, offset: Int, valor: ByteArray?) {
            if (respuesta) servidor?.sendResponse(d, id, BluetoothGatt.GATT_SUCCESS, 0, valor)
        }
    }

    /** marca de tiempo BLE MIDI: 13 bits en milisegundos */
    private fun marca(): Int = (((System.nanoTime() - t0) / 1_000_000L) and 0x1FFF).toInt()

    /** manda un mensaje MIDI (canal o SysEx completo con F0..F7) */
    fun enviar(b: ByteArray, n: Int) {
        val srv = servidor; val c = caract; val d = central
        if (srv == null || c == null || d == null || !suscrito) return
        val t = marca()
        val cab = (0x80 or ((t shr 7) and 0x3F)).toByte()
        val ts = (0x80 or (t and 0x7F)).toByte()
        val salida = java.io.ByteArrayOutputStream()
        salida.write(cab.toInt()); salida.write(ts.toInt())
        if ((b[0].toInt() and 0xFF) == 0xF0) {
            // SysEx: F0 ... [marca] F7
            salida.write(b, 0, n - 1)
            salida.write(ts.toInt())
            salida.write(0xF7)
        } else {
            salida.write(b, 0, n)
        }
        val paquete = salida.toByteArray()
        synchronized(this) {
            try {
                c.value = paquete
                srv.notifyCharacteristicChanged(d, c, false)
            } catch (_: Exception) {}
        }
    }
}
