package com.v2ray.ang.ui.shortcut

import android.content.Intent
import android.os.Bundle
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import com.v2ray.ang.core.CoreServiceManager
import com.v2ray.ang.core.LauncherManager
import com.v2ray.ang.ui.base.BaseComponentActivity
import com.v2ray.ang.ui.chapar.ChaparActivity

/**
 * Chapar (D-042): stops a running connection; when stopped it opens the Chapar screen instead of
 * starting, because a start needs a fresh key from Connect (see [ScStartActivity]).
 */
class ScSwitchActivity : BaseComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
    }

    @Composable
    override fun ScreenContent() {
        LaunchedEffect(Unit) {
            if (CoreServiceManager.isRunning()) {
                moveTaskToBack(true)
                LauncherManager.stopService(this@ScSwitchActivity)
            } else {
                startActivity(Intent(this@ScSwitchActivity, ChaparActivity::class.java))
            }
            finish()
        }
    }
}
