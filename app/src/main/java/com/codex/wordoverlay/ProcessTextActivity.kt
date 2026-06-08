package com.codex.wordoverlay

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.provider.Settings
import android.util.Log
import android.widget.Toast
import androidx.core.net.toUri

class ProcessTextActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        if (!AppSettings(this).isTranslationEnabled()) {
            Toast.makeText(this, "拷贝即译已暂停", Toast.LENGTH_SHORT).show()
            finish()
            return
        }

        val query = WordExtractor.normalize(intent.getCharSequenceExtra(Intent.EXTRA_PROCESS_TEXT))
        if (query == null) {
            Toast.makeText(this, R.string.process_text_invalid, Toast.LENGTH_SHORT).show()
            finish()
            return
        }

        if (!Settings.canDrawOverlays(this)) {
            Toast.makeText(this, R.string.overlay_permission_required, Toast.LENGTH_SHORT).show()
            startActivity(
                Intent(
                    Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                    "package:$packageName".toUri()
                )
            )
            finish()
            return
        }

        Log.d(TAG, "Process Text handoff requested for query=$query")
        startService(ProcessTextLookupService.createIntent(this, query))
        finish()
    }

    companion object {
        private const val TAG = "ProcessText"
    }
}
