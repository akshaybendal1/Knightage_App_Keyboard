package com.knightage.btkeyboard

import android.Manifest
import android.annotation.SuppressLint
import android.app.Activity
import android.app.AlertDialog
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.Bundle
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.view.ViewGroup.LayoutParams.WRAP_CONTENT
import android.view.inputmethod.EditorInfo
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast

@SuppressLint("MissingPermission", "SetTextI18n")
class MainActivity : Activity() {

    private enum class Mode { KEYBOARD, TOUCHPAD, BOTH }

    private lateinit var status: TextView
    private lateinit var connectButton: Button
    private lateinit var keyboard: KeyboardView
    private lateinit var touchpad: TouchpadView
    private lateinit var modeButtons: Map<Mode, Button>
    private var mode = Mode.BOTH

    private val dp get() = resources.displayMetrics.density

    private val hidListener = HidManager.Listener { state, device -> render(state, device) }

    private val btStateReceiver = object : BroadcastReceiver() {
        override fun onReceive(c: Context, i: Intent) {
            when (i.getIntExtra(BluetoothAdapter.EXTRA_STATE, -1)) {
                BluetoothAdapter.STATE_ON -> HidManager.start(this@MainActivity)
                BluetoothAdapter.STATE_OFF -> {
                    HidManager.stop()
                    HidManager.start(this@MainActivity) // reports BLUETOOTH_OFF
                }
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(buildUi())
        mode = savedInstanceState?.getString("mode")?.let { Mode.valueOf(it) } ?: Mode.BOTH
        applyMode()
        registerReceiver(btStateReceiver, IntentFilter(BluetoothAdapter.ACTION_STATE_CHANGED))
        HidManager.addListener(hidListener)
        if (hasPermissions()) HidManager.start(this) else requestPermissions(requiredPermissions(), REQ_PERMS)
    }

    override fun onSaveInstanceState(out: Bundle) {
        super.onSaveInstanceState(out)
        out.putString("mode", mode.name)
    }

    override fun onDestroy() {
        HidManager.removeListener(hidListener)
        unregisterReceiver(btStateReceiver)
        // Leaving the app: give up the HID slot so the host sees us disconnect cleanly.
        if (isFinishing) HidManager.stop()
        super.onDestroy()
    }

    // ------------------------------------------------------------ permissions

    private fun requiredPermissions(): Array<String> =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S)
            arrayOf(Manifest.permission.BLUETOOTH_CONNECT, Manifest.permission.BLUETOOTH_ADVERTISE)
        else emptyArray()

    private fun hasPermissions() = requiredPermissions().all {
        checkSelfPermission(it) == PackageManager.PERMISSION_GRANTED
    }

    override fun onRequestPermissionsResult(code: Int, perms: Array<out String>, results: IntArray) {
        if (code != REQ_PERMS) return
        if (hasPermissions()) HidManager.start(this)
        else status.text = "Bluetooth permission is required. Grant it in Settings → Apps."
    }

    // ------------------------------------------------------------------- UI

    private fun buildUi(): View {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(0xFF11151C.toInt())
            fitsSystemWindows = true
        }

        // Top bar: connection status + connect button
        val top = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding((12 * dp).toInt(), (8 * dp).toInt(), (8 * dp).toInt(), (4 * dp).toInt())
        }
        status = TextView(this).apply {
            setTextColor(0xFFE8ECF3.toInt())
            textSize = 14f
        }
        connectButton = pillButton("Connect") { showDevicePicker() }
        top.addView(status, LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f))
        top.addView(connectButton)
        root.addView(top)

        // Mode selector
        val modes = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding((8 * dp).toInt(), 0, (8 * dp).toInt(), (4 * dp).toInt())
        }
        modeButtons = linkedMapOf(
            Mode.KEYBOARD to pillButton("Keyboard") { mode = Mode.KEYBOARD; applyMode() },
            Mode.TOUCHPAD to pillButton("Touchpad") { mode = Mode.TOUCHPAD; applyMode() },
            Mode.BOTH to pillButton("Both") { mode = Mode.BOTH; applyMode() }
        )
        modeButtons.values.forEach {
            modes.addView(it, LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f).apply {
                marginStart = (4 * dp).toInt(); marginEnd = (4 * dp).toInt()
            })
        }
        root.addView(modes)

        // Quick text row: type with the phone's own keyboard (voice, swipe, autocorrect) and send.
        val textRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding((8 * dp).toInt(), 0, (8 * dp).toInt(), (4 * dp).toInt())
        }
        val input = EditText(this).apply {
            hint = "Type a phrase and send…"
            setHintTextColor(0xFF6B7587.toInt())
            setTextColor(0xFFE8ECF3.toInt())
            textSize = 14f
            isSingleLine = true
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
            imeOptions = EditorInfo.IME_ACTION_SEND
            background = rounded(0xFF1F2530.toInt())
            setPadding((12 * dp).toInt(), (8 * dp).toInt(), (12 * dp).toInt(), (8 * dp).toInt())
        }
        val sendText = {
            val t = input.text.toString()
            if (t.isNotEmpty()) {
                if (HidManager.state != HidManager.State.CONNECTED) {
                    toast("Not connected")
                } else {
                    val skipped = HidManager.typeText(t)
                    if (skipped > 0) toast("$skipped unsupported character(s) skipped")
                    input.setText("")
                }
            }
        }
        input.setOnEditorActionListener { _, id, _ ->
            if (id == EditorInfo.IME_ACTION_SEND) { sendText(); true } else false
        }
        textRow.addView(input, LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f))
        textRow.addView(pillButton("Send") { sendText() }, LinearLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT).apply {
            marginStart = (6 * dp).toInt()
        })
        root.addView(textRow)

        touchpad = TouchpadView(this)
        keyboard = KeyboardView(this)
        root.addView(touchpad, LinearLayout.LayoutParams(MATCH_PARENT, 0, 1f))
        root.addView(keyboard, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))
        return root
    }

    private fun applyMode() {
        touchpad.visibility = if (mode == Mode.KEYBOARD) View.GONE else View.VISIBLE
        keyboard.visibility = if (mode == Mode.TOUCHPAD) View.GONE else View.VISIBLE
        // Keyboard-only mode: let the keyboard grow to fill the screen for bigger keys.
        keyboard.layoutParams = if (mode == Mode.KEYBOARD)
            LinearLayout.LayoutParams(MATCH_PARENT, 0, 1f)
        else LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT)
        modeButtons.forEach { (m, b) ->
            b.background = rounded(if (m == mode) 0xFF4C8DFF.toInt() else 0xFF2A3140.toInt())
        }
    }

    private fun render(state: HidManager.State, device: BluetoothDevice?) {
        val name = device?.let { it.name ?: it.address } ?: ""
        status.text = when (state) {
            HidManager.State.UNSUPPORTED ->
                "This phone doesn't allow Bluetooth HID (keyboard) mode, or another app is using it."
            HidManager.State.BLUETOOTH_OFF -> "Bluetooth is off"
            HidManager.State.STARTING -> "Starting Bluetooth HID…"
            HidManager.State.READY -> "Not connected"
            HidManager.State.CONNECTING -> "Connecting to $name…"
            HidManager.State.CONNECTED -> "● Connected to $name"
        }
        status.setTextColor(if (state == HidManager.State.CONNECTED) 0xFF5BD68A.toInt() else 0xFFE8ECF3.toInt())
        connectButton.text = when (state) {
            HidManager.State.CONNECTED -> "Devices"
            HidManager.State.BLUETOOTH_OFF -> "Turn on"
            else -> "Connect"
        }
    }

    private fun showDevicePicker() {
        if (!hasPermissions()) {
            requestPermissions(requiredPermissions(), REQ_PERMS)
            return
        }
        when (HidManager.state) {
            HidManager.State.BLUETOOTH_OFF -> {
                startActivity(Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE))
                return
            }
            HidManager.State.UNSUPPORTED, HidManager.State.STARTING -> {
                HidManager.start(this)
                toast("Bluetooth HID not ready yet")
                return
            }
            else -> Unit
        }
        val devices = HidManager.bondedDevices()
        val labels = devices.map { d ->
            val n = d.name ?: d.address
            if (d == HidManager.connectedDevice) "● $n" else n
        }.toMutableList()
        labels += "＋ Pair a new device (make this phone visible)"
        if (HidManager.connectedDevice != null) labels += "Disconnect"

        AlertDialog.Builder(this)
            .setTitle("Connect to")
            .setItems(labels.toTypedArray()) { _, which ->
                when {
                    which < devices.size -> HidManager.connect(devices[which])
                    which == devices.size -> makeDiscoverable()
                    else -> HidManager.disconnect()
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun makeDiscoverable() {
        startActivity(Intent(BluetoothAdapter.ACTION_REQUEST_DISCOVERABLE)
            .putExtra(BluetoothAdapter.EXTRA_DISCOVERABLE_DURATION, 120))
        AlertDialog.Builder(this)
            .setTitle("Pair from the other device")
            .setMessage(
                "1. Keep this app open.\n" +
                "2. On your laptop / phone / tablet open Bluetooth settings and pick this phone.\n" +
                "3. Accept the pairing code on both screens.\n\n" +
                "It will show up as a keyboard + mouse. Next time, just tap Connect and choose it."
            )
            .setPositiveButton("OK", null)
            .show()
    }

    // -------------------------------------------------------------- helpers

    private fun rounded(color: Int) = GradientDrawable().apply {
        setColor(color)
        cornerRadius = 10 * dp
    }

    private fun pillButton(text: String, onClick: () -> Unit) = Button(this).apply {
        this.text = text
        isAllCaps = false
        textSize = 13f
        setTextColor(0xFFE8ECF3.toInt())
        background = rounded(0xFF2A3140.toInt())
        minHeight = (36 * dp).toInt(); minimumHeight = minHeight
        setPadding((14 * dp).toInt(), 0, (14 * dp).toInt(), 0)
        stateListAnimator = null
        setOnClickListener { onClick() }
    }

    private fun toast(msg: String) = Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()

    companion object {
        private const val REQ_PERMS = 1
    }
}
