package com.v2ray.ang.ui.chapar

import com.v2ray.ang.AngApplication
import com.v2ray.ang.AppConfig
import com.v2ray.ang.core.LauncherManager
import com.v2ray.ang.extension.toastError
import com.v2ray.ang.extension.toastSuccess
import com.v2ray.ang.handler.AppLocaleManager
import com.v2ray.ang.helper.MessageHelper
import com.v2ray.ang.ui.main.MainRepository
import com.v2ray.ang.ui.main.MainServiceEvent
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.merge
import kotlinx.coroutines.flow.receiveAsFlow

/**
 * What [ChaparViewModel] needs from Android and the VPN daemon. It exists so the ViewModel's state machine
 * runs as a JVM test, and so upstream changes to the 30-member MainDataSource do not reach Chapar's screen.
 */
interface ChaparPlatform {
    /** Running, started, failed, and stopped, as the daemon reports them. */
    val serviceEvents: Flow<MainServiceEvent>

    /** Geo files for routing: upstream's start-up work. Blocking. */
    fun initAssets()

    /** Asks the daemon for its state. A daemon that is not there is reported as not running. */
    fun queryServiceState()

    fun stopVpn()

    /** Switches the running core to the selected profile without closing the VPN interface. */
    fun reloadVpn()

    /** Stops and starts the running VPN, which builds a new VPN interface; [onNotRunning] if there was none. */
    fun restartVpn(onNotRunning: () -> Unit)

    fun showError(resId: Int)
    fun showSuccess(resId: Int)

    /** Stops listening to the daemon. */
    fun close()
}

/** [ChaparPlatform] on upstream's MainRepository (service events), LauncherManager, and toasts. */
class AndroidChaparPlatform(private val app: AngApplication) : ChaparPlatform {

    private val repository = MainRepository(app)

    /** Answers that come from this process, not from the daemon (it may not exist). */
    private val local = Channel<MainServiceEvent>(Channel.UNLIMITED)

    override val serviceEvents: Flow<MainServiceEvent> = merge(repository.mainServiceEvent, local.receiveAsFlow())

    override fun initAssets() = repository.initAssets()

    override fun queryServiceState() {
        // An ordered broadcast tells whether a daemon received it; a plain one (MainRepository's own query when
        // it starts) gets no answer at all when the daemon died, and the screen would keep showing "Connected".
        MessageHelper.sendMsg2ServiceForResult(app, AppConfig.MSG_REGISTER_CLIENT, "") { handled ->
            if (!handled) local.trySend(MainServiceEvent.StateNotRunning)
        }
    }

    override fun stopVpn() = LauncherManager.stopService(app)

    override fun reloadVpn() = LauncherManager.reloadService(app)

    override fun restartVpn(onNotRunning: () -> Unit) = LauncherManager.restartServiceOrStart(app, onNotRunning)

    override fun showError(resId: Int) = AppLocaleManager.localizedContext(app).toastError(resId)

    override fun showSuccess(resId: Int) = AppLocaleManager.localizedContext(app).toastSuccess(resId)

    override fun close() {
        repository.close()
        local.close()
    }
}
