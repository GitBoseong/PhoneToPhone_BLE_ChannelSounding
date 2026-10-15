package com.example.ble6_channelsounding

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothGattServer
import android.bluetooth.BluetoothGattServerCallback
import android.bluetooth.BluetoothGattService
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.bluetooth.BluetoothStatusCodes
import android.bluetooth.le.AdvertisingSet
import android.bluetooth.le.AdvertisingSetCallback
import android.bluetooth.le.AdvertisingSetParameters
import android.bluetooth.le.AdvertiseData
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.ParcelUuid
import android.util.Log
import androidx.core.content.ContextCompat
import java.nio.charset.StandardCharsets
import java.util.UUID

class BlePeerCoordinator(
    private val context: Context,
    private val listener: Listener
) {
    companion object {
        const val MIN_ADVERTISING_TX_DBM = -21
        private val RANGING_SERVICE_UUID = ParcelUuid(UUID.fromString("0000185b-0000-1000-8000-00805f9b34fb"))
        // API 37 expands the public request range; actual applied power remains controller-owned.
        val maxAdvertisingTxDbm: Int
            get() = if (Build.VERSION.SDK_INT >= 37) AdvertisingSetParameters.TX_POWER_MAX_AVAILABLE else 1
    }
    interface Listener {
        fun onLog(message: String)
        fun onScanDevice(device: BluetoothDevice, name: String, rssi: Int, txPower: Int?, timestamp: Long)
        fun onAdvertisingState(busy: Boolean, appliedTxPower: Int?)
        fun onSelectedPeerConnected(address: String)
        fun onBondState(address: String, state: Int)
        fun onControlChannelReady(address: String)
        fun onStartReflectorRequested(initiatorAddress: String)
        fun onStopReflectorRequested()
        fun onReflectorReady(reflectorAddress: String)
        fun onReflectorError(message: String)

        /* Initiator가 GATT로 보낸 거리값을 Reflector에서 수신 */
        fun onRemoteDistance(
            rawMeters: Double,
            smoothedMeters: Double,
            sampleCount: Int
        )

        fun onBlePhy(state: BlePhyState)

        fun onConnectionRssi(address: String, rssi: Int?, timestamp: Long)

        fun onDisconnected()
    }

    private data class PendingWrite(
        val characteristic: BluetoothGattCharacteristic,
        val value: ByteArray,
        val description: String
    )

    private val bluetoothManager =
        context.getSystemService(BluetoothManager::class.java)

    private val adapter: BluetoothAdapter = bluetoothManager.adapter
    private val mainHandler = Handler(Looper.getMainLooper())

    private var selectedDevice: BluetoothDevice? = null
    @Volatile private var scanning = false

    /* Hardware Reflector (e.g. Nordic nRF54L15 DK) 여부 */
    var isHardwareReflector: Boolean = false
        private set

    val hasControlChannel: Boolean
        get() = controlCharacteristic != null

    val isGattConnected: Boolean
        get() = clientConnected

    private var clientConnected = false

    /* Initiator 쪽 GATT client */
    private var clientGatt: BluetoothGatt? = null
    private var requestedPhy: BlePhyChoice? = null
    private var phyReadyGatt: BluetoothGatt? = null
    private var actualPhy = BlePhyState()

    fun selectPhy(choice: BlePhyChoice) {
        mainHandler.post {
            requestedPhy = choice
            actualPhy = actualPhy.copy(requested = choice.csv)
            listener.onBlePhy(actualPhy)
            val gatt = phyReadyGatt
            if (gatt != null && clientGatt === gatt) requestPreferredPhy(gatt)
            else listener.onLog("PHY 선택: ${choice.label}; 기존 GATT 준비 후 요청")
        }
    }

    @SuppressLint("MissingPermission")
    private fun requestPreferredPhy(gatt: BluetoothGatt) {
        val choice = requestedPhy ?: return // Preserve platform defaults until explicitly selected.
        if (clientGatt !== gatt || !hasBlePermissions()) return
        if (choice == BlePhyChoice.LE_2M && !adapter.isLe2MPhySupported ||
            choice in listOf(BlePhyChoice.LE_CODED_S2, BlePhyChoice.LE_CODED_S8) && !adapter.isLeCodedPhySupported) {
            listener.onLog("PHY 요청 불가: 로컬 기기가 ${choice.label}를 지원하지 않습니다.")
            return
        }
        val mask = when (choice) {
            BlePhyChoice.LE_1M -> BluetoothDevice.PHY_LE_1M_MASK
            BlePhyChoice.LE_2M -> BluetoothDevice.PHY_LE_2M_MASK
            else -> BluetoothDevice.PHY_LE_CODED_MASK
        }
        val options = when (choice) {
            BlePhyChoice.LE_CODED_S2 -> BluetoothDevice.PHY_OPTION_S2
            BlePhyChoice.LE_CODED_S8 -> BluetoothDevice.PHY_OPTION_S8
            else -> BluetoothDevice.PHY_OPTION_NO_PREFERRED
        }
        try {
            gatt.setPreferredPhy(mask, mask, options)
            listener.onLog("PHY 요청: ${choice.label}; 실제 결과는 onPhyUpdate에서 확인")
        } catch (e: Exception) { listener.onLog("PHY 요청 실패: ${e.message}") }
    }

    private fun onPhyReady(gatt: BluetoothGatt) {
        mainHandler.post {
            if (clientGatt !== gatt) return@post
            phyReadyGatt = gatt
            requestPreferredPhy(gatt)
        }
    }

    private fun clearPhy() {
        phyReadyGatt = null
        actualPhy = BlePhyState(requested = requestedPhy?.csv)
        listener.onBlePhy(actualPhy)
    }

    private fun reportPhy(txPhy: Int, rxPhy: Int, status: Int) {
        if (status != BluetoothGatt.GATT_SUCCESS) {
            listener.onLog("PHY 결과 실패: status=$status; 기존 확인값 유지")
            return
        }
        actualPhy = BlePhyState(BlePhyState.label(txPhy), BlePhyState.label(rxPhy),
            requestedPhy?.csv, System.currentTimeMillis())
        listener.onBlePhy(actualPhy)
        listener.onLog("PHY 실제: TX=${actualPhy.tx} RX=${actualPhy.rx}; 요청=${actualPhy.requested}")
    }

    private var rssiGatt: BluetoothGatt? = null
    private var rssiRequestPending = false
    private val rssiPoll = object : Runnable {
        @SuppressLint("MissingPermission")
        override fun run() {
            val gatt = rssiGatt ?: return
            if (clientGatt !== gatt) return
            // Do not compete with pairing, discovery, or queued control/distance writes.
            val writing = synchronized(writeLock) { currentWrite != null || writeQueue.isNotEmpty() }
            if (!rssiRequestPending && !serviceDiscoveryRequested &&
                pendingDiscoveryRunnable == null && !writing &&
                gatt.device.bondState != BluetoothDevice.BOND_BONDING && hasBlePermissions()) {
                rssiRequestPending = try { gatt.readRemoteRssi() } catch (_: Exception) { false }
            }
            mainHandler.postDelayed(this, 1000L)
        }
    }

    private fun startConnectionRssi(gatt: BluetoothGatt) {
        mainHandler.post {
            if (clientGatt !== gatt) return@post
            stopConnectionRssi()
            rssiGatt = gatt
            mainHandler.postDelayed(rssiPoll, 1000L)
        }
    }

    private fun stopConnectionRssi() {
        mainHandler.removeCallbacks(rssiPoll)
        val old = rssiGatt
        rssiGatt = null
        rssiRequestPending = false
        old?.let { listener.onConnectionRssi(it.device.address, null, System.currentTimeMillis()) }
    }
    private var controlCharacteristic: BluetoothGattCharacteristic? = null
    private var statusCharacteristic: BluetoothGattCharacteristic? = null
    private var distanceCharacteristic: BluetoothGattCharacteristic? = null

    private var serviceDiscoveryRequested = false
    private var pendingDiscoveryRunnable: Runnable? = null

    /* GATT write는 한 번에 하나씩 실행해야 하므로 직렬화 큐 사용 */
    private val writeLock = Any()
    private val writeQueue = ArrayDeque<PendingWrite>()
    private var currentWrite: PendingWrite? = null

    /* Reflector 쪽 GATT server */
    private var gattServer: BluetoothGattServer? = null
    private var serverStatusCharacteristic: BluetoothGattCharacteristic? = null
    private var serverConnectedDevice: BluetoothDevice? = null
    private var notificationsEnabled = false
    private var advertising = false
    private var advertisingStarting = false
    private var requestedTxPower: Int? = null
    private var appliedTxPower: Int? = null
    private var advertiseCallback: AdvertisingSetCallback? = null
    private var advertisingService: BluetoothGattService? = null

    private var bondReceiverRegistered = false

    fun register() {
        if (bondReceiverRegistered) return

        val filter = IntentFilter(BluetoothDevice.ACTION_BOND_STATE_CHANGED)

        ContextCompat.registerReceiver(
            context,
            bondReceiver,
            filter,
            ContextCompat.RECEIVER_EXPORTED
        )

        bondReceiverRegistered = true
    }

    fun unregister() {
        if (!bondReceiverRegistered) return

        try {
            context.unregisterReceiver(bondReceiver)
        } catch (_: Exception) {
        }

        bondReceiverRegistered = false
    }

    fun selectDevice(device: BluetoothDevice) {
        selectedDevice = device
        listener.onLog("선택: ${device.address}")
    }

    @SuppressLint("MissingPermission")
    fun startScan() {
        if (!hasBlePermissions()) {
            listener.onReflectorError("BLE 권한이 없습니다.")
            return
        }

        stopScan()

        val settings = ScanSettings.Builder()
            .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
            // Silicon Labs CS Reflector demos use extended advertising.
            .setLegacy(!adapter.isLeExtendedAdvertisingSupported)
            .build()

        scanning = true
        adapter.bluetoothLeScanner?.startScan(
            emptyList(),
            settings,
            scanCallback
        ) ?: run {
            scanning = false
            listener.onReflectorError("BLE scanner를 사용할 수 없습니다.")
            return
        }

        listener.onLog("Reflector 스캔 시작 (Reflector / CS RFLCT / RAS / Phone CS)")
    }

    @SuppressLint("MissingPermission")
    fun stopScan() {
        scanning = false
        try {
            adapter.bluetoothLeScanner?.stopScan(scanCallback)
        } catch (_: Exception) {
        }
    }

    @SuppressLint("MissingPermission")
    fun connectAndPairSelected() {
        val device = selectedDevice ?: run {
            listener.onReflectorError("검색 목록에서 장치를 먼저 선택하세요.")
            return
        }

        if (!hasBlePermissions()) {
            listener.onReflectorError("BLE 권한이 없습니다.")
            return
        }

        // Keep the service-filtered ScanCallback alive through GATT and CS.
        closeClientGatt()

        serviceDiscoveryRequested = false
        listener.onLog("GATT 연결 요청: ${device.address}")

        clientGatt = device.connectGatt(
            context,
            false,
            clientGattCallback,
            BluetoothDevice.TRANSPORT_LE
        )
    }

    fun requestStartFromReflector() {
        val characteristic = controlCharacteristic ?: run {
            listener.onReflectorError("Control characteristic가 준비되지 않았습니다.")
            return
        }

        enqueueWrite(
            characteristic = characteristic,
            value = CsProtocol.START.toByteArray(StandardCharsets.UTF_8),
            description = "START"
        )

        listener.onLog("Reflector에 START 요청 예약")
    }

    fun requestStopFromReflector() {
        val characteristic = controlCharacteristic ?: return

        enqueueWrite(
            characteristic = characteristic,
            value = CsProtocol.STOP.toByteArray(StandardCharsets.UTF_8),
            description = "STOP"
        )
    }

    /*
     * Initiator가 RangingData.distance를 받은 뒤 호출합니다.
     * 아직 전송되지 않은 거리 패킷은 가장 최신 값 하나만 유지해 backlog를 막습니다.
     */
    fun sendDistanceToReflector(
        rawMeters: Double,
        smoothedMeters: Double,
        sampleCount: Int
    ) {
        val characteristic = distanceCharacteristic ?: return

        val packet = CsProtocol.encodeDistance(
            rawMeters = rawMeters,
            smoothedMeters = smoothedMeters,
            sampleCount = sampleCount
        )

        enqueueWrite(
            characteristic = characteristic,
            value = packet,
            description = "DISTANCE#$sampleCount",
            replacePendingDistance = true
        )
    }

    @SuppressLint("MissingPermission")
    fun startReflectorAdvertising(txPower: Int? = null) {
        if (advertising || advertisingStarting || serverConnectedDevice != null) return
        if (txPower != null && txPower !in MIN_ADVERTISING_TX_DBM..maxAdvertisingTxDbm) {
            listener.onReflectorError("Unsupported Advertising TX Power request: $txPower")
            return
        }
        if (!hasBlePermissions()) {
            listener.onReflectorError("BLE 권한이 없습니다.")
            return
        }

        closeServer()
        requestedTxPower = txPower
        advertisingStarting = true
        listener.onAdvertisingState(true, null)
        listener.onLog("TX_POWER mode=${if (txPower == null) "DEFAULT" else "CUSTOM"}")
        listener.onLog("TX_POWER requested=${txPower?.let { "$it dBm" } ?: "DEFAULT"}")

        val server = bluetoothManager.openGattServer(context, serverCallback)
            ?: run {
                listener.onReflectorError("GATT server 생성 실패")
                return
            }

        gattServer = server

        val service = BluetoothGattService(
            CsProtocol.SERVICE_UUID,
            BluetoothGattService.SERVICE_TYPE_PRIMARY
        )

        val control = BluetoothGattCharacteristic(
            CsProtocol.CONTROL_UUID,
            BluetoothGattCharacteristic.PROPERTY_WRITE,
            BluetoothGattCharacteristic.PERMISSION_WRITE_ENCRYPTED
        )

        val status = BluetoothGattCharacteristic(
            CsProtocol.STATUS_UUID,
            BluetoothGattCharacteristic.PROPERTY_NOTIFY or
                    BluetoothGattCharacteristic.PROPERTY_READ,
            BluetoothGattCharacteristic.PERMISSION_READ_ENCRYPTED
        )

        status.addDescriptor(
            BluetoothGattDescriptor(
                CsProtocol.CCCD_UUID,
                BluetoothGattDescriptor.PERMISSION_READ or
                        BluetoothGattDescriptor.PERMISSION_WRITE
            )
        )

        val distance = BluetoothGattCharacteristic(
            CsProtocol.DISTANCE_UUID,
            BluetoothGattCharacteristic.PROPERTY_WRITE,
            BluetoothGattCharacteristic.PERMISSION_WRITE_ENCRYPTED
        )

        service.addCharacteristic(control)
        service.addCharacteristic(status)
        service.addCharacteristic(distance)

        serverStatusCharacteristic = status

        advertisingService = service
        val addRequested = server.addService(service)
        if (!addRequested) {
            listener.onReflectorError("GATT server service 추가 요청 실패")
            closeServer()
            return
        }

        listener.onLog("GATT server service 추가 요청")
    }

    @SuppressLint("MissingPermission")
    private fun beginAdvertising() {
        val advertiser = adapter.bluetoothLeAdvertiser ?: run {
            listener.onReflectorError("BLE advertiser를 사용할 수 없습니다.")
            return
        }

        val builder = AdvertisingSetParameters.Builder()
            .setLegacyMode(true)
            .setConnectable(true)
            .setScannable(true)
            .setInterval(AdvertisingSetParameters.INTERVAL_LOW)
        // Default: leave the builder's platform default untouched; never invent applied dBm.
        requestedTxPower?.let(builder::setTxPowerLevel)
        val data = AdvertiseData.Builder()
            .addServiceUuid(ParcelUuid(CsProtocol.SERVICE_UUID))
            .setIncludeDeviceName(false)
            .setIncludeTxPowerLevel(true)
            .build()
        val callback = object : AdvertisingSetCallback() {
            override fun onAdvertisingSetStarted(set: AdvertisingSet?, txPower: Int, status: Int) {
                if (advertiseCallback !== this) return
                advertisingStarting = false
                if (status == ADVERTISE_SUCCESS && set != null) {
                    advertising = true
                    appliedTxPower = txPower
                    listener.onLog("Advertising started; TX_POWER applied=$txPower dBm")
                    listener.onAdvertisingState(true, txPower)
                } else {
                    advertiseCallback = null
                    advertising = false
                    listener.onAdvertisingState(false, null)
                    listener.onReflectorError("Advertising start failed: $status; TX_POWER applied=N/A")
                }
            }
            override fun onAdvertisingSetStopped(set: AdvertisingSet?) {
                if (advertiseCallback !== this) return
                advertiseCallback = null
                advertising = false
                advertisingStarting = false
                listener.onAdvertisingState(false, appliedTxPower)
            }
        }
        advertiseCallback = callback
        try {
            advertiser.startAdvertisingSet(builder.build(), data, null, null, null, callback)
            listener.onLog("Legacy connectable/scannable advertising requested")
        } catch (e: Exception) {
            stopAdvertisingOnly()
            listener.onReflectorError("Advertising failed: ${e.message}")
        }
    }

    @SuppressLint("MissingPermission")
    private fun stopAdvertisingOnly() {
        val callback = advertiseCallback
        if (callback != null || advertisingStarting) listener.onLog("Advertising stopped")
        advertiseCallback = null // Invalidates late start/stop callbacks.
        try { callback?.let { adapter.bluetoothLeAdvertiser?.stopAdvertisingSet(it) } }
        catch (_: Exception) { }
        advertising = false
        advertisingStarting = false
        listener.onAdvertisingState(false, appliedTxPower)
    }

    fun notifyReflectorReady() {
        notifyStatus(CsProtocol.READY)
    }

    fun notifyReflectorPreparing() {
        notifyStatus(CsProtocol.PREPARING)
    }

    fun notifyReflectorStopped() {
        notifyStatus(CsProtocol.STOPPED)
    }

    fun notifyReflectorFailure(message: String) {
        notifyStatus(CsProtocol.ERROR_PREFIX + message)
    }

    @SuppressLint("MissingPermission")
    private fun notifyStatus(value: String) {
        val server = gattServer ?: return
        val device = serverConnectedDevice ?: return
        val characteristic = serverStatusCharacteristic ?: return

        if (!notificationsEnabled) {
            listener.onLog("상태 알림 미전송(CCCD 미활성): $value")
            return
        }

        val bytes = value.toByteArray(StandardCharsets.UTF_8)

        if (Build.VERSION.SDK_INT >= 33) {
            val result = server.notifyCharacteristicChanged(
                device,
                characteristic,
                false,
                bytes
            )

            if (result != BluetoothStatusCodes.SUCCESS) {
                listener.onReflectorError("GATT notify 요청 실패: status=$result")
                return
            }
        } else {
            @Suppress("DEPRECATION")
            run {
                characteristic.value = bytes
                val requested = server.notifyCharacteristicChanged(
                    device,
                    characteristic,
                    false
                )

                if (!requested) {
                    listener.onReflectorError("GATT notify 요청 실패")
                    return
                }
            }
        }

        listener.onLog("GATT notify: $value")
    }

    @SuppressLint("MissingPermission")
    fun stopAll() {
        stopScan()
        requestStopFromReflector()
        closeClientGatt()
        closeServer()
    }

    @SuppressLint("MissingPermission")
    fun closeClientGattOnly() {
        if (clientGatt != null) {
            listener.onLog("Hardware Reflector CS 시작 전 GATT 연결 해제")
            closeClientGatt()
        }
    }

    @SuppressLint("MissingPermission")
    private fun closeClientGatt() {
        clearPhy()
        stopConnectionRssi()
        cancelPendingDiscovery()
        clearWriteQueue()

        isHardwareReflector = false
        val gatt = clientGatt

        clientConnected = false
        clientGatt = null
        controlCharacteristic = null
        statusCharacteristic = null
        distanceCharacteristic = null
        serviceDiscoveryRequested = false

        try {
            gatt?.disconnect()
        } catch (_: Exception) {
        }

        try {
            gatt?.close()
        } catch (_: Exception) {
        }
    }

    @SuppressLint("MissingPermission")
    private fun releaseClientGattFromCallback(gatt: BluetoothGatt) {
        cancelPendingDiscovery()
        clearWriteQueue()

        if (clientGatt === gatt) {
            clearPhy()
            stopConnectionRssi()
            isHardwareReflector = false
            clientConnected = false
            clientGatt = null
            controlCharacteristic = null
            statusCharacteristic = null
            distanceCharacteristic = null
            serviceDiscoveryRequested = false
        }

        try {
            gatt.close()
        } catch (_: Exception) {
        }
    }

    @SuppressLint("MissingPermission")
    private fun closeServer() {
        clearPhy()
        advertisingService = null
        appliedTxPower = null
        stopAdvertisingOnly()

        try {
            serverConnectedDevice?.let { device ->
                gattServer?.cancelConnection(device)
            }
        } catch (_: Exception) {
        }

        try {
            gattServer?.close()
        } catch (_: Exception) {
        }

        gattServer = null
        serverStatusCharacteristic = null
        serverConnectedDevice = null
        notificationsEnabled = false
    }

    private fun cancelPendingDiscovery() {
        pendingDiscoveryRunnable?.let(mainHandler::removeCallbacks)
        pendingDiscoveryRunnable = null
    }

    @SuppressLint("MissingPermission")
    private fun scheduleServiceDiscovery(
        gatt: BluetoothGatt,
        delayMillis: Long
    ) {
        if (serviceDiscoveryRequested) {
            listener.onLog("GATT 서비스 탐색이 이미 요청되었습니다.")
            return
        }

        cancelPendingDiscovery()

        val runnable = Runnable {
            pendingDiscoveryRunnable = null

            if (clientGatt !== gatt) return@Runnable

            serviceDiscoveryRequested = true

            val requested = try {
                gatt.discoverServices()
            } catch (e: Exception) {
                listener.onReflectorError(
                    "GATT 서비스 탐색 예외: " +
                            "${e.javaClass.simpleName}: ${e.message}"
                )
                false
            }

            listener.onLog("GATT 서비스 탐색 요청 결과: $requested")

            if (!requested) {
                serviceDiscoveryRequested = false
                listener.onReflectorError("GATT 서비스 탐색 요청 실패")
            }
        }

        pendingDiscoveryRunnable = runnable
        mainHandler.postDelayed(runnable, delayMillis)
    }

    private fun enqueueWrite(
        characteristic: BluetoothGattCharacteristic,
        value: ByteArray,
        description: String,
        replacePendingDistance: Boolean = false
    ) {
        synchronized(writeLock) {
            if (replacePendingDistance) {
                writeQueue.removeAll {
                    it.characteristic.uuid == CsProtocol.DISTANCE_UUID
                }
            }

            writeQueue.addLast(
                PendingWrite(
                    characteristic = characteristic,
                    value = value,
                    description = description
                )
            )
        }

        drainWriteQueue()
    }

    @SuppressLint("MissingPermission")
    private fun drainWriteQueue() {
        val gatt = clientGatt ?: run {
            clearWriteQueue()
            return
        }

        val item = synchronized(writeLock) {
            if (currentWrite != null || writeQueue.isEmpty()) {
                return
            }

            writeQueue.removeFirst().also { currentWrite = it }
        }

        val requested = try {
            if (Build.VERSION.SDK_INT >= 33) {
                gatt.writeCharacteristic(
                    item.characteristic,
                    item.value,
                    BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
                ) == BluetoothStatusCodes.SUCCESS
            } else {
                @Suppress("DEPRECATION")
                run {
                    item.characteristic.writeType =
                        BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
                    item.characteristic.value = item.value
                    gatt.writeCharacteristic(item.characteristic)
                }
            }
        } catch (e: Exception) {
            listener.onReflectorError(
                "GATT write 예외(${item.description}): " +
                        "${e.javaClass.simpleName}: ${e.message}"
            )
            false
        }

        if (!requested) {
            synchronized(writeLock) {
                currentWrite = null
            }

            listener.onReflectorError(
                "GATT write 요청이 즉시 거부됨: ${item.description}"
            )

            mainHandler.post { drainWriteQueue() }
        }
    }

    private fun clearWriteQueue() {
        synchronized(writeLock) {
            writeQueue.clear()
            currentWrite = null
        }
    }

    private val scanCallback = object : ScanCallback() {
        @SuppressLint("MissingPermission")
        override fun onScanResult(callbackType: Int, result: ScanResult) {
            if (!scanning) return
            val device = result.device
            val scanRecord = result.scanRecord
            val rawName = scanRecord?.deviceName ?: device.name

            val serviceUuids = scanRecord?.serviceUuids
            val isPhoneCs = serviceUuids?.contains(ParcelUuid(CsProtocol.SERVICE_UUID)) == true
            val isRas = serviceUuids?.contains(RANGING_SERVICE_UUID) == true
            val isReflectorName = rawName != null &&
                (rawName.contains("Reflector", ignoreCase = true) || rawName.equals("CS RFLCT", ignoreCase = true))
            val accepted = isReflectorName || isRas || isPhoneCs
            Log.d("PhoneCS.Scan", "address=${device.address} name=$rawName rssi=${result.rssi} " +
                "serviceUuids=$serviceUuids legacy=${result.isLegacy} accepted=$accepted")

            // Include standard RAS reflectors and the Silicon Labs demo's advertised name.
            if (!accepted) {
                return
            }

            val displayName = rawName ?: "Reflector"
            val name = if (isPhoneCs && !displayName.contains("[Phone CS]")) {
                "$displayName [Phone CS]"
            } else {
                displayName
            }

            // Legacy AD TX Power is in ScanRecord; extended reports may expose ScanResult.txPower.
            val reported = result.txPower.takeUnless { it == ScanResult.TX_POWER_NOT_PRESENT }
            val packetPower = scanRecord?.txPowerLevel?.takeUnless { it == Int.MIN_VALUE }
            val power = reported ?: packetPower
            val epoch = System.currentTimeMillis() +
                (result.timestampNanos - android.os.SystemClock.elapsedRealtimeNanos()) / 1_000_000L
            listener.onLog("SCAN ${device.address} RSSI=${result.rssi} dBm TX_POWER advertised=${power?.let { "$it dBm" } ?: "N/A"}")
            listener.onScanDevice(device, name, result.rssi, power, epoch)
        }

        override fun onScanFailed(errorCode: Int) {
            if (!scanning) return
            scanning = false
            listener.onReflectorError("BLE scan 실패: $errorCode")
        }
    }

    private val clientGattCallback = object : BluetoothGattCallback() {
        override fun onPhyUpdate(gatt: BluetoothGatt, txPhy: Int, rxPhy: Int, status: Int) {
            mainHandler.post {
                if (clientGatt === gatt) reportPhy(txPhy, rxPhy, status)
            }
        }

        override fun onPhyRead(gatt: BluetoothGatt, txPhy: Int, rxPhy: Int, status: Int) {
            mainHandler.post {
                if (clientGatt === gatt) reportPhy(txPhy, rxPhy, status)
            }
        }

        override fun onReadRemoteRssi(gatt: BluetoothGatt, rssi: Int, status: Int) {
            val timestamp = System.currentTimeMillis()
            mainHandler.post {
                if (clientGatt !== gatt || rssiGatt !== gatt) return@post
                rssiRequestPending = false
                if (status == BluetoothGatt.GATT_SUCCESS) {
                    listener.onConnectionRssi(gatt.device.address, rssi, timestamp)
                }
            }
        }

        @SuppressLint("MissingPermission")
        override fun onConnectionStateChange(
            gatt: BluetoothGatt,
            status: Int,
            newState: Int
        ) {
            if (clientGatt !== gatt) return // A closed connection must not end a newer session.
            if (status != BluetoothGatt.GATT_SUCCESS) {
                releaseClientGattFromCallback(gatt)
                listener.onReflectorError("GATT 오류 status=$status")
                listener.onDisconnected()
                return
            }

            when (newState) {
                BluetoothProfile.STATE_CONNECTED -> {
                    clientGatt = gatt
                    clientConnected = true
                    serviceDiscoveryRequested = false

                    listener.onSelectedPeerConnected(gatt.device.address)
                    listener.onLog("GATT 연결 성공")
                    startConnectionRssi(gatt)
                    mainHandler.post {
                        if (clientGatt === gatt && hasBlePermissions()) {
                            try { gatt.readPhy() }
                            catch (e: Exception) { listener.onLog("PHY 읽기 실패: ${e.message}") }
                        }
                    }

                    when (gatt.device.bondState) {
                        BluetoothDevice.BOND_BONDED -> {
                            listener.onLog("이미 Bond 완료됨 → 서비스 탐색 예약")
                            scheduleServiceDiscovery(gatt, 200L)
                        }

                        BluetoothDevice.BOND_BONDING -> {
                            listener.onLog("Bond 진행 중")
                        }

                        else -> {
                            listener.onLog("Bond 시작")
                            val requested = gatt.device.createBond()
                            listener.onLog("Bond 요청 결과: $requested")

                            if (!requested) {
                                listener.onReflectorError("Bond 요청 시작 실패")
                            }
                        }
                    }
                }

                BluetoothProfile.STATE_DISCONNECTED -> {
                    listener.onLog("GATT 연결 해제")
                    releaseClientGattFromCallback(gatt)
                    listener.onDisconnected()
                }
            }
        }

        @SuppressLint("MissingPermission")
        override fun onServicesDiscovered(gatt: BluetoothGatt, status: Int) {
            if (clientGatt !== gatt) return
            serviceDiscoveryRequested = false
            listener.onLog("GATT services discovered address=${gatt.device.address} status=$status")

            if (status != BluetoothGatt.GATT_SUCCESS) {
                listener.onReflectorError("서비스 탐색 실패: $status")
                return
            }

            gatt.services.forEach { discoveredService ->
                listener.onLog("GATT service uuid=${discoveredService.uuid} type=${discoveredService.type}")
                discoveredService.characteristics.forEach { characteristic ->
                    listener.onLog("GATT characteristic service=${discoveredService.uuid} uuid=${characteristic.uuid} " +
                        "properties=0x${characteristic.properties.toString(16)} " +
                        "descriptors=${characteristic.descriptors.map { it.uuid }}")
                }
            }

            val service = gatt.getService(CsProtocol.SERVICE_UUID)

            if (service == null) {
                isHardwareReflector = true
                listener.onLog("Phone CS 서비스 없음 - 하드웨어 CS Reflector (Nordic 등) 감지됨")
                onPhyReady(gatt)
                listener.onControlChannelReady(gatt.device.address)
                return
            }

            isHardwareReflector = false
            controlCharacteristic =
                service.getCharacteristic(CsProtocol.CONTROL_UUID)

            statusCharacteristic =
                service.getCharacteristic(CsProtocol.STATUS_UUID)

            distanceCharacteristic =
                service.getCharacteristic(CsProtocol.DISTANCE_UUID)

            val statusChar = statusCharacteristic

            if (
                controlCharacteristic == null ||
                statusChar == null ||
                distanceCharacteristic == null
            ) {
                listener.onReflectorError(
                    "Phone CS GATT service의 control/status/distance characteristic을 찾지 못했습니다. " +
                            "양쪽 앱을 모두 새 코드로 설치했는지 확인하세요."
                )
                return
            }

            val notificationSet = gatt.setCharacteristicNotification(
                statusChar,
                true
            )

            if (!notificationSet) {
                listener.onReflectorError("로컬 notification 설정 실패")
                return
            }

            val cccd = statusChar.getDescriptor(CsProtocol.CCCD_UUID)
            if (cccd == null) {
                listener.onReflectorError("CCCD를 찾지 못했습니다.")
                return
            }

            if (Build.VERSION.SDK_INT >= 33) {
                val result = gatt.writeDescriptor(
                    cccd,
                    BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
                )

                if (result != BluetoothStatusCodes.SUCCESS) {
                    listener.onReflectorError(
                        "CCCD 쓰기 요청 실패: status=$result"
                    )
                }
            } else {
                @Suppress("DEPRECATION")
                run {
                    cccd.value = BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
                    val requested = gatt.writeDescriptor(cccd)

                    if (!requested) {
                        listener.onReflectorError("CCCD 쓰기 요청 실패")
                    }
                }
            }
        }

        override fun onDescriptorWrite(
            gatt: BluetoothGatt,
            descriptor: BluetoothGattDescriptor,
            status: Int
        ) {
            if (clientGatt !== gatt) return
            if (descriptor.uuid != CsProtocol.CCCD_UUID) return

            if (status == BluetoothGatt.GATT_SUCCESS) {
                onPhyReady(gatt)
                listener.onControlChannelReady(gatt.device.address)
            } else {
                listener.onReflectorError("알림 설정 실패: $status")
            }
        }

        override fun onCharacteristicWrite(
            gatt: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic,
            status: Int
        ) {
            if (clientGatt !== gatt) return
            val completed = synchronized(writeLock) {
                currentWrite.also { currentWrite = null }
            }

            if (status == BluetoothGatt.GATT_SUCCESS) {
                if (completed?.characteristic?.uuid != CsProtocol.DISTANCE_UUID) {
                    listener.onLog("GATT write 완료: ${completed?.description ?: characteristic.uuid}")
                }
            } else {
                listener.onReflectorError(
                    "GATT write 실패(${completed?.description ?: characteristic.uuid}): $status"
                )
            }

            drainWriteQueue()
        }

        @Deprecated("Deprecated in Java")
        override fun onCharacteristicChanged(
            gatt: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic
        ) {
            if (clientGatt !== gatt) return
            @Suppress("DEPRECATION")
            handleStatus(gatt.device.address, characteristic.value)
        }

        override fun onCharacteristicChanged(
            gatt: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic,
            value: ByteArray
        ) {
            if (clientGatt !== gatt) return
            handleStatus(gatt.device.address, value)
        }
    }

    private fun handleStatus(reflectorAddress: String, value: ByteArray) {
        val message = value.toString(StandardCharsets.UTF_8)
        listener.onLog("Reflector 상태: $message")

        when {
            message == CsProtocol.READY -> {
                listener.onReflectorReady(reflectorAddress)
            }

            message.startsWith(CsProtocol.ERROR_PREFIX) -> {
                listener.onReflectorError(
                    message.removePrefix(CsProtocol.ERROR_PREFIX)
                )
            }
        }
    }

    private val serverCallback = object : BluetoothGattServerCallback() {
        override fun onPhyUpdate(device: BluetoothDevice, txPhy: Int, rxPhy: Int, status: Int) {
            mainHandler.post {
                if (serverConnectedDevice == device) reportPhy(txPhy, rxPhy, status)
            }
        }

        override fun onPhyRead(device: BluetoothDevice, txPhy: Int, rxPhy: Int, status: Int) {
            mainHandler.post {
                if (serverConnectedDevice == device) reportPhy(txPhy, rxPhy, status)
            }
        }

        @SuppressLint("MissingPermission")
        override fun onServiceAdded(
            status: Int,
            service: BluetoothGattService
        ) {
            mainHandler.post {
                if (advertisingService !== service || !advertisingStarting) return@post
                if (status == BluetoothGatt.GATT_SUCCESS) beginAdvertising()
                else listener.onReflectorError("GATT service 추가 실패: $status")
            }
        }

        @SuppressLint("MissingPermission")
        override fun onConnectionStateChange(
            device: BluetoothDevice,
            status: Int,
            newState: Int
        ) {
            if (gattServer == null) return
            if (newState == BluetoothProfile.STATE_DISCONNECTED && serverConnectedDevice != device) return
            if (status != BluetoothGatt.GATT_SUCCESS) {
                listener.onReflectorError("GATT server 연결 오류: $status")
                return
            }

            when (newState) {
                BluetoothProfile.STATE_CONNECTED -> {
                    serverConnectedDevice = device
                    mainHandler.post {
                        if (serverConnectedDevice == device && hasBlePermissions()) {
                            try { gattServer?.readPhy(device) }
                            catch (e: Exception) { listener.onLog("PHY 읽기 실패: ${e.message}") }
                        }
                    }
                    notificationsEnabled = false

                    listener.onSelectedPeerConnected(device.address)
                    listener.onLog("Initiator 연결됨: ${device.address}")

                    // Keep advertising active during GATT and CS so the Initiator can scan it.
                    listener.onLog("Advertising kept active during GATT/CS")
                }

                BluetoothProfile.STATE_DISCONNECTED -> {
                    clearPhy()
                    serverConnectedDevice = null
                    notificationsEnabled = false
                    listener.onDisconnected()
                }
            }
        }

        override fun onDescriptorWriteRequest(
            device: BluetoothDevice,
            requestId: Int,
            descriptor: BluetoothGattDescriptor,
            preparedWrite: Boolean,
            responseNeeded: Boolean,
            offset: Int,
            value: ByteArray
        ) {
            if (descriptor.uuid == CsProtocol.CCCD_UUID) {
                notificationsEnabled = value.contentEquals(
                    BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
                )

                listener.onLog(
                    "CCCD 상태: notificationsEnabled=$notificationsEnabled"
                )
            }

            if (responseNeeded) {
                sendServerResponse(
                    device,
                    requestId,
                    BluetoothGatt.GATT_SUCCESS,
                    offset,
                    value
                )
            }
        }

        @SuppressLint("MissingPermission")
        override fun onCharacteristicWriteRequest(
            device: BluetoothDevice,
            requestId: Int,
            characteristic: BluetoothGattCharacteristic,
            preparedWrite: Boolean,
            responseNeeded: Boolean,
            offset: Int,
            value: ByteArray
        ) {
            if (preparedWrite || offset != 0) {
                if (responseNeeded) {
                    sendServerResponse(
                        device,
                        requestId,
                        BluetoothGatt.GATT_REQUEST_NOT_SUPPORTED,
                        offset,
                        null
                    )
                }
                return
            }

            if (device.bondState != BluetoothDevice.BOND_BONDED) {
                if (responseNeeded) {
                    sendServerResponse(
                        device,
                        requestId,
                        BluetoothGatt.GATT_INSUFFICIENT_AUTHENTICATION,
                        offset,
                        null
                    )
                }

                listener.onReflectorError(
                    "Bond되지 않은 Initiator의 요청을 거부했습니다."
                )
                return
            }

            when (characteristic.uuid) {
                CsProtocol.CONTROL_UUID -> {
                    handleControlWrite(
                        device = device,
                        requestId = requestId,
                        responseNeeded = responseNeeded,
                        value = value
                    )
                }

                CsProtocol.DISTANCE_UUID -> {
                    handleDistanceWrite(
                        device = device,
                        requestId = requestId,
                        responseNeeded = responseNeeded,
                        value = value
                    )
                }

                else -> {
                    if (responseNeeded) {
                        sendServerResponse(
                            device,
                            requestId,
                            BluetoothGatt.GATT_REQUEST_NOT_SUPPORTED,
                            offset,
                            null
                        )
                    }
                }
            }
        }
    }

    // Permission can be revoked between connection and a server write callback.
    private fun sendServerResponse(device: BluetoothDevice, requestId: Int, status: Int, offset: Int, value: ByteArray?) {
        try {
            gattServer?.sendResponse(device, requestId, status, offset, value)
        } catch (e: SecurityException) {
            listener.onReflectorError("GATT response permission revoked: ${e.message}")
        }
    }

    private fun handleControlWrite(
        device: BluetoothDevice,
        requestId: Int,
        responseNeeded: Boolean,
        value: ByteArray
    ) {
        val command = value.toString(StandardCharsets.UTF_8)

        if (responseNeeded) {
            sendServerResponse(
                device,
                requestId,
                BluetoothGatt.GATT_SUCCESS,
                0,
                value
            )
        }

        when (command) {
            CsProtocol.START -> listener.onStartReflectorRequested(device.address)
            CsProtocol.STOP -> listener.onStopReflectorRequested()
            else -> listener.onReflectorError("알 수 없는 명령: $command")
        }
    }

    private fun handleDistanceWrite(
        device: BluetoothDevice,
        requestId: Int,
        responseNeeded: Boolean,
        value: ByteArray
    ) {
        val packet = CsProtocol.decodeDistance(value)

        if (packet == null) {
            if (responseNeeded) {
                sendServerResponse(
                    device,
                    requestId,
                    BluetoothGatt.GATT_INVALID_ATTRIBUTE_LENGTH,
                    0,
                    null
                )
            }

            listener.onReflectorError(
                "거리 패킷 해석 실패: length=${value.size}"
            )
            return
        }

        if (responseNeeded) {
            sendServerResponse(
                device,
                requestId,
                BluetoothGatt.GATT_SUCCESS,
                0,
                value
            )
        }

        listener.onRemoteDistance(
            rawMeters = packet.rawMeters,
            smoothedMeters = packet.smoothedMeters,
            sampleCount = packet.sampleCount
        )
    }

    private val bondReceiver = object : BroadcastReceiver() {
        @SuppressLint("MissingPermission")
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action != BluetoothDevice.ACTION_BOND_STATE_CHANGED) {
                return
            }

            val device = if (Build.VERSION.SDK_INT >= 33) {
                intent.getParcelableExtra(
                    BluetoothDevice.EXTRA_DEVICE,
                    BluetoothDevice::class.java
                )
            } else {
                @Suppress("DEPRECATION")
                intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE)
            } ?: return

            val state = intent.getIntExtra(
                BluetoothDevice.EXTRA_BOND_STATE,
                BluetoothDevice.BOND_NONE
            )

            val previousState = intent.getIntExtra(
                BluetoothDevice.EXTRA_PREVIOUS_BOND_STATE,
                BluetoothDevice.BOND_NONE
            )

            listener.onBondState(device.address, state)

            val currentGatt = clientGatt ?: return
            if (!currentGatt.device.address.equals(
                    device.address,
                    ignoreCase = true
                )
            ) {
                return
            }

            when (state) {
                BluetoothDevice.BOND_BONDED -> {
                    listener.onLog("Bond 완료 → 300 ms 후 서비스 탐색")
                    scheduleServiceDiscovery(currentGatt, 300L)
                }

                BluetoothDevice.BOND_BONDING -> {
                    listener.onLog("Bond 진행 중")
                }

                BluetoothDevice.BOND_NONE -> {
                    if (previousState == BluetoothDevice.BOND_BONDING) {
                        listener.onReflectorError(
                            "Bond 실패 또는 취소됨: ${device.address}"
                        )
                    }
                }
            }
        }
    }

    private fun hasBlePermissions(): Boolean {
        val scan = ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.BLUETOOTH_SCAN
        ) == PackageManager.PERMISSION_GRANTED

        val connect = ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.BLUETOOTH_CONNECT
        ) == PackageManager.PERMISSION_GRANTED

        val advertise = ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.BLUETOOTH_ADVERTISE
        ) == PackageManager.PERMISSION_GRANTED

        return scan && connect && advertise
    }
}



