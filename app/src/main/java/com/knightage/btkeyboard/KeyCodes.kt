package com.knightage.btkeyboard

/** USB HID usage IDs (Keyboard/Keypad page 0x07, Consumer page 0x0C) and a US-layout char map. */
object KeyCodes {
    // Modifier bits in byte 0 of the keyboard report
    const val MOD_CTRL = 0x01
    const val MOD_SHIFT = 0x02
    const val MOD_ALT = 0x04
    const val MOD_GUI = 0x08

    const val ENTER = 0x28
    const val ESC = 0x29
    const val BACKSPACE = 0x2A
    const val TAB = 0x2B
    const val SPACE = 0x2C
    const val CAPS_LOCK = 0x39
    const val F1 = 0x3A
    const val PRINT_SCREEN = 0x46
    const val INSERT = 0x49
    const val HOME = 0x4A
    const val PAGE_UP = 0x4B
    const val DELETE = 0x4C
    const val END = 0x4D
    const val PAGE_DOWN = 0x4E
    const val RIGHT = 0x4F
    const val LEFT = 0x50
    const val DOWN = 0x51
    const val UP = 0x52

    // Consumer page usages
    const val MEDIA_PLAY_PAUSE = 0xCD
    const val MEDIA_NEXT = 0xB5
    const val MEDIA_PREV = 0xB6
    const val MEDIA_MUTE = 0xE2
    const val MEDIA_VOL_UP = 0xE9
    const val MEDIA_VOL_DOWN = 0xEA

    /** A key usage plus whether Shift must be held to produce the character (US layout). */
    data class Stroke(val usage: Int, val shift: Boolean)

    private val unshifted = "1234567890-=[]\\;',./`"
    private val shifted = "!@#$%^&*()_+{}|:\"<>?~"
    private val punctUsages = intArrayOf(
        0x1E, 0x1F, 0x20, 0x21, 0x22, 0x23, 0x24, 0x25, 0x26, 0x27, // 1..0
        0x2D, 0x2E, 0x2F, 0x30, 0x31, 0x33, 0x34, 0x36, 0x37, 0x38, // - = [ ] \ ; ' , . /
        0x35                                                       // `
    )

    fun strokeFor(c: Char): Stroke? = when (c) {
        in 'a'..'z' -> Stroke(0x04 + (c - 'a'), false)
        in 'A'..'Z' -> Stroke(0x04 + (c - 'A'), true)
        ' ' -> Stroke(SPACE, false)
        '\n' -> Stroke(ENTER, false)
        '\t' -> Stroke(TAB, false)
        else -> {
            val i = unshifted.indexOf(c)
            val j = shifted.indexOf(c)
            when {
                i >= 0 -> Stroke(punctUsages[i], false)
                j >= 0 -> Stroke(punctUsages[j], true)
                else -> null
            }
        }
    }
}
