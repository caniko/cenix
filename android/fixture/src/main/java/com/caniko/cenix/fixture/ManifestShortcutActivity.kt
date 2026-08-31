package com.caniko.cenix.fixture

import android.app.Activity
import android.os.Bundle
import android.widget.TextView

class ManifestShortcutActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(TextView(this).apply {
            text = getString(R.string.manifest_destination)
            contentDescription = text
        })
    }
}
