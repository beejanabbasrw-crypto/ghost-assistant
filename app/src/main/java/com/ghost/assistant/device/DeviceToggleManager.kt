package com.ghost.assistant.device

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.wifi.WifiManager
import android.os.Build
import android.provider.Settings
import androidx.core.content.ContextCompat

object DeviceToggleManager {

    enum class DeviceType {
        BLUETOOTH,
        WIFI,
        HOTSPOT,
        MOBILE_DATA
    }

    enum class ToggleAction {
        ON,
        OFF,
        TOGGLE
    }

    sealed class ToggleResult {
        data class DirectSuccess(val message: String) : ToggleResult()
        data class SettingsOpened(val message: String) : ToggleResult()
        data class Error(val message: String) : ToggleResult()
    }

    /**
     * Executes a device toggle command respecting Android platform security restrictions.
     */
    fun executeToggle(
        context: Context,
        device: DeviceType,
        action: ToggleAction,
        userName: String
    ): ToggleResult {
        return when (device) {
            DeviceType.BLUETOOTH -> handleBluetoothToggle(context, action, userName)
            DeviceType.WIFI -> handleWifiToggle(context, action, userName)
            DeviceType.HOTSPOT -> handleHotspotToggle(context, action, userName)
            DeviceType.MOBILE_DATA -> handleMobileDataToggle(context, action, userName)
        }
    }

    @SuppressLint("MissingPermission")
    private fun handleBluetoothToggle(
        context: Context,
        action: ToggleAction,
        userName: String
    ): ToggleResult {
        // Android 12+ (API 31+) requires BLUETOOTH_CONNECT runtime permission
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            if (ContextCompat.checkSelfPermission(context, Manifest.permission.BLUETOOTH_CONNECT)
                != PackageManager.PERMISSION_GRANTED
            ) {
                openBluetoothSettings(context)
                return ToggleResult.SettingsOpened(
                    "Bluetooth permission is required. Opening Bluetooth settings for you, $userName."
                )
            }
        }

        val bluetoothManager = context.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager
        val adapter = bluetoothManager?.adapter

        if (adapter == null) {
            return ToggleResult.Error("Bluetooth hardware is not available on this device, $userName.")
        }

        val currentState = adapter.isEnabled
        val targetState = when (action) {
            ToggleAction.ON -> true
            ToggleAction.OFF -> false
            ToggleAction.TOGGLE -> !currentState
        }

        if (currentState == targetState) {
            val stateText = if (currentState) "already turned on" else "already turned off"
            return ToggleResult.DirectSuccess("Bluetooth is $stateText, $userName.")
        }

