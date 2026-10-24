package com.gujiangjiang.jmgocontrol

import android.app.Application
import androidx.appcompat.app.AppCompatDelegate
import androidx.core.os.LocaleListCompat
import com.gujiangjiang.jmgocontrol.core.AppPrefs

/**
 * 使用 AppCompat 官方 per-app locale（AppCompatDelegate.setApplicationLocales），
 * 可在 Android 7 上可靠切换应用语言并自动重建界面。
 */
class App : Application() {

    override fun onCreate() {
        super.onCreate()
        applyLang(AppPrefs(this).lang)
    }

    companion object {
        fun applyLang(lang: String) {
            val locales = when (lang) {
                AppPrefs.LANG_EN -> LocaleListCompat.forLanguageTags("en")
                AppPrefs.LANG_ZH -> LocaleListCompat.forLanguageTags("zh-CN")
                else -> LocaleListCompat.getEmptyLocaleList()
            }
            AppCompatDelegate.setApplicationLocales(locales)
        }
    }
}
