package com.example.ble6_channelsounding

import android.Manifest
import android.bluetooth.BluetoothAdapter
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.CancellationSignal
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
        if (starting || session != null) {
            listener.onError("이미 CS 세션이 시작 중이거나 실행 중입니다.")
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
            .setRangingMeasurementsLimit(1000)
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

                unregisterCapabilityCallback()
            } catch (e: Exception) {
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
            if (generation != token) return
            starting = false
            activeRole?.let(listener::onSessionOpened)
        }

        override fun onOpenFailed(reason: Int) {
            if (generation != token) return
            starting = false
            fail(
                "RangingSession open 실패: " +
                        "${reasonToText(reason)}(reason=$reason)"
            )
        }

        override fun onStarted(peer: RangingDevice, technology: Int) {
            if (generation != token) return
            activeRole?.let(listener::onRangingStarted)
        }

        override fun onResults(peer: RangingDevice, data: RangingData) {
            if (generation != token) return
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
                if (data.hasRssi()) data.rssi else null, data.timestampMillis)
        }

        override fun onStopped(peer: RangingDevice, technology: Int) {
            if (generation != token) return
            activeRole?.let(listener::onRangingStopped)
        }

        override fun onClosed(reason: Int) {
            if (generation != token) return
            cleanup()
            listener.onClosed(reason)
        }
    }

    fun stop() {
        generation++ // Ignore late callbacks from a stopped session after a role change/restart.
        starting = false

        try {
            session?.stop()
        } catch (_: Exception) {
        }

        try {
            session?.close()
        } catch (_: Exception) {
        }

        cleanup()
    }

    private fun fail(message: String) {
        listener.onError(message)
        stop()
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
        unregisterCapabilityCallback()
        sessionCancellation = null
        session = null
        activeRole = null
        recentDistances.clear()
        totalSamples = 0
    }

    companion object {
        fun reasonToText(reason: Int): String = when (reason) {
            0 -> "UNKNOWN(0 - Nordic 보드 또는 BLE 링크 종료)"
            1 -> "LOCAL_REQUEST(1 - 앱/시스템 요청 종료)"
            2 -> "REMOTE_REQUEST(2 - Nordic 보드가 세션 종료)"
            3 -> "UNSUPPORTED(3 - 지원 안되는 CS 파라미터)"
            4 -> "SYSTEM_POLICY(4 - 시스템 정책 제한)"
            5 -> "NO_PEERS_FOUND(5 - 피어 응답 없음)"
            else -> "UNDEFINED($reason)"
        }
    }

    private fun hasRangingPermission(): Boolean =
        ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.RANGING
        ) == PackageManager.PERMISSION_GRANTED
}
