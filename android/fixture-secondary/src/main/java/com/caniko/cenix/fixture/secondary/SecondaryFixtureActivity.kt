package com.caniko.cenix.fixture.secondary

import android.app.Activity
import android.os.Bundle
import android.widget.TextView

class SecondaryFixtureActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(TextView(this).apply {
            text = getString(R.string.app_name)
            contentDescription = text
        })
    }
}
