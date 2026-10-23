package com.gujiangjiang.jmgocontrol.core

import android.view.KeyEvent

/**
 * 实验室解锁手势：
 * - 按键序列（上上下下左右左右 OK），4 秒内完成；
 * - 或连续点击入口行 7 次（2 秒间隔内）。
 */
class LabUnlockTracker {

    private val seq = intArrayOf(
        KeyEvent.KEYCODE_DPAD_UP, KeyEvent.KEYCODE_DPAD_UP,
        KeyEvent.KEYCODE_DPAD_DOWN, KeyEvent.KEYCODE_DPAD_DOWN,
        KeyEvent.KEYCODE_DPAD_LEFT, KeyEvent.KEYCODE_DPAD_RIGHT,
        KeyEvent.KEYCODE_DPAD_LEFT, KeyEvent.KEYCODE_DPAD_RIGHT,
        KeyEvent.KEYCODE_DPAD_CENTER
    )
    private var pos = 0
    private var at = 0L
    private var taps = 0
    private var tapAt = 0L

    /** @return true 表示序列刚刚完成 */
    fun onKey(keyCode: Int): Boolean {
        val now = System.currentTimeMillis()
        if (now - at > 4000) pos = 0
        at = now
        if (keyCode == seq[pos]) {
            pos++
            if (pos == seq.size) {
                pos = 0
                return true
            }
        } else {
            pos = if (keyCode == seq[0]) 1 else 0
        }
        return false
    }

    /** @return true 表示点击次数达到 7 次 */
    fun onTap(): Boolean {
        val now = System.currentTimeMillis()
        if (now - tapAt > 2000) taps = 0
        tapAt = now
        taps++
        if (taps >= 7) {
            taps = 0
            return true
        }
        return false
    }
}
