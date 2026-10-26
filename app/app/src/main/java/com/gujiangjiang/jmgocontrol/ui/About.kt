package com.gujiangjiang.jmgocontrol.ui

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import com.gujiangjiang.jmgocontrol.BuildConfig
import com.gujiangjiang.jmgocontrol.R

object About {

    private const val REPO_URL = "https://github.com/gujiangjiang/JMGO_M6"

    fun show(activity: Activity) {
        AlertDialog.Builder(activity)
            .setTitle(R.string.about_title)
            .setMessage(
                activity.getString(
                    R.string.about_msg,
                    BuildConfig.VERSION_NAME,
                    BuildConfig.VERSION_CODE
                )
            )
            .setPositiveButton(R.string.about_repo) { _, _ -> openRepo(activity) }
            .setNegativeButton(R.string.dialog_ok, null)
            .show()
    }

    fun openRepo(context: Context) {
        try {
            context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(REPO_URL)))
        } catch (t: Throwable) {
            Toast.makeText(context, R.string.no_browser, Toast.LENGTH_SHORT).show()
        }
    }
}
