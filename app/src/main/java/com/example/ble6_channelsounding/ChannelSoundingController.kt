package com.example.ble6_channelsounding

import android.Manifest
import android.bluetooth.BluetoothAdapter
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.CancellationSignal
import android.util.Log
import android.ranging.RangingData
import android.ranging.RangingDevice
import android.ranging.RangingManager
import android.ranging.RangingPreference
import android.ranging.RangingSession
import android.ranging.SessionConfig
import android.ranging.ble.cs.BleCsRangingCapabilities
import android.ranging.ble.cs.BleCsRangingParams
import android.ranging.raw.RawInitiatorRangingConfig
import android.ranging.raw.RawRangingDevice
import android.ranging.raw.RawResponderRangingConfig
import androidx.annotation.RequiresApi
import androidx.core.content.ContextCompat
import java.util.Locale
import java.util.UUID

@RequiresApi(Build.VERSION_CODES.BAKLAVA)
class ChannelSoundingController(
    private val context: Context,
    private val listener: Listener
) {
    interface Listener {
        fun onCapability(supported: Boolean, detail: String)
        fun onSessionOpened(role: Role)
        fun onRangingStarted(role: Role)
        fun onDistance(rawMeters: Double, smoothedMeters: Double, sampleCount: Int, rssi: Int?, timestampEpochMs: Long)
        fun onRangingStopped(role: Role)
        fun onError(message: String)
        fun onClosed(reason: Int)
    }

    enum class Role { INITIATOR, REFLECTOR }

    private val rangingManager: RangingManager? =
        context.getSystemService(RangingManager::class.java)

    private var session: RangingSession? = null
    private var sessionCancellation: CancellationSignal? = null
    private var capabilityCallback: RangingManager.RangingCapabilitiesCallback? = null

    private var activeRole: Role? = null
    private var starting = false
    private var generation = 0L
    private val lifecycle = CsSessionLifecycle()
    val isClosing: Boolean get() = lifecycle.closing

    private val recentDistances = ArrayDeque<Double>()
    private var totalSamples = 0

    fun checkCapability() {
        if (!hasRangingPermission()) {
            listener.onCapability(false, "RANGING permission 없음")
            return
        }

        val manager = rangingManager ?: run {
            listener.onCapability(false, "RangingManager 없음")
            return
        }

        unregisterCapabilityCallback()

        val callback = RangingManager.RangingCapabilitiesCallback { capabilities ->
            diagnostic("capabilities availability=${capabilities.technologyAvailability} cs=${capabilities.csCapabilities}")
            val cs = capabilities.csCapabilities
            val supported = cs != null

            val detail = if (cs == null) {
                "BLE Channel Sounding 미지원"
            } else {
                "BLE CS 지원 / security=${cs.supportedSecurityLevels}"
            }

            listener.onCapability(supported, detail)
            unregisterCapabilityCallback()
        }

        capabilityCallback = callback
        manager.registerCapabilitiesCallback(context.mainExecutor, callback)
    }

    fun startInitiator(
        peerAddress: String,
        securityLevel: Int = BleCsRangingCapabilities.CS_SECURITY_LEVEL_ONE,
        updateRate: Int = RawRangingDevice.UPDATE_RATE_NORMAL,
        preserveSampleCount: Boolean = false
    ) {
        start(Role.INITIATOR, peerAddress, securityLevel, updateRate, preserveSampleCount)
    }

    fun startReflector(
        peerAddress: String,
        securityLevel: Int = BleCsRangingCapabilities.CS_SECURITY_LEVEL_ONE,
        updateRate: Int = RawRangingDevice.UPDATE_RATE_NORMAL,
        preserveSampleCount: Boolean = false
    ) {
        start(Role.REFLECTOR, peerAddress, securityLevel, updateRate, preserveSampleCount)
    }

    private fun start(
        role: Role,
        peerAddress: String,
        securityLevel: Int,
        updateRate: Int,
        preserveSampleCount: Boolean
    ) {
        diagnostic("startRequested role=$role peer=$peerAddress security=$securityLevel updateRate=$updateRate " +
            "starting=$starting hasSession=${session != null}")
        if (starting || session != null) {
            // Manual restart: wait for the old session's actual onClosed callback.
            stop { start(role, peerAddress, securityLevel, updateRate, preserveSampleCount) }
            return
        }

        if (!hasRangingPermission()) {
            listener.onError("RANGING 권한이 없습니다.")
            return
        }

        if (!BluetoothAdapter.checkBluetoothAddress(peerAddress)) {
            listener.onError("잘못된 peer Bluetooth 주소: $peerAddress")
            return
        }

        val manager = rangingManager ?: run {
            listener.onError("RangingManager를 사용할 수 없습니다.")
            return
        }

        starting = true
        val token = ++generation
        activeRole = role
        if (!preserveSampleCount) {
            recentDistances.clear()
            totalSamples = 0
        }

        val normalizedAddress = peerAddress.uppercase(Locale.US)

        val rangingDevice = RangingDevice.Builder()
            .setUuid(UUID.nameUUIDFromBytes(normalizedAddress.toByteArray()))
            .build()

        val csParams = BleCsRangingParams.Builder(normalizedAddress)
            .setRangingUpdateRate(updateRate)
            .setSecurityLevel(securityLevel)
            .setLocationType(BleCsRangingParams.LOCATION_TYPE_INDOOR)
            .build()

        val rawDevice = RawRangingDevice.Builder()
            .setRangingDevice(rangingDevice)
            .setCsRangingParams(csParams)
            .build()

        val rangingConfig = when (role) {
            Role.INITIATOR -> RawInitiatorRangingConfig.Builder()
                .addRawRangingDevice(rawDevice)
                .build()

            Role.REFLECTOR -> RawResponderRangingConfig.Builder()
                .setRawRangingDevice(rawDevice)
                .build()
        }

        val sessionConfig = SessionConfig.Builder()
            .setRangingMeasurementsLimit(0)
            .build()

        val deviceRole = when (role) {
            Role.INITIATOR -> RangingPreference.DEVICE_ROLE_INITIATOR
            Role.REFLECTOR -> RangingPreference.DEVICE_ROLE_RESPONDER
        }

        val preference = RangingPreference.Builder(deviceRole, rangingConfig)
            .setSessionConfig(sessionConfig)
            .build()

        unregisterCapabilityCallback()

        val callback = RangingManager.RangingCapabilitiesCallback { capabilities ->
            diagnostic("startCapabilities token=$token availability=${capabilities.technologyAvailability} " +
                "cs=${capabilities.csCapabilities}")
            if (generation != token) return@RangingCapabilitiesCallback
            val cs = capabilities.csCapabilities

            if (cs == null) {
                fail("이 휴대폰은 BLE Channel Sounding을 지원하지 않습니다.")
                return@RangingCapabilitiesCallback
            }

            if (!cs.supportedSecurityLevels.contains(securityLevel)) {
                fail("요청한 CS Security Level($securityLevel)을 지원하지 않습니다.")
                return@RangingCapabilitiesCallback
            }

            try {
                diagnostic("createRangingSession token=$token peer=$normalizedAddress preference=$preference")
                val newSession = manager.createRangingSession(
                    context.mainExecutor,
                    sessionCallback(token)
                ) ?: run {
                    fail("RangingSession 생성 실패")
                    return@RangingCapabilitiesCallback
                }

                session = newSession

                /*
                 * addDeviceToRangingSession()은 호출하지 않습니다.
                 * RangingPreference에 포함된 raw config로 바로 시작합니다.
                 */
                sessionCancellation = newSession.start(preference)
                diagnostic("sessionStartReturned token=$token")

                unregisterCapabilityCallback()
            } catch (e: Exception) {
                Log.e("PhoneCS.CS", "sessionStartException token=$token role=$role peer=$normalizedAddress", e)
                fail(
                    "CS 세션 시작 실패: " +
                            "${e.javaClass.simpleName}: ${e.message ?: "원인 정보 없음"}"
                )
            }
        }

        capabilityCallback = callback
        manager.registerCapabilitiesCallback(context.mainExecutor, callback)
    }

    private fun sessionCallback(token: Long) = object : RangingSession.Callback {
        override fun onOpened() {
            diagnostic("onOpened token=$token")
            if (generation != token) return
            starting = false
            lifecycle.open = true
            if (lifecycle.closing) {
                session?.close()
                return
            }
            activeRole?.let(listener::onSessionOpened)
        }

        override fun onOpenFailed(reason: Int) {
            diagnostic("onOpenFailed token=$token reason=$reason name=${reasonToText(reason)}")
            if (generation != token) return
            val restart = lifecycle.finish()
            cleanup()
            if (restart != null) restart()
            else listener.onError("RangingSession open 실패: ${reasonToText(reason)}(reason=$reason)")
        }

        override fun onStarted(peer: RangingDevice, technology: Int) {
            diagnostic("onStarted token=$token peer=$peer technology=$technology")
            if (generation != token || lifecycle.closing) return
            activeRole?.let(listener::onRangingStarted)
        }

        override fun onResults(peer: RangingDevice, data: RangingData) {
            diagnostic("onResults token=$token peer=$peer data=$data")
            if (generation != token || lifecycle.closing) return
            /* Android 공개 API의 BLE CS 거리 결과는 Initiator에서 처리합니다. */
            if (activeRole != Role.INITIATOR) return

            val raw = data.distance?.measurement?.toDouble() ?: return

            totalSamples += 1
            recentDistances.addLast(raw)

            while (recentDistances.size > 5) {
                recentDistances.removeFirst()
            }

            val smoothed = recentDistances.average()
            // API 36 getTimestampMillis() is annotated @CurrentTimeMillisLong (Unix epoch).
            listener.onDistance(raw, smoothed, totalSamples,
                if (data.hasRssi() && data.rssi != 127) data.rssi else null, data.timestampMillis)
        }

        override fun onStopped(peer: RangingDevice, technology: Int) {
            diagnostic("onStopped token=$token peer=$peer technology=$technology requestedClose=${lifecycle.closing}")
            if (generation != token) return
            val requestedClose = lifecycle.stopped()
            recentDistances.clear()
            if (requestedClose) session?.close()
            else activeRole?.let(listener::onRangingStopped)
        }

        override fun onClosed(reason: Int) {
            diagnostic("onClosed token=$token reason=$reason name=${reasonToText(reason)} requestedClose=${lifecycle.closing}")
            if (generation != token) return
            val restart = lifecycle.finish()
            cleanup()
            if (restart != null) restart() else listener.onClosed(reason)
        }

    }

    fun stop(onClosed: (() -> Unit)? = null) {
        diagnostic("stopRequested hasSession=${session != null} closing=${lifecycle.closing}")
        val current = session
        if (current == null) {
            // Cancel a capability request before it can create a session.
            generation++
            cleanup()
            onClosed?.invoke()
            return
        }
        val action = lifecycle.requestClose(onClosed)
        if (action == CsSessionLifecycle.CloseAction.NONE) return
        try {
            if (action == CsSessionLifecycle.CloseAction.STOP) current.stop() else current.close()
        } catch (e: Exception) {
            Log.e("PhoneCS.CS", "sessionStopException generation=$generation", e)
            // Keep the handle: teardown is unconfirmed and a replacement must not start.
            lifecycle.restart = null
            lifecycle.closing = false
            listener.onError("CS 세션 종료 요청 실패: ${e.message}")
        }
    }

    private fun fail(message: String) {
        diagnostic("failure message=$message")
        stop()
        listener.onError(message)
    }

    private fun unregisterCapabilityCallback() {
        try {
            capabilityCallback?.let { callback ->
                rangingManager?.unregisterCapabilitiesCallback(callback)
            }
        } catch (_: Exception) {
        }

        capabilityCallback = null
    }

    private fun cleanup() {
        generation++ // Ignore callbacks from a session after its actual terminal event.
        starting = false
        lifecycle.reset()
        unregisterCapabilityCallback()
        sessionCancellation = null
        session = null
        activeRole = null
        recentDistances.clear()
        // Keep totalSamples for preserveSampleCount; start(preserve=false) resets it.
    }

    companion object {
        fun reasonToText(reason: Int): String = when (reason) {
            RangingSession.Callback.REASON_UNKNOWN -> "UNKNOWN(0)"
            RangingSession.Callback.REASON_LOCAL_REQUEST -> "LOCAL_REQUEST(1)"
            RangingSession.Callback.REASON_REMOTE_REQUEST -> "REMOTE_REQUEST(2)"
            RangingSession.Callback.REASON_UNSUPPORTED -> "UNSUPPORTED(3)"
            RangingSession.Callback.REASON_SYSTEM_POLICY -> "SYSTEM_POLICY(4)"
            RangingSession.Callback.REASON_NO_PEERS_FOUND -> "NO_PEERS_FOUND(5)"
            else -> "UNDEFINED($reason)"
        }
    }

    private fun diagnostic(message: String) {
        Log.i("PhoneCS.CS", "generation=$generation role=$activeRole $message")
    }

    private fun hasRangingPermission(): Boolean =
        ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.RANGING
        ) == PackageManager.PERMISSION_GRANTED
}
