package com.gujiangjiang.jmgocontrol

import android.content.Intent
import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import com.gujiangjiang.jmgocontrol.core.AppPrefs
import com.gujiangjiang.jmgocontrol.databinding.ActivityModeSelectBinding

class ModeSelectActivity : AppCompatActivity() {

    private lateinit var b: ActivityModeSelectBinding

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        b = ActivityModeSelectBinding.inflate(layoutInflater)
        setContentView(b.root)

        b.cardLocal.setOnClickListener { choose(AppPrefs.MODE_LOCAL) }
        b.cardRemote.setOnClickListener { choose(AppPrefs.MODE_REMOTE) }
    }

    private fun choose(mode: String) {
        AppPrefs(this).mode = mode
        startActivity(Intent(this, MainActivity::class.java))
        finish()
    }
}
