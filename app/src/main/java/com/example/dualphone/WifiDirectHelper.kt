package com.example.dualphone

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.net.wifi.p2p.WifiP2pManager
import android.os.Build
import android.os.Looper
import androidx.core.content.ContextCompat
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

data class WifiDirectState(
    val supported: Boolean = false,
    val groupActive: Boolean = false,
    val groupOwnerAddress: String = "",
    val networkName: String = "",
    val statusText: String = "Wi-Fi Direct idle",
    val needsPermission: Boolean = false
)

/**
 * Real Android Wi-Fi Direct (WifiP2pManager) helper allowing two phones to form a direct
 * high-bandwidth peer-to-peer group (typically 192.168.49.1) without needing an external router.
 * Uses Android 13+ NEARBY_WIFI_DEVICES (neverForLocation) for Play Policy compliance.
 */
class WifiDirectHelper(private val appContext: Context) {

    private val manager: WifiP2pManager? =
        runCatching { appContext.getSystemService(Context.WIFI_P2P_SERVICE) as? WifiP2pManager }.getOrNull()

    private var channel: WifiP2pManager.Channel? = null

    private val _state = MutableStateFlow(
        WifiDirectState(
            supported = manager != null,
            statusText = if (manager != null) "Wi-Fi Direct ready" else "Wi-Fi Direct unsupported on this device",
            needsPermission = !hasWifiDirectPermission(appContext)
        )
    )
    val state: StateFlow<WifiDirectState> = _state.asStateFlow()

    private fun ensureChannel(): WifiP2pManager.Channel? {
        val mgr = manager ?: return null
        if (channel == null) {
            channel = runCatching { mgr.initialize(appContext, Looper.getMainLooper(), null) }.getOrNull()
        }
        return channel
    }

    @SuppressLint("MissingPermission")
    fun startDirectGroup(onGroupAddressReady: (String) -> Unit = {}) {
        val mgr = manager
        if (mgr == null) {
            _state.update { it.copy(supported = false, statusText = "Wi-Fi Direct not supported on this hardware") }
            return
        }
        if (!hasWifiDirectPermission(appContext)) {
            _state.update {
                it.copy(
                    needsPermission = true,
                    statusText = "Grant Nearby Wi-Fi Devices permission to start Wi-Fi Direct."
                )
            }
            return
        }
        val ch = ensureChannel() ?: run {
            _state.update { it.copy(statusText = "Unable to initialize Wi-Fi Direct channel.") }
            return
        }
        _state.update { it.copy(needsPermission = false, statusText = "Starting Wi-Fi Direct group…") }

        runCatching {
            mgr.createGroup(ch, object : WifiP2pManager.ActionListener {
                override fun onSuccess() {
                    queryGroupAndConnectionInfo(mgr, ch, onGroupAddressReady)
                }

                override fun onFailure(reason: Int) {
                    // Group may already exist — inspect existing group info cleanly
                    queryGroupAndConnectionInfo(mgr, ch, onGroupAddressReady, fallbackReason = reason)
                }
            })
        }.onFailure { err ->
            _state.update { it.copy(statusText = err.localizedMessage ?: "Wi-Fi Direct unavailable.") }
        }
    }

    @SuppressLint("MissingPermission")
    private fun queryGroupAndConnectionInfo(
        mgr: WifiP2pManager,
        ch: WifiP2pManager.Channel,
        onGroupAddressReady: (String) -> Unit,
        fallbackReason: Int? = null
    ) {
        runCatching {
            mgr.requestConnectionInfo(ch) { info ->
                val ownerIp = info?.groupOwnerAddress?.hostAddress.orEmpty()
                if (ownerIp.isNotBlank()) {
                    _state.update {
                        it.copy(
                            groupActive = true,
                            groupOwnerAddress = ownerIp,
                            statusText = "Wi-Fi Direct active ($ownerIp)"
                        )
                    }
                    onGroupAddressReady(ownerIp)
                } else if (fallbackReason != null) {
                    val msg = when (fallbackReason) {
                        WifiP2pManager.P2P_UNSUPPORTED -> "Wi-Fi Direct is unsupported on this device."
                        WifiP2pManager.BUSY -> "Wi-Fi Direct is busy — ensure Wi-Fi is turned ON and retry."
                        else -> "Turn Wi-Fi ON to start Wi-Fi Direct (code $fallbackReason)."
                    }
                    _state.update { it.copy(groupActive = false, statusText = msg) }
                } else {
                    _state.update {
                        it.copy(
                            groupActive = true,
                            groupOwnerAddress = "192.168.49.1",
                            statusText = "Wi-Fi Direct group ready (192.168.49.1)"
                        )
                    }
                    onGroupAddressReady("192.168.49.1")
                }
            }
            mgr.requestGroupInfo(ch) { group ->
                if (group != null) {
                    _state.update {
                        it.copy(
                            groupActive = true,
                            networkName = group.networkName.orEmpty()
                        )
                    }
                }
            }
        }
    }

    @SuppressLint("MissingPermission")
    fun stopDirectGroup() {
        val mgr = manager ?: return
        val ch = channel ?: return
        runCatching {
            mgr.removeGroup(ch, object : WifiP2pManager.ActionListener {
                override fun onSuccess() {
                    _state.update {
                        it.copy(
                            groupActive = false,
                            groupOwnerAddress = "",
                            networkName = "",
                            statusText = "Wi-Fi Direct stopped"
                        )
                    }
                }

                override fun onFailure(reason: Int) {
                    _state.update { it.copy(groupActive = false, statusText = "Wi-Fi Direct idle") }
                }
            })
        }
    }

    companion object {
        fun requiredPermissionForSdk(sdkInt: Int = Build.VERSION.SDK_INT): String? {
            return if (sdkInt >= Build.VERSION_CODES.TIRAMISU) {
                Manifest.permission.NEARBY_WIFI_DEVICES
            } else {
                null
            }
        }

        fun hasWifiDirectPermission(context: Context): Boolean {
            return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                ContextCompat.checkSelfPermission(
                    context,
                    Manifest.permission.NEARBY_WIFI_DEVICES
                ) == PackageManager.PERMISSION_GRANTED
            } else {
                true
            }
        }
    }
}
