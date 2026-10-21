package com.v2ray.ang.service

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.drawable.Icon
import android.os.Build
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import androidx.core.content.ContextCompat
import com.v2ray.ang.AppConfig
import com.v2ray.ang.R
import com.v2ray.ang.core.CoreServiceManager
import com.v2ray.ang.core.LauncherManager
import com.v2ray.ang.handler.AppLocaleManager
import com.v2ray.ang.helper.MessageHelper
import com.v2ray.ang.ui.chapar.ChaparActivity
import com.v2ray.ang.util.LogUtil
import com.v2ray.ang.util.Utils
import java.lang.ref.SoftReference

class QSTileService : TileService() {

    override fun attachBaseContext(newBase: Context?) {
        super.attachBaseContext(newBase?.let(AppLocaleManager::localizedContext))
    }

    /**
     * Sets the state of the tile.
     * @param state The state to set.
     */
    fun setState(state: Int) {
        qsTile?.icon = Icon.createWithResource(applicationContext, R.drawable.ic_stat_name)
        if (state == Tile.STATE_INACTIVE) {
            qsTile?.state = Tile.STATE_INACTIVE
            qsTile?.label = getString(R.string.app_name)
        } else if (state == Tile.STATE_ACTIVE) {
            qsTile?.state = Tile.STATE_ACTIVE
            qsTile?.label = CoreServiceManager.getRunningServerName()
        }

        qsTile?.updateTile()
    }

    /**
     * Refer to the official documentation for [registerReceiver](https://developer.android.com/reference/androidx/core/content/ContextCompat#registerReceiver(android.content.Context,android.content.BroadcastReceiver,android.content.IntentFilter,int):
     * `registerReceiver(Context, BroadcastReceiver, IntentFilter, int)`.
     */
    override fun onStartListening() {
        super.onStartListening()

        if (CoreServiceManager.isRunning()) {
            setState(Tile.STATE_ACTIVE)
        } else {
            setState(Tile.STATE_INACTIVE)
        }
        mMsgReceive = ReceiveMessageHandler(this)
        val mFilter = IntentFilter(AppConfig.BROADCAST_ACTION_ACTIVITY)
        ContextCompat.registerReceiver(applicationContext, mMsgReceive, mFilter, Utils.receiverFlags())
        MessageHelper.sendMsg2Service(this, AppConfig.MSG_REGISTER_CLIENT, "")
    }

    /**
     * Called when the tile stops listening.
     */
    override fun onStopListening() {
        super.onStopListening()

        try {
            applicationContext.unregisterReceiver(mMsgReceive)
            mMsgReceive = null
        } catch (e: Exception) {
            LogUtil.e(AppConfig.TAG, "Failed to unregister receiver", e)
        }

    }

    /**
     * Called when the tile is clicked.
     */
    override fun onClick() {
        super.onClick()
        when (qsTile.state) {
            Tile.STATE_INACTIVE -> openChapar()

            Tile.STATE_ACTIVE -> {
                LauncherManager.stopService(this)
            }
        }
    }

    /**
     * Chapar (D-042): the tile never starts the VPN by itself. A start needs a fresh key from Connect,
     * which only the Chapar screen does, so a tap on the inactive tile opens that screen.
     */
    private fun openChapar() {
        val intent = Intent(this, ChaparActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startActivityAndCollapse(PendingIntent.getActivity(this, 0, intent, PendingIntent.FLAG_IMMUTABLE))
        } else {
            startActivityAndCollapseLegacy(intent)
        }
    }

    // The Intent overload is the only way to start an activity from a tile below Android 14 (API 34),
    // where the PendingIntent overload was added. Remove this function when minSdk reaches 34.
    @Suppress("DEPRECATION")
    private fun startActivityAndCollapseLegacy(intent: Intent) = startActivityAndCollapse(intent)

    private var mMsgReceive: BroadcastReceiver? = null

    private class ReceiveMessageHandler(context: QSTileService) : BroadcastReceiver() {
        var mReference: SoftReference<QSTileService> = SoftReference(context)
        override fun onReceive(ctx: Context?, intent: Intent?) {
            val context = mReference.get()
            when (intent?.getIntExtra("key", 0)) {
                AppConfig.MSG_STATE_RUNNING -> {
                    context?.setState(Tile.STATE_ACTIVE)
                }

                AppConfig.MSG_STATE_NOT_RUNNING -> {
                    context?.setState(Tile.STATE_INACTIVE)
                }

                AppConfig.MSG_STATE_START_SUCCESS -> {
                    context?.setState(Tile.STATE_ACTIVE)
                }

                AppConfig.MSG_STATE_START_FAILURE -> {
                    context?.setState(Tile.STATE_INACTIVE)
                }

                AppConfig.MSG_STATE_STOP_SUCCESS -> {
                    context?.setState(Tile.STATE_INACTIVE)
                }
            }
        }
    }
}
