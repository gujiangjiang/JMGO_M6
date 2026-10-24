package com.gujiangjiang.jmgocontrol.core

import android.content.Context
import android.content.res.Configuration
import java.util.Locale

/**
 * 应用语言注入：
 * 1) App.onCreate 中调用 AppCompatDelegate.setApplicationLocales（官方 per-app locale
 *    向下兼容实现，API 14+ 可用，配合 manifest 中 AppLocalesMetadataHolderService）；
 * 2) 这里再兜一层 attachBaseContext 注入，确保在部分 ROM/旧系统上依然生效。
 * 两者语言值始终来自同一 AppPrefs，不会冲突。
 */
object LocaleHelper {

    fun localeFor(lang: String): Locale? = when (lang) {
        AppPrefs.LANG_EN -> Locale.ENGLISH
        AppPrefs.LANG_ZH -> Locale.SIMPLIFIED_CHINESE
        else -> null
    }

    fun wrap(base: Context): Context {
        val lang = try {
            AppPrefs(base).lang
        } catch (t: Throwable) {
            AppPrefs.LANG_SYSTEM
        }
        val locale = localeFor(lang) ?: return base
        Locale.setDefault(locale)
        val config = Configuration(base.resources.configuration)
        config.setLocale(locale)
        return base.createConfigurationContext(config)
    }
}
