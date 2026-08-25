package com.caniko.cenix.fixture

import android.app.Activity
import android.os.Bundle
import android.widget.TextView

class DynamicShortcutActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(TextView(this).apply {
            text = getString(R.string.dynamic_destination)
            contentDescription = text
        })
    }
}
