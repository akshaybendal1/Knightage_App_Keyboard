package com.knightage.btkeyboard

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothHidDevice
import android.bluetooth.BluetoothHidDeviceAppSdpSettings
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.content.Context
import android.util.Log
import java.util.concurrent.Executors

/**
 * Owns the BluetoothHidDevice proxy: registers this phone as a HID keyboard/mouse,
 * connects to a host (phone, laptop, tablet, TV) and sends input reports.
 *
 * All public methods must be called on the main thread; listener callbacks arrive on it too.
 * Callers are responsible for holding BLUETOOTH_CONNECT before calling [start].
 */
@SuppressLint("MissingPermission")
object HidManager {
    private const val TAG = "HidManager"
    private const val PREFS = "hid"
    private const val KEY_LAST_DEVICE = "last_device"

    enum class State { UNSUPPORTED, BLUETOOTH_OFF, STARTING, READY, CONNECTING, CONNECTED }

    fun interface Listener {
        fun onStateChanged(state: State, device: BluetoothDevice?)
    }

    var state = State.STARTING
        private set
    var connectedDevice: BluetoothDevice? = null
        private set

    private lateinit var appContext: Context
    private var adapter: BluetoothAdapter? = null
    private var hid: BluetoothHidDevice? = null
    private var registered = false
    private val listeners = mutableListOf<Listener>()

    /** Serialises multi-report sequences (typing strings) so reports go out in order. */
    private val sender = Executors.newSingleThreadExecutor()

    fun addListener(l: Listener) {
        listeners += l
        l.onStateChanged(state, connectedDevice)
    }

    fun removeListener(l: Listener) {
        listeners -= l
    }

    private fun setState(s: State, device: BluetoothDevice? = connectedDevice) {
        state = s
        connectedDevice = device
        listeners.toList().forEach { it.onStateChanged(s, device) }
    }

    fun start(context: Context) {
        appContext = context.applicationContext
        if (hid != null) {
            if (!registered) register()
            return
        }
        val a = appContext.getSystemService(BluetoothManager::class.java)?.adapter
        adapter = a
        if (a == null) return setState(State.UNSUPPORTED)
        if (!a.isEnabled) return setState(State.BLUETOOTH_OFF)
        setState(State.STARTING)
        val ok = a.getProfileProxy(appContext, object : BluetoothProfile.ServiceListener {
            override fun onServiceConnected(profile: Int, proxy: BluetoothProfile) {
                hid = proxy as BluetoothHidDevice
                register()
            }

            override fun onServiceDisconnected(profile: Int) {
                hid = null
                registered = false
                setState(State.STARTING, null)
            }
        }, BluetoothProfile.HID_DEVICE)
        // Some OEM builds strip the HID Device profile entirely.
        if (!ok) setState(State.UNSUPPORTED)
    }

    private val callback = object : BluetoothHidDevice.Callback() {
        override fun onAppStatusChanged(pluggedDevice: BluetoothDevice?, registered: Boolean) {
            this@HidManager.registered = registered
            Log.d(TAG, "app registered=$registered plugged=$pluggedDevice")
            if (!registered) {
                setState(State.STARTING, null)
                return
            }
            setState(State.READY, null)
            // Reconnect to whatever host we were last paired with.
            val target = pluggedDevice ?: lastDevice()
            if (target != null) connect(target)
        }

        override fun onConnectionStateChanged(device: BluetoothDevice, state: Int) {
            Log.d(TAG, "connection ${device.address} -> $state")
            when (state) {
                BluetoothProfile.STATE_CONNECTED -> {
                    rememberDevice(device)
                    setState(State.CONNECTED, device)
                }
                BluetoothProfile.STATE_CONNECTING -> setState(State.CONNECTING, device)
                BluetoothProfile.STATE_DISCONNECTED ->
                    if (connectedDevice == null || connectedDevice == device) {
                        setState(if (registered) State.READY else State.STARTING, null)
                    }
            }
        }

        // Hosts (notably Windows and some Android versions) poll reports during setup;
        // an unanswered GET_REPORT can stall the connection.
        override fun onGetReport(device: BluetoothDevice, type: Byte, id: Byte, bufferSize: Int) {
            val len = when (id.toInt()) {
                HidDescriptor.ID_KEYBOARD -> HidDescriptor.KEYBOARD_LEN
                HidDescriptor.ID_MOUSE -> HidDescriptor.MOUSE_LEN
                HidDescriptor.ID_CONSUMER -> HidDescriptor.CONSUMER_LEN
                else -> {
                    hid?.reportError(device, BluetoothHidDevice.ERROR_RSP_INVALID_RPT_ID)
                    return
                }
            }
            hid?.replyReport(device, type, id, ByteArray(len))
        }

        override fun onSetReport(device: BluetoothDevice, type: Byte, id: Byte, data: ByteArray) {
            hid?.reportError(device, BluetoothHidDevice.ERROR_RSP_SUCCESS)
        }
    }

