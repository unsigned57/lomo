package com.lomo.app.util

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.widget.Toast
import com.lomo.app.R

/**
 * Utility object for sharing and copying memo content.
 */
object ShareUtils {
    /**
     * Share memo content via Android share sheet.
     */
    fun shareMemoText(
        context: Context,
        content: String,
        title: String? = null,
    ) {
        val sendIntent =
            Intent().apply {
                action = Intent.ACTION_SEND
                putExtra(Intent.EXTRA_TEXT, content)
                type = "text/plain"
                title?.let { putExtra(Intent.EXTRA_TITLE, it) }
            }
        val shareIntent = Intent.createChooser(sendIntent, null)
        context.startActivity(shareIntent)
    }

    /**
     * Copy content to clipboard and show a toast confirmation.
     */
    fun copyToClipboard(
        context: Context,
        content: String,
        showToast: Boolean = true,
    ) {
        val clipboardManager = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        val clip = ClipData.newPlainText("Lomo Memo", content)
        clipboardManager.setPrimaryClip(clip)

        if (showToast) {
            Toast
                .makeText(
                    context,
                    context.getString(R.string.copied_to_clipboard),
                    Toast.LENGTH_SHORT,
                ).show()
        }
    }

    /**
     * Share memo as markdown file (for future enhancement).
     */
    fun shareMemoAsMarkdown(
        context: Context,
        content: String,
        fileName: String,
    ) {
        // TODO: Create temp file and share via FileProvider
        // For now, just share as text
        shareMemoText(context, content, fileName)
    }
}
