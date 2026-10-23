package com.gujiangjiang.jmgocontrol.core

import android.content.Context
import android.content.SharedPreferences

class AppPrefs(context: Context) {

    private val sp: SharedPreferences =
        context.getSharedPreferences("jmgo_control", Context.MODE_PRIVATE)

    var mode: String
        get() = sp.getString(KEY_MODE, MODE_UNSET) ?: MODE_UNSET
        set(v) {
            sp.edit().putString(KEY_MODE, v).apply()
        }

    var labUnlocked: Boolean
        get() = sp.getBoolean(KEY_LAB, false)
        set(v) {
            sp.edit().putBoolean(KEY_LAB, v).apply()
        }

    var brightnessFloor: Int
        get() = sp.getInt(KEY_BRI_FLOOR, DEFAULT_BRI_FLOOR)
        set(v) {
            sp.edit().putInt(KEY_BRI_FLOOR, v.coerceIn(0, 50)).apply()
        }

    var agentEnabled: Boolean
        get() = sp.getBoolean(KEY_AGENT, true)
        set(v) {
            sp.edit().putBoolean(KEY_AGENT, v).apply()
        }

    var lastRemoteIp: String
        get() = sp.getString(KEY_LAST_IP, "") ?: ""
        set(v) {
            sp.edit().putString(KEY_LAST_IP, v).apply()
        }

    var lastRemoteName: String
        get() = sp.getString(KEY_LAST_NAME, "") ?: ""
        set(v) {
            sp.edit().putString(KEY_LAST_NAME, v).apply()
        }

    /** 手机端保存的 Agent PIN（连接投影仪远程助手时使用） */
    var agentClientPin: String
        get() = sp.getString(KEY_AGENT_CLIENT_PIN, "") ?: ""
        set(v) {
            sp.edit().putString(KEY_AGENT_CLIENT_PIN, v).apply()
        }

    /** 远程助手 PIN（首次访问时自动生成 6 位数字） */
    val agentPin: String
        get() {
            var p = sp.getString(KEY_AGENT_PIN, "") ?: ""
            if (p.isEmpty()) {
                p = (100000 + (Math.random() * 900000).toInt()).toString()
                sp.edit().putString(KEY_AGENT_PIN, p).apply()
            }
            return p
        }

    fun regenerateAgentPin(): String {
        val p = (100000 + (Math.random() * 900000).toInt()).toString()
        sp.edit().putString(KEY_AGENT_PIN, p).apply()
        return p
    }

    companion object {
        const val MODE_UNSET = "unset"
        const val MODE_LOCAL = "local"
        const val MODE_REMOTE = "remote"
        const val DEFAULT_BRI_FLOOR = 8

        private const val KEY_MODE = "mode"
        private const val KEY_LAB = "lab_unlocked"
        private const val KEY_BRI_FLOOR = "brightness_floor"
        private const val KEY_AGENT = "agent_enabled"
        private const val KEY_LAST_IP = "last_remote_ip"
        private const val KEY_LAST_NAME = "last_remote_name"
        private const val KEY_AGENT_PIN = "agent_pin"
        private const val KEY_AGENT_CLIENT_PIN = "agent_client_pin"
    }
}
