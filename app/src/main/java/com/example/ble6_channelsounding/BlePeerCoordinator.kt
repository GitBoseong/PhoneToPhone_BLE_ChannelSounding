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
import android.bluetooth.le.AdvertiseCallback
import android.bluetooth.le.AdvertiseData
import android.bluetooth.le.AdvertiseSettings
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanFilter
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
import androidx.core.content.ContextCompat
import java.nio.charset.StandardCharsets

class BlePeerCoordinator(
    private val context: Context,
    private val listener: Listener
) {
    interface Listener {
        fun onLog(message: String)
        fun onScanDevice(device: BluetoothDevice, name: String, rssi: Int)
        fun onSelectedPeerConnected(address: String)
        fun onBondState(address: String, state: Int)
        fun onControlChannelReady(address: String)
        fun onStartReflectorRequested(initiatorAddress: String)
        fun onStopReflectorRequested()
        fun onReflectorReady(reflectorAddress: String)
        fun onReflectorError(message: String)
        fun onDisconnected()
    }

    private val bluetoothManager =
        context.getSystemService(BluetoothManager::class.java)

    private val adapter: BluetoothAdapter = bluetoothManager.adapter
    private val mainHandler = Handler(Looper.getMainLooper())

    private var selectedDevice: BluetoothDevice? = null

    private var clientGatt: BluetoothGatt? = null
    private var controlCharacteristic: BluetoothGattCharacteristic? = null
    private var statusCharacteristic: BluetoothGattCharacteristic? = null
    private var serviceDiscoveryRequested = false
    private var pendingDiscoveryRunnable: Runnable? = null

    private var gattServer: BluetoothGattServer? = null
    private var serverStatusCharacteristic: BluetoothGattCharacteristic? = null
    private var serverConnectedDevice: BluetoothDevice? = null
    private var notificationsEnabled = false
    private var advertising = false

    private var bondReceiverRegistered = false

    fun register() {
        if (bondReceiverRegistered) return

        val filter = IntentFilter(BluetoothDevice.ACTION_BOND_STATE_CHANGED)

        /*
         * Bluetooth의 bond 상태 브로드캐스트는 시스템의 Bluetooth 프로세스에서 옵니다.
         * 따라서 RECEIVER_NOT_EXPORTED가 아니라 RECEIVER_EXPORTED로 등록합니다.
         */
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

        val filter = ScanFilter.Builder()
            .setServiceUuid(ParcelUuid(CsProtocol.SERVICE_UUID))
            .build()

        val settings = ScanSettings.Builder()
            .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
            .build()

        adapter.bluetoothLeScanner?.startScan(
            listOf(filter),
            settings,
            scanCallback
        ) ?: run {
            listener.onReflectorError("BLE scanner를 사용할 수 없습니다.")
            return
        }

        listener.onLog("Reflector 스캔 시작")
    }

    @SuppressLint("MissingPermission")
    fun stopScan() {
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

        stopScan()
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

    @SuppressLint("MissingPermission")
    fun requestStartFromReflector() {
        val gatt = clientGatt ?: run {
            listener.onReflectorError("GATT 연결이 없습니다.")
            return
        }

        val characteristic = controlCharacteristic ?: run {
            listener.onReflectorError("Control characteristic가 준비되지 않았습니다.")
            return
        }

        if (writeCharacteristic(gatt, characteristic, CsProtocol.START)) {
            listener.onLog("Reflector에 START 요청")
        } else {
            listener.onReflectorError("Reflector START 쓰기 요청이 즉시 거부되었습니다.")
        }
    }

    @SuppressLint("MissingPermission")
    fun requestStopFromReflector() {
        val gatt = clientGatt ?: return
        val characteristic = controlCharacteristic ?: return

        if (writeCharacteristic(gatt, characteristic, CsProtocol.STOP)) {
            listener.onLog("Reflector에 STOP 요청")
        }
    }

    @SuppressLint("MissingPermission")
    fun startReflectorAdvertising() {
        if (!hasBlePermissions()) {
            listener.onReflectorError("BLE 권한이 없습니다.")
            return
        }

        closeServer()

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

        service.addCharacteristic(control)
        service.addCharacteristic(status)

        serverStatusCharacteristic = status

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

        val settings = AdvertiseSettings.Builder()
            .setAdvertiseMode(AdvertiseSettings.ADVERTISE_MODE_LOW_LATENCY)
            .setTxPowerLevel(AdvertiseSettings.ADVERTISE_TX_POWER_HIGH)
            .setConnectable(true)
            .setTimeout(0)
            .build()

        val data = AdvertiseData.Builder()
            .addServiceUuid(ParcelUuid(CsProtocol.SERVICE_UUID))
            .setIncludeDeviceName(false)
            .build()

        advertiser.startAdvertising(settings, data, advertiseCallback)
    }

    @SuppressLint("MissingPermission")
    fun notifyReflectorReady() {
        notifyStatus(CsProtocol.READY)
    }

    @SuppressLint("MissingPermission")
    fun notifyReflectorPreparing() {
        notifyStatus(CsProtocol.PREPARING)
    }

    @SuppressLint("MissingPermission")
    fun notifyReflectorStopped() {
        notifyStatus(CsProtocol.STOPPED)
    }

    @SuppressLint("MissingPermission")
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
    private fun closeClientGatt() {
        cancelPendingDiscovery()

        val gatt = clientGatt

        clientGatt = null
        controlCharacteristic = null
        statusCharacteristic = null
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

        if (clientGatt === gatt) {
            clientGatt = null
            controlCharacteristic = null
            statusCharacteristic = null
            serviceDiscoveryRequested = false
        }

        try {
            gatt.close()
        } catch (_: Exception) {
        }
    }

    @SuppressLint("MissingPermission")
    private fun closeServer() {
        try {
            if (advertising) {
                adapter.bluetoothLeAdvertiser?.stopAdvertising(advertiseCallback)
            }
        } catch (_: Exception) {
        }

        advertising = false

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

            if (clientGatt !== gatt) {
                return@Runnable
            }

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

    private val scanCallback = object : ScanCallback() {
        @SuppressLint("MissingPermission")
        override fun onScanResult(callbackType: Int, result: ScanResult) {
            val device = result.device
            val name = result.scanRecord?.deviceName
                ?: device.name
                ?: "Phone CS Reflector"

            listener.onScanDevice(device, name, result.rssi)
        }

        override fun onScanFailed(errorCode: Int) {
            listener.onReflectorError("BLE scan 실패: $errorCode")
        }
    }

    private val advertiseCallback = object : AdvertiseCallback() {
        override fun onStartSuccess(settingsInEffect: AdvertiseSettings?) {
            advertising = true
            listener.onLog("Reflector 광고 시작됨")
        }

        override fun onStartFailure(errorCode: Int) {
            advertising = false
            listener.onReflectorError("광고 시작 실패: $errorCode")
        }
    }

    private val clientGattCallback = object : BluetoothGattCallback() {
        @SuppressLint("MissingPermission")
        override fun onConnectionStateChange(
            gatt: BluetoothGatt,
            status: Int,
            newState: Int
        ) {
            if (status != BluetoothGatt.GATT_SUCCESS) {
                listener.onReflectorError("GATT 오류 status=$status")
                releaseClientGattFromCallback(gatt)
                listener.onDisconnected()
                return
            }

            when (newState) {
                BluetoothProfile.STATE_CONNECTED -> {
                    clientGatt = gatt
                    serviceDiscoveryRequested = false

                    listener.onSelectedPeerConnected(gatt.device.address)
                    listener.onLog("GATT 연결 성공")

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
            serviceDiscoveryRequested = false

            if (status != BluetoothGatt.GATT_SUCCESS) {
                listener.onReflectorError("서비스 탐색 실패: $status")
                return
            }

            val service = gatt.getService(CsProtocol.SERVICE_UUID)
            controlCharacteristic = service?.getCharacteristic(CsProtocol.CONTROL_UUID)
            statusCharacteristic = service?.getCharacteristic(CsProtocol.STATUS_UUID)

            val statusChar = statusCharacteristic

            if (controlCharacteristic == null || statusChar == null) {
                listener.onReflectorError("Phone CS GATT service를 찾지 못했습니다.")
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
            if (descriptor.uuid != CsProtocol.CCCD_UUID) return

            if (status == BluetoothGatt.GATT_SUCCESS) {
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
            if (characteristic.uuid != CsProtocol.CONTROL_UUID) return

            if (status == BluetoothGatt.GATT_SUCCESS) {
                listener.onLog("GATT control 쓰기 완료")
            } else {
                listener.onReflectorError("GATT control 쓰기 실패: $status")
            }
        }

        @Deprecated("Deprecated in Java")
        override fun onCharacteristicChanged(
            gatt: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic
        ) {
            @Suppress("DEPRECATION")
            handleStatus(gatt.device.address, characteristic.value)
        }

        override fun onCharacteristicChanged(
            gatt: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic,
            value: ByteArray
        ) {
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
        @SuppressLint("MissingPermission")
        override fun onServiceAdded(
            status: Int,
            service: BluetoothGattService
        ) {
            if (status == BluetoothGatt.GATT_SUCCESS) {
                beginAdvertising()
            } else {
                listener.onReflectorError("GATT service 추가 실패: $status")
            }
        }

        @SuppressLint("MissingPermission")
        override fun onConnectionStateChange(
            device: BluetoothDevice,
            status: Int,
            newState: Int
        ) {
            if (status != BluetoothGatt.GATT_SUCCESS) {
                listener.onReflectorError("GATT server 연결 오류: $status")
                return
            }

            when (newState) {
                BluetoothProfile.STATE_CONNECTED -> {
                    serverConnectedDevice = device
                    notificationsEnabled = false

                    listener.onSelectedPeerConnected(device.address)
                    listener.onLog("Initiator 연결됨: ${device.address}")

                    try {
                        adapter.bluetoothLeAdvertiser?.stopAdvertising(
                            advertiseCallback
                        )
                        advertising = false
                    } catch (_: Exception) {
                    }
                }

                BluetoothProfile.STATE_DISCONNECTED -> {
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
                gattServer?.sendResponse(
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
            if (characteristic.uuid != CsProtocol.CONTROL_UUID) {
                if (responseNeeded) {
                    gattServer?.sendResponse(
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
                    gattServer?.sendResponse(
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

            val command = value.toString(StandardCharsets.UTF_8)

            if (responseNeeded) {
                gattServer?.sendResponse(
                    device,
                    requestId,
                    BluetoothGatt.GATT_SUCCESS,
                    offset,
                    value
                )
            }

            when (command) {
                CsProtocol.START -> {
                    listener.onStartReflectorRequested(device.address)
                }

                CsProtocol.STOP -> {
                    listener.onStopReflectorRequested()
                }

                else -> {
                    listener.onReflectorError("알 수 없는 명령: $command")
                }
            }
        }
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

    @SuppressLint("MissingPermission")
    private fun writeCharacteristic(
        gatt: BluetoothGatt,
        characteristic: BluetoothGattCharacteristic,
        text: String
    ): Boolean {
        val bytes = text.toByteArray(StandardCharsets.UTF_8)

        return if (Build.VERSION.SDK_INT >= 33) {
            gatt.writeCharacteristic(
                characteristic,
                bytes,
                BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
            ) == BluetoothStatusCodes.SUCCESS
        } else {
            @Suppress("DEPRECATION")
            run {
                characteristic.writeType =
                    BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
                characteristic.value = bytes
                gatt.writeCharacteristic(characteristic)
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
