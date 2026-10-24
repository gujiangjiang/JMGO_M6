package com.gujiangjiang.jmgocontrol.core

import android.content.Context
import androidx.appcompat.app.AppCompatActivity

/**
 * 所有 Activity 的基类。
 * 语言切换由两层机制共同保证：
 * 1) AppCompatDelegate.setApplicationLocales（App.onCreate 应用，官方向下兼容实现）；
 * 2) attachBaseContext 注入 Locale（兜底，见 LocaleHelper）。
 */
open class BaseActivity : AppCompatActivity() {

    override fun attachBaseContext(newBase: Context) {
        super.attachBaseContext(LocaleHelper.wrap(newBase))
    }
}
