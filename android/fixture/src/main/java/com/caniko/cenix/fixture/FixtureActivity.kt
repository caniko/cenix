package com.caniko.cenix.fixture

import android.os.Bundle
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity

class FixtureActivity : AppCompatActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val view = TextView(this)
        view.text = getString(R.string.app_name)
        view.contentDescription = getString(R.string.app_name)
        setContentView(view)
    }
}