        return try {
            // Note: On Android 13+ (API 33+), direct adapter.enable()/disable() calls by third-party apps
            // may be blocked or restricted by the OS. We handle graceful fallback.
            val success = if (targetState) {
                adapter.enable()
            } else {
                adapter.disable()
            }

            if (success) {
                val stateText = if (targetState) "on" else "off"
                ToggleResult.DirectSuccess("Bluetooth turned $stateText for you, $userName.")
            } else {
                // If programmatic toggle was blocked by OS, open Bluetooth settings
                openBluetoothSettings(context)
                val actionText = if (targetState) "switch it on" else "switch it off"
                ToggleResult.SettingsOpened(
                    "Direct Bluetooth toggle is restricted on this Android version. Opening Bluetooth settings so you can $actionText, $userName."
                )
            }
        } catch (e: SecurityException) {
            openBluetoothSettings(context)
            ToggleResult.SettingsOpened(
                "Bluetooth permission restricted. Opening Bluetooth settings for you, $userName."
            )
        } catch (e: Exception) {
            openBluetoothSettings(context)
            ToggleResult.SettingsOpened(
                "Opening Bluetooth settings for you, $userName."
            )
        }
    }

    private fun handleWifiToggle(
        context: Context,
        action: ToggleAction,
        userName: String
    ): ToggleResult {
        // On Android 10+ (API 29+), apps cannot silently toggle Wi-Fi (WifiManager.setWifiEnabled is deprecated & blocked).
        // The recommended platform approach is Settings.Panel.ACTION_INTERNET_CONNECTIVITY so user can tap it.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val opened = openInternetConnectivityPanel(context)
            val actionWord = when (action) {
                ToggleAction.ON -> "turn on"
                ToggleAction.OFF -> "turn off"
                ToggleAction.TOGGLE -> "toggle"
            }
            return if (opened) {
                ToggleResult.SettingsOpened(
                    "On Android 10 and above, apps cannot silently change Wi-Fi. Opening the Internet connectivity panel so you can $actionWord Wi-Fi, $userName."
                )
            } else {
                openWifiSettings(context)
                ToggleResult.SettingsOpened(
                    "Opening Wi-Fi settings so you can $actionWord Wi-Fi, $userName."
                )
            }
        } else {
            // Android 9 and lower: attempt direct toggle if permission granted
            @Suppress("DEPRECATION")
            val wifiManager = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager
            if (wifiManager != null) {
                val currentState = wifiManager.isWifiEnabled
                val targetState = when (action) {
                    ToggleAction.ON -> true
                    ToggleAction.OFF -> false
                    ToggleAction.TOGGLE -> !currentState
                }
                @Suppress("DEPRECATION")
                val success = wifiManager.setWifiEnabled(targetState)
                if (success) {
                    val stateText = if (targetState) "on" else "off"
                    return ToggleResult.DirectSuccess("Wi-Fi turned $stateText for you, $userName.")
                }
            }
            openWifiSettings(context)
            return ToggleResult.SettingsOpened("Opening Wi-Fi settings for you, $userName.")
        }
    }

    private fun handleHotspotToggle(
        context: Context,
        action: ToggleAction,
        userName: String
    ): ToggleResult {
        // Android does not offer a public API for third-party apps to directly enable/disable hotspot
        val actionText = when (action) {
            ToggleAction.ON -> "enable"
            ToggleAction.OFF -> "disable"
            ToggleAction.TOGGLE -> "configure"
        }
        openHotspotSettings(context)
        return ToggleResult.SettingsOpened(
            "Android does not allow third-party apps to toggle hotspot directly. Opening hotspot settings so you can $actionText it, $userName."
        )
    }

    private fun handleMobileDataToggle(
        context: Context,
        action: ToggleAction,
        userName: String
    ): ToggleResult {
        // Direct mobile data toggling requires system signature (MODIFY_PHONE_STATE) or root
        val actionText = when (action) {
            ToggleAction.ON -> "turn on"
            ToggleAction.OFF -> "turn off"
            ToggleAction.TOGGLE -> "adjust"
        }
        openMobileDataSettings(context)
        return ToggleResult.SettingsOpened(
            "Mobile data cannot be toggled directly without system-level permissions. Opening mobile network settings for you, $userName."
        )
    }

    private fun openBluetoothSettings(context: Context) {
        val intent = Intent(Settings.ACTION_BLUETOOTH_SETTINGS).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        try {
            context.startActivity(intent)
        } catch (_: Exception) {}
    }

    private fun openInternetConnectivityPanel(context: Context): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            try {
                val intent = Intent(Settings.Panel.ACTION_INTERNET_CONNECTIVITY).apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                context.startActivity(intent)
                true
            } catch (_: Exception) {
                false
            }
        } else false
    }

    private fun openWifiSettings(context: Context) {
        val intent = Intent(Settings.ACTION_WIFI_SETTINGS).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        try {
            context.startActivity(intent)
        } catch (_: Exception) {}
    }

    private fun openHotspotSettings(context: Context) {
        // Try Tether settings first, then wireless settings fallback
        val tetherIntent = Intent("android.settings.TETHER_SETTINGS").apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        if (tetherIntent.resolveActivity(context.packageManager) != null) {
            context.startActivity(tetherIntent)
            return
        }

        val altTetherIntent = Intent("com.android.settings.WIFI_TETHER_SETTINGS").apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        if (altTetherIntent.resolveActivity(context.packageManager) != null) {
            context.startActivity(altTetherIntent)
            return
        }

        val wirelessIntent = Intent(Settings.ACTION_WIRELESS_SETTINGS).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        try {
            context.startActivity(wirelessIntent)
        } catch (_: Exception) {}
    }

    private fun openMobileDataSettings(context: Context) {
        // Try Data Roaming / Network Operator / Wireless settings
        val dataIntent = Intent(Settings.ACTION_DATA_ROAMING_SETTINGS).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        if (dataIntent.resolveActivity(context.packageManager) != null) {
            context.startActivity(dataIntent)
            return
        }

        val netIntent = Intent(Settings.ACTION_NETWORK_OPERATOR_SETTINGS).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        if (netIntent.resolveActivity(context.packageManager) != null) {
            context.startActivity(netIntent)
            return
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            if (openInternetConnectivityPanel(context)) return
        }

        val wirelessIntent = Intent(Settings.ACTION_WIRELESS_SETTINGS).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        try {
            context.startActivity(wirelessIntent)
        } catch (_: Exception) {}
    }
}
