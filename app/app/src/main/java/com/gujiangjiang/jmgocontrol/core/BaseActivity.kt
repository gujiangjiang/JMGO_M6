package com.gujiangjiang.jmgocontrol.core

import android.content.Context
import android.content.res.Configuration
import androidx.appcompat.app.AppCompatActivity
import java.util.Locale

/**
 * 所有 Activity 的基类：按应用设置（跟随系统 / 简体中文 / English）应用界面语言。
 * Android 7（API 24）不支持 LocaleManager，这里用 attachBaseContext 方案。
 */
open class BaseActivity : AppCompatActivity() {

    override fun attachBaseContext(newBase: Context) {
        val lang = try {
            AppPrefs(newBase).lang
        } catch (t: Throwable) {
            AppPrefs.LANG_SYSTEM
        }
        if (lang == AppPrefs.LANG_SYSTEM) {
            super.attachBaseContext(newBase)
            return
        }
        val locale = if (lang == AppPrefs.LANG_EN) Locale.ENGLISH else Locale.SIMPLIFIED_CHINESE
        Locale.setDefault(locale)
        val config = Configuration(newBase.resources.configuration)
        config.setLocale(locale)
        super.attachBaseContext(newBase.createConfigurationContext(config))
    }
}
