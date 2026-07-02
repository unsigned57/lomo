package com.lomo.app.widget

import android.app.PendingIntent
import android.content.Intent
import android.service.quicksettings.TileService
import androidx.core.service.quicksettings.PendingIntentActivityWrapper
import androidx.core.service.quicksettings.TileServiceCompat
import com.lomo.app.MainActivity

class NewMemoTileService : TileService() {
    override fun onClick() {
        super.onClick()
        startMainActivityAndCollapse(
            requestCode = NEW_MEMO_REQUEST_CODE,
            intent =
                Intent(this, MainActivity::class.java).apply {
                    action = MainActivity.ACTION_NEW_MEMO
                    flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
                },
        )
    }

    private fun startMainActivityAndCollapse(
        requestCode: Int,
        intent: Intent,
    ) {
        val wrapper =
            PendingIntentActivityWrapper(
                this,
                requestCode,
                intent,
                PendingIntent.FLAG_UPDATE_CURRENT,
                false,
            )
        TileServiceCompat.startActivityAndCollapse(this, wrapper)
    }

    private companion object {
        const val NEW_MEMO_REQUEST_CODE = 51_001
    }
}
