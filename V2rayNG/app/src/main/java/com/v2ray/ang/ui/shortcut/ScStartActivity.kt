package com.v2ray.ang.ui.shortcut

import android.content.Intent
import android.os.Bundle
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import com.v2ray.ang.ui.base.BaseComponentActivity
import com.v2ray.ang.ui.chapar.ChaparActivity

/**
 * Chapar (D-042): never starts the core by itself. A start needs a fresh key from Connect, which
 * only the Chapar screen does, so this opens that screen and lets the user tap Connect.
 */
class ScStartActivity : BaseComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
    }

    @Composable
    override fun ScreenContent() {
        LaunchedEffect(Unit) {
            startActivity(Intent(this@ScStartActivity, ChaparActivity::class.java))
            finish()
        }
    }
}
