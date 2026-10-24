package com.v2ray.ang.core

import com.v2ray.ang.AppConfig
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

/** Chapar: the daemon's answer to the screen's state query (CoreServiceManager's MSG_REGISTER_CLIENT). */
class RegisterClientReplyTest {

    @Test
    fun aRunningOrReloadingCoreIsReportedRunning() {
        assertEquals(AppConfig.MSG_STATE_RUNNING, registerClientReply(isRunning = true, isReloading = false, isStarting = false))
        assertEquals(AppConfig.MSG_STATE_RUNNING, registerClientReply(isRunning = false, isReloading = true, isStarting = false))
    }

    @Test
    fun aStartInProgressIsNeverReportedAsFailed() {
        // The query that onResume sends right after the VPN permission dialog: the start's own result follows.
        assertNull(registerClientReply(isRunning = false, isReloading = false, isStarting = true))
    }

    @Test
    fun onlyAFinishedStartWithoutACoreIsReportedFailed() {
        assertEquals(AppConfig.MSG_STATE_START_FAILURE, registerClientReply(isRunning = false, isReloading = false, isStarting = false))
    }
}
