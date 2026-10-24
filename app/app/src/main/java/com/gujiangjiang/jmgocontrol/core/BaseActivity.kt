package com.gujiangjiang.jmgocontrol.core

import androidx.appcompat.app.AppCompatActivity

/**
 * 所有 Activity 的基类。
 * 语言切换由 AppCompat 官方 per-app locale 实现（见 App.applyLang），
 * 这里不再手动包裹 Context，避免与 AppCompat 内部机制冲突。
 */
open class BaseActivity : AppCompatActivity()