    private fun register() {
        val h = hid ?: return
        val sdp = BluetoothHidDeviceAppSdpSettings(
            "BT Keyboard",
            "Phone keyboard and touchpad",
            "Knightage",
            BluetoothHidDevice.SUBCLASS1_COMBO,
            HidDescriptor.DESCRIPTOR
        )
        val ok = h.registerApp(sdp, null, null, appContext.mainExecutor, callback)
        // false usually means another app already holds the HID Device slot.
        if (!ok) setState(State.UNSUPPORTED)
    }

    /** Release the HID registration so other apps (or a fresh start) can claim it. */
    fun stop() {
        val h = hid
        if (h != null) {
            connectedDevice?.let { h.disconnect(it) }
            if (registered) h.unregisterApp()
            adapter?.closeProfileProxy(BluetoothProfile.HID_DEVICE, h)
        }
        hid = null
        registered = false
        setState(State.STARTING, null)
    }

    fun bondedDevices(): List<BluetoothDevice> =
        adapter?.bondedDevices?.sortedBy { it.name ?: it.address } ?: emptyList()

    fun connect(device: BluetoothDevice) {
        val h = hid ?: return
        connectedDevice?.takeIf { it != device }?.let { h.disconnect(it) }
        setState(State.CONNECTING, device)
        if (!h.connect(device)) setState(State.READY, null)
    }

    fun disconnect() {
        val d = connectedDevice ?: return
        hid?.disconnect(d)
        appContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().remove(KEY_LAST_DEVICE).apply()
    }

    private fun rememberDevice(d: BluetoothDevice) {
        appContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putString(KEY_LAST_DEVICE, d.address).apply()
    }

    private fun lastDevice(): BluetoothDevice? {
        val addr = appContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getString(KEY_LAST_DEVICE, null) ?: return null
        return bondedDevices().firstOrNull { it.address == addr }
    }

    // ---------------------------------------------------------------- reports

    private fun send(id: Int, data: ByteArray): Boolean {
        val h = hid ?: return false
        val d = connectedDevice ?: return false
        if (state != State.CONNECTED) return false
        return h.sendReport(d, id, data)
    }

    /** Keyboard report: [modifiers] + up to 6 simultaneously held key usages. */
    fun sendKeys(modifiers: Int, keys: List<Int>) {
        val r = ByteArray(HidDescriptor.KEYBOARD_LEN)
        r[0] = modifiers.toByte()
        keys.take(6).forEachIndexed { i, k -> r[2 + i] = k.toByte() }
        send(HidDescriptor.ID_KEYBOARD, r)
    }

    fun tapKey(modifiers: Int, usage: Int) {
        sendKeys(modifiers, listOf(usage))
        sendKeys(0, emptyList())
    }

    fun sendMouse(buttons: Int, dx: Int, dy: Int, wheel: Int) {
        // Split large deltas so nothing is lost to the -127..127 report range.
        var rx = dx
        var ry = dy
        var rw = wheel
        do {
            val x = rx.coerceIn(-127, 127)
            val y = ry.coerceIn(-127, 127)
            val w = rw.coerceIn(-127, 127)
            send(HidDescriptor.ID_MOUSE, byteArrayOf(buttons.toByte(), x.toByte(), y.toByte(), w.toByte()))
            rx -= x; ry -= y; rw -= w
        } while (rx != 0 || ry != 0 || rw != 0)
    }

    fun tapConsumer(usage: Int) {
        send(HidDescriptor.ID_CONSUMER, byteArrayOf((usage and 0xFF).toByte(), (usage shr 8).toByte()))
        send(HidDescriptor.ID_CONSUMER, ByteArray(HidDescriptor.CONSUMER_LEN))
    }

    /** Types a string (US layout, ASCII only). Returns the number of characters skipped. */
    fun typeText(text: String): Int {
        var skipped = 0
        val strokes = text.mapNotNull { c -> KeyCodes.strokeFor(c).also { if (it == null) skipped++ } }
        sender.execute {
            for (s in strokes) {
                sendKeys(if (s.shift) KeyCodes.MOD_SHIFT else 0, listOf(s.usage))
                Thread.sleep(6)
                sendKeys(0, emptyList())
                Thread.sleep(6)
            }
        }
        return skipped
    }
}
