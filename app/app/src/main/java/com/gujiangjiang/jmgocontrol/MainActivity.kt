package com.gujiangjiang.jmgocontrol

import android.os.Bundle
import android.view.Gravity
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity

class MainActivity : AppCompatActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val dp = resources.displayMetrics.density
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setBackgroundColor(getColor(R.color.bg))
        }
        val logo = ImageView(this).apply {
            setImageResource(R.drawable.ic_app_logo)
        }
        root.addView(logo, LinearLayout.LayoutParams((96 * dp).toInt(), (96 * dp).toInt()))
        root.addView(TextView(this).apply {
            text = getString(R.string.app_name)
            textSize = 22f
            setTextColor(getColor(R.color.text_primary))
            gravity = Gravity.CENTER
        }, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.WRAP_CONTENT,
            LinearLayout.LayoutParams.WRAP_CONTENT
        ).apply { topMargin = (16 * dp).toInt() })
        root.addView(TextView(this).apply {
            text = getString(R.string.build_info, BuildConfig.VERSION_NAME, BuildConfig.VERSION_CODE)
            textSize = 13f
            setTextColor(getColor(R.color.text_secondary))
            gravity = Gravity.CENTER
        }, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.WRAP_CONTENT,
            LinearLayout.LayoutParams.WRAP_CONTENT
        ).apply { topMargin = (8 * dp).toInt() })

        setContentView(root)
    }
}
