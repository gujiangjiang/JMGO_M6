package com.gujiangjiang.jmgocontrol.ui

import android.content.Context
import android.content.res.Configuration

object Ui {

    /** 横屏或宽屏（平板/投影仪/TV）时列表用双栏 */
    fun span(context: Context): Int {
        val cfg = context.resources.configuration
        return if (cfg.orientation == Configuration.ORIENTATION_LANDSCAPE ||
            cfg.screenWidthDp >= 600
        ) 2 else 1
    }
}
