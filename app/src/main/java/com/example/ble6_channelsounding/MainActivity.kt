package com.example.ble6_channelsounding

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.View
import android.widget.ArrayAdapter
import android.widget.SeekBar
import android.text.method.ScrollingMovementMethod
import androidx.activity.result.contract.ActivityResultContracts
import androidx.annotation.RequiresApi
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import com.example.ble6_channelsounding.databinding.ActivityMainBinding
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class MainActivity : AppCompatActivity(),
    BlePeerCoordinator.Listener,
    ChannelSoundingController.Listener {

    private lateinit var binding: ActivityMainBinding
    private lateinit var bleCoordinator: BlePeerCoordinator
    private var csController: ChannelSoundingController? = null
    private lateinit var recorder: MeasurementRecorder
    private var advertisingBusy = false
    private var bleConnected = false
    private var recording = false
    private var requestedTxPower: Int? = null
    private var appliedTxPower: Int? = null
    private val scanSamples = mutableMapOf<String, ScanSample>()
    private val scanPowers = mutableMapOf<String, Int?>()
    private var bleState = "Disconnected"
    private var csState = "Idle"
    private var stopping = false

    companion object {
        private const val DEFAULT_IMU_ENABLED = true
        private const val DEFAULT_GPS_ENABLED = true
    }

    private val gpsPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) {
        val granted = ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED
        if (!granted) {
            binding.gpsSwitch.isChecked = false
            log("Location permission denied (precise location required)", "GPS")
        }
        recorder.setGpsEnabled(granted && binding.gpsSwitch.isChecked)
    }

    private enum class UiRole { INITIATOR, REFLECTOR }

    private var uiRole = UiRole.INITIATOR

    private val foundDevices = linkedMapOf<String, BluetoothDevice>()
    private val deviceLabels = mutableListOf<String>()
    private lateinit var deviceAdapter: ArrayAdapter<String>

    private var selectedAddress: String? = null
    private var controlReady = false

    /* Reflector가 완전히 준비된 뒤 Initiator를 약간 늦게 시작하여 역할 반전 안정성 향상 */
    private val csStartHandler = Handler(Looper.getMainLooper())

    private val logLines = ArrayDeque<String>()

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { grants ->
        val denied = grants.filterValues { !it }.keys

        if (denied.isEmpty()) {
            log("모든 권한 허용 완료")
            if (Build.VERSION.SDK_INT >= 36) initChannelSounding()
        } else {
            log("권한 거부: $denied")
        }
        ensureGpsPermission()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)
        ViewCompat.setOnApplyWindowInsetsListener(binding.root) { view, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            view.setPadding(bars.left, bars.top, bars.right, bars.bottom)
            insets
        }
        binding.imuSwitch.isChecked = DEFAULT_IMU_ENABLED
        binding.gpsSwitch.isChecked = DEFAULT_GPS_ENABLED
        binding.statusText.movementMethod = ScrollingMovementMethod()

        if (Build.VERSION.SDK_INT < 36) {
            binding.capabilityText.text = "Android 16 / API 36 이상 필요"
            disableAllActions()
            return
        }

        recorder = MeasurementRecorder(applicationContext,
            status = { message -> runOnUiThread {
                binding.recordingText.text = message
                log(message, "STORAGE", persist = false)
            } },
            gpsUnavailable = { runOnUiThread { binding.gpsSwitch.isChecked = false } })
        setupSensorsAndPower()
        bleCoordinator = BlePeerCoordinator(this, this)
        bleCoordinator.register()

        deviceAdapter = ArrayAdapter(
            this,
            android.R.layout.simple_list_item_single_choice,
            deviceLabels
        )

        binding.deviceList.adapter = deviceAdapter

        binding.deviceList.setOnItemClickListener { _, _, position, _ ->
            val address = foundDevices.keys.elementAtOrNull(position)
                ?: return@setOnItemClickListener

            selectedAddress = address
            recorder.setTx(currentTxState())
            foundDevices[address]?.let(bleCoordinator::selectDevice)
            binding.selectedDeviceText.text = "선택 장치: $address"
        }

        binding.applyRoleButton.setOnClickListener {
            applySelectedRole()
        }

        binding.scanButton.setOnClickListener {
            foundDevices.clear()
            scanSamples.clear()
            scanPowers.clear()
            deviceLabels.clear()
            deviceAdapter.notifyDataSetChanged()
            binding.deviceList.clearChoices()
            binding.selectedDeviceText.text = "선택 장치: -"

            selectedAddress = null
            controlReady = false
            binding.startCsButton.isEnabled = false

            bleState = "Scanning"
            updateStatus()
            bleCoordinator.startScan()
        }

        binding.pairButton.setOnClickListener {
            controlReady = false
            binding.startCsButton.isEnabled = false
            bleState = "Connecting / Pairing"
            updateStatus()
            bleCoordinator.connectAndPairSelected()
        }

        binding.startCsButton.setOnClickListener {
            if (!controlReady) {
                log("먼저 연결·페어링·GATT 준비를 완료하세요.")
                return@setOnClickListener
            }

            binding.startCsButton.isEnabled = false
            startCsvSession(UiRole.INITIATOR, selectedAddress)
            bleCoordinator.requestStartFromReflector()
        }

        binding.advertiseButton.setOnClickListener {
            bleCoordinator.startReflectorAdvertising(requestedTxPower)
        }

        binding.stopButton.setOnClickListener {
            stopEverything()
        }

        applySelectedRole()
        requestPermissionsIfNeeded()
    }

    @RequiresApi(36)
    private fun initChannelSounding() {
        if (csController == null) {
            csController = ChannelSoundingController(this, this)
        }

        csController?.checkCapability()
    }

    private fun requestPermissionsIfNeeded() {
        if (Build.VERSION.SDK_INT < 36) return
        val permissions = arrayOf(
            Manifest.permission.BLUETOOTH_SCAN,
            Manifest.permission.BLUETOOTH_CONNECT,
            Manifest.permission.BLUETOOTH_ADVERTISE,
            Manifest.permission.RANGING
        )

        val missing = permissions.filter { permission ->
            ContextCompat.checkSelfPermission(
                this,
                permission
            ) != PackageManager.PERMISSION_GRANTED
        }

        if (missing.isEmpty()) {
            if (Build.VERSION.SDK_INT >= 36) {
                initChannelSounding()
                ensureGpsPermission()
            }
        } else {
            permissionLauncher.launch(missing.toTypedArray())
        }
    }

    private fun applySelectedRole() {
        stopEverything(keepLog = true)

        uiRole = if (binding.reflectorRadio.isChecked) {
            UiRole.REFLECTOR
        } else {
            UiRole.INITIATOR
        }

        recorder.setRole(uiRole.name)
        recorder.setTx(currentTxState())
        val initiator = uiRole == UiRole.INITIATOR
        binding.txPowerPanel.visibility = if (initiator) View.GONE else View.VISIBLE
        updateStatus()
        updatePowerUi()

        binding.initiatorPanel.visibility =
            if (initiator) View.VISIBLE else View.GONE

        binding.reflectorPanel.visibility =
            if (initiator) View.GONE else View.VISIBLE

        binding.distanceText.text = "--.-- m"

        binding.rawDistanceText.text = if (initiator) {
            "Raw: - / samples: 0"
        } else {
            "Initiator가 전달하는 거리값 대기 중"
        }

        controlReady = false
        binding.startCsButton.isEnabled = false

        log("역할 적용: $uiRole")
    }

    private fun stopEverything(keepLog: Boolean = false) {
        if (stopping) return
        stopping = true
        if (!keepLog) {
            log("전체 세션 중지", "APP")
        }
        csStartHandler.removeCallbacksAndMessages(null)
        if (Build.VERSION.SDK_INT >= 36) csController?.stop()

        if (::bleCoordinator.isInitialized) {
            bleCoordinator.stopAll()
        }

        controlReady = false

        if (::binding.isInitialized) {
            binding.startCsButton.isEnabled = false
        }

        finishRecording("Stop / disconnect")
        bleConnected = false
        bleState = "Disconnected"
        csState = "Idle"
        binding.reflectorPeerText.text = "연결된 Initiator: -"
        binding.selectedDeviceText.text = "선택 장치: ${selectedAddress ?: "-"}"
        updateStatus()
        updatePowerUi()
        stopping = false
    }

    override fun onDestroy() {
        stopEverything(keepLog = true)

        if (::bleCoordinator.isInitialized) {
            bleCoordinator.unregister()
        }

        if (::recorder.isInitialized) recorder.destroy()
        super.onDestroy()
    }

    override fun onLog(message: String) {
        log(message, "BLE")
    }

    @SuppressLint("MissingPermission")
    override fun onScanDevice(device: BluetoothDevice, name: String, rssi: Int, txPower: Int?, timestamp: Long) {
        runOnUiThread {
            val address = device.address.uppercase(Locale.US)
            val sample = ScanSample(rssi, timestamp)
            scanSamples[address] = sample
            scanPowers[address] = txPower
            foundDevices[address] = device
            val label = "$name\n$address\nRSSI: $rssi dBm    TX: ${txPower?.let { "$it dBm" } ?: "N/A"}"
            val index = foundDevices.keys.indexOf(address)
            if (index < deviceLabels.size) deviceLabels[index] = label else deviceLabels.add(label)
            deviceAdapter.notifyDataSetChanged()
            if (selectedAddress == address) {
                recorder.setTx(currentTxState())
                recorder.scan(sample)
                binding.rssiText.text = "BLE RSSI: $rssi dBm / Ranging RSSI: -"
            }
        }
    }

    override fun onAdvertisingState(busy: Boolean, appliedTxPower: Int?) {
        runOnUiThread {
            advertisingBusy = busy
            this.appliedTxPower = appliedTxPower
            if (!bleConnected) bleState = if (busy) {
                if (appliedTxPower == null) "Starting advertising" else "Advertising"
            } else "Disconnected"
            if (uiRole == UiRole.REFLECTOR && (appliedTxPower != null || !recording)) {
                recorder.setTx(currentTxState())
            }
            updatePowerUi()
            updateStatus()
        }
    }

    override fun onSelectedPeerConnected(address: String) {
        runOnUiThread {
            bleConnected = true
            bleState = "Connected"
            updateStatus()
            updatePowerUi()
            if (uiRole == UiRole.REFLECTOR) {
                binding.reflectorPeerText.text =
                    "연결된 Initiator: $address"
            } else {
                binding.selectedDeviceText.text =
                    "연결된 Reflector: $address"
            }
        }
    }

    override fun onBondState(address: String, state: Int) {
        val stateText = when (state) {
            BluetoothDevice.BOND_BONDED -> "BONDED"
            BluetoothDevice.BOND_BONDING -> "BONDING"
            else -> "NONE"
        }

        log("Bond state $address: $stateText")
    }

    override fun onControlChannelReady(address: String) {
        runOnUiThread {
            controlReady = true
            bleState = "Connected / GATT ready"
            updateStatus()
            binding.startCsButton.isEnabled = true
            binding.selectedDeviceText.text =
                "페어링 및 제어·거리 채널 준비 완료: $address"
        }

        log("GATT 제어 및 거리 채널 준비 완료")
    }

    override fun onStartReflectorRequested(initiatorAddress: String) {
        if (uiRole != UiRole.REFLECTOR) return

        runOnUiThread {
            if (recording) { log("Duplicate START ignored", "BLE"); return@runOnUiThread }
            startCsvSession(UiRole.REFLECTOR, initiatorAddress)
            log("Initiator로부터 START 수신: $initiatorAddress")
            val controller = csController

            if (controller == null) {
                val message = "ChannelSoundingController가 초기화되지 않았습니다."
                log(message)
                bleCoordinator.notifyReflectorFailure(message)
                finishRecording("Controller unavailable")
                return@runOnUiThread
            }

            binding.distanceText.text = "--.-- m"
            binding.rawDistanceText.text = "Initiator 거리값 수신 대기 중"

            bleCoordinator.notifyReflectorPreparing()
            if (Build.VERSION.SDK_INT >= 36) {
                controller.startReflector(initiatorAddress.uppercase(Locale.US))
            }
        }
    }

    override fun onStopReflectorRequested() {
        log("Initiator로부터 STOP 수신")

        runOnUiThread {
            if (Build.VERSION.SDK_INT >= 36) csController?.stop()
            bleCoordinator.notifyReflectorStopped()
            binding.rawDistanceText.text = "거리 공유 중지됨"
            stopEverything()
        }
    }

    override fun onReflectorReady(reflectorAddress: String) {
        if (uiRole != UiRole.INITIATOR) return

        val normalizedAddress = reflectorAddress.uppercase(Locale.US)

        log("Reflector READY 수신 → 800 ms 후 Initiator CS 시작")

        csStartHandler.removeCallbacksAndMessages(null)
        csStartHandler.postDelayed({
            if (uiRole != UiRole.INITIATOR || !controlReady) {
                log("역할 또는 GATT 상태 변경으로 Initiator 시작 취소")
                finishRecording("CS start cancelled")
                restoreStartButton()
                return@postDelayed
            }

            val controller = csController
            if (controller == null) {
                log("CS 오류: ChannelSoundingController가 초기화되지 않았습니다.")
                finishRecording("CS start cancelled")
                restoreStartButton()
                return@postDelayed
            }

            if (Build.VERSION.SDK_INT >= 36) controller.startInitiator(normalizedAddress)
        }, 800L)
    }

    override fun onReflectorError(message: String) {
        log("BLE 오류: $message")
        runOnUiThread { stopEverything() }
    }

    override fun onRemoteDistance(
        rawMeters: Double,
        smoothedMeters: Double,
        sampleCount: Int
    ) {
        if (uiRole != UiRole.REFLECTOR) return

        recorder.distance(DistanceSample(rawMeters, smoothedMeters, sampleCount,
            System.currentTimeMillis(), null, "REMOTE_RECEIPT"))
        log("RELAY distance=$rawMeters smoothed=$smoothedMeters samples=$sampleCount RSSI=N/A", "CS")

        runOnUiThread {
            binding.distanceText.text = String.format(
                Locale.US,
                "%.3f m",
                smoothedMeters
            )

            binding.rawDistanceText.text = String.format(
                Locale.US,
                "Initiator relay / Raw: %.3f m / 5-sample mean: %.3f m / samples: %d",
                rawMeters,
                smoothedMeters,
                sampleCount
            )
        }
    }

    override fun onDisconnected() {
        runOnUiThread {
            log("Peer 연결 해제", "BLE")
            stopEverything(keepLog = true)
        }
    }

    override fun onCapability(supported: Boolean, detail: String) {
        runOnUiThread {
            binding.capabilityText.text = "Channel Sounding: ${if (supported) "Supported" else "Unavailable"}\n$detail"
        }

        log("Capability: $supported / $detail")
    }

    override fun onSessionOpened(role: ChannelSoundingController.Role) {
        csState = "Session opened"
        updateStatus()
        log("RangingSession opened: $role")

        if (Build.VERSION.SDK_INT >= 36 && role == ChannelSoundingController.Role.REFLECTOR) {
            bleCoordinator.notifyReflectorReady()
        }
    }

    override fun onRangingStarted(role: ChannelSoundingController.Role) {
        csState = "Measuring"
        updateStatus()
        log("CS started: $role")
    }

    override fun onDistance(
        rawMeters: Double,
        smoothedMeters: Double,
        sampleCount: Int,
        rssi: Int?,
        timestampEpochMs: Long
    ) {
        if (uiRole != UiRole.INITIATOR) return

        recorder.distance(DistanceSample(rawMeters, smoothedMeters, sampleCount,
            timestampEpochMs, rssi, "LOCAL_RANGING"))
        log("RANGING RSSI=${rssi?.let { "$it dBm" } ?: "N/A"} distance=$rawMeters smoothed=$smoothedMeters samples=$sampleCount", "CS")
        binding.rssiText.text = "BLE RSSI: ${scanSamples[selectedAddress]?.rssi?.let { "$it dBm" } ?: "-"} / Ranging RSSI: ${rssi?.let { "$it dBm" } ?: "N/A"}"

        runOnUiThread {
            binding.distanceText.text = String.format(
                Locale.US,
                "%.3f m",
                smoothedMeters
            )

            binding.rawDistanceText.text = String.format(
                Locale.US,
                "Raw: %.3f m / 5-sample mean: %.3f m / samples: %d",
                rawMeters,
                smoothedMeters,
                sampleCount
            )
        }

        /* Initiator가 받은 값을 Reflector GATT server로 전달 */
        bleCoordinator.sendDistanceToReflector(
            rawMeters = rawMeters,
            smoothedMeters = smoothedMeters,
            sampleCount = sampleCount
        )
    }

    override fun onRangingStopped(role: ChannelSoundingController.Role) {
        log("CS stopped: $role")
        stopEverything(keepLog = true)
    }

    override fun onError(message: String) {
        log("CS 오류: $message")
        finishRecording("CS error")
        csState = "Error"
        updateStatus()

        if (uiRole == UiRole.REFLECTOR) {
            bleCoordinator.notifyReflectorFailure(message)
        } else {
            restoreStartButton()
        }
        // The controller owns its failure stop; release transport on the next main turn.
        csStartHandler.post { stopEverything(keepLog = true) }
    }

    override fun onClosed(reason: Int) {
        log("RangingSession closed: reason=$reason")
        finishRecording("Ranging session closed")
        csStartHandler.post { stopEverything(keepLog = true) }
    }

    private fun restoreStartButton() {
        if (uiRole != UiRole.INITIATOR || !controlReady) return

        runOnUiThread {
            binding.startCsButton.isEnabled = true
        }
    }

    private fun startCsvSession(role: UiRole, peerAddress: String?) {
        if (recording) return
        recording = true
        binding.root.keepScreenOn = true
        csState = "Preparing"
        updateStatus()
        recorder.start(role.name, peerAddress, currentTxState(),
            if (role == UiRole.INITIATOR) scanSamples[peerAddress] else null)
        updatePowerUi()
    }

    private fun finishRecording(reason: String) {
        recording = false
        binding.root.keepScreenOn = false
        if (::recorder.isInitialized) recorder.stop(reason)
    }

    private fun currentTxState(): TxPowerState = if (uiRole == UiRole.REFLECTOR) {
        TxPowerState(if (requestedTxPower == null) "DEFAULT" else "CUSTOM", requestedTxPower, appliedTxPower)
    } else {
        // Peer requested/applied power is not carried in the unchanged GATT protocol.
        TxPowerState(advertised = scanPowers[selectedAddress])
    }

    private fun setupSensorsAndPower() {
        recorder.setImuEnabled(binding.imuSwitch.isChecked)
        recorder.setGpsEnabled(false) // Enable only after the runtime permission check.
        binding.imuSwitch.setOnCheckedChangeListener { _, checked -> recorder.setImuEnabled(checked) }
        binding.gpsSwitch.setOnCheckedChangeListener { _, checked ->
            if (checked) ensureGpsPermission() else recorder.setGpsEnabled(false)
        }
        binding.txPowerSlider.max = BlePeerCoordinator.maxAdvertisingTxDbm + 22
        binding.txRangeText.text = "Default · -21 … +${BlePeerCoordinator.maxAdvertisingTxDbm} dBm"
        binding.txPowerSlider.progress = 0
        binding.txPowerSlider.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(bar: SeekBar?, progress: Int, fromUser: Boolean) {
                if (!fromUser || advertisingBusy || bleConnected || recording) return
                requestedTxPower = if (progress == 0) null else progress - 22
                appliedTxPower = null
                recorder.setTx(currentTxState())
                log("TX_POWER mode=${if (requestedTxPower == null) "DEFAULT" else "CUSTOM"} requested=${requestedTxPower ?: "DEFAULT"}", "BLE")
                updatePowerUi()
            }
            override fun onStartTrackingTouch(bar: SeekBar?) = Unit
            override fun onStopTrackingTouch(bar: SeekBar?) = Unit
        })
    }

    private fun ensureGpsPermission() {
        if (!binding.gpsSwitch.isChecked) return
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED) {
            recorder.setGpsEnabled(true)
        } else {
            gpsPermissionLauncher.launch(arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION))
        }
    }

    private fun updatePowerUi() {
        val requested = requestedTxPower?.let { if (it > 0) "+$it dBm" else "$it dBm" } ?: "Default"
        binding.txRequestedText.text = "Requested: $requested"
        binding.txAppliedText.text = "Applied: ${appliedTxPower?.let { "$it dBm" } ?: "-"}"
        binding.txPowerSlider.contentDescription = "Advertising TX Power: $requested"
        val editable = !advertisingBusy && !bleConnected && !recording
        binding.txPowerSlider.isEnabled = editable
        binding.advertiseButton.isEnabled = editable
        binding.scanButton.isEnabled = !bleConnected && !recording
        binding.pairButton.isEnabled = !bleConnected && !recording
        binding.deviceList.isEnabled = !bleConnected && !recording
    }

    private fun updateStatus() {
        if (!::binding.isInitialized) return
        binding.connectionStatusText.text = "Role: $uiRole\nBLE: $bleState\nCS: $csState"
    }

    private fun log(message: String, source: String = "CS", persist: Boolean = true) {
        val time = SimpleDateFormat(
            "HH:mm:ss.SSS",
            Locale.US
        ).format(Date())

        val line = "$time  $message"

        if (persist && ::recorder.isInitialized) recorder.event(source, message, uiRole.name)
        runOnUiThread {
            while (logLines.size >= 100) logLines.removeFirst()
            logLines.addLast(line)
            if (::binding.isInitialized) {
                binding.statusText.text = logLines.joinToString("\n")
                binding.statusText.post {
                    val height = binding.statusText.layout?.height ?: 0
                    binding.statusText.scrollTo(0, (height - binding.statusText.height).coerceAtLeast(0))
                }
            }
        }
    }

    private fun disableAllActions() {
        binding.applyRoleButton.isEnabled = false
        binding.scanButton.isEnabled = false
        binding.pairButton.isEnabled = false
        binding.startCsButton.isEnabled = false
        binding.advertiseButton.isEnabled = false
        binding.stopButton.isEnabled = false
        binding.imuSwitch.isEnabled = false
        binding.gpsSwitch.isEnabled = false
        binding.txPowerSlider.isEnabled = false
    }
}

