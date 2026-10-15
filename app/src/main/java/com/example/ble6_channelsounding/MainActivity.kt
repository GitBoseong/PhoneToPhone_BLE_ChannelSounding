package com.example.ble6_channelsounding

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.View
import android.widget.ArrayAdapter
import android.widget.SeekBar
import androidx.activity.result.contract.ActivityResultContracts
import androidx.annotation.RequiresApi
import androidx.appcompat.app.AlertDialog
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
    private var transportStopped = true

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
    override fun onBlePhy(state: BlePhyState) {
        runOnUiThread {
            if (transportStopped && state.actual != null) return@runOnUiThread
            recorder.setPhy(state)
            binding.phyStatusText.text = "실제 PHY: TX=${state.tx ?: "-"} / RX=${state.rx ?: "-"}"
            binding.phySelectButton.text = "BLE PHY 선택 · 요청: ${state.requested ?: "Default"}"
        }
    }

    private var connectionRssi: Int? = null

    private fun updateRssiUi() {
        binding.rssiText.text = "Connection RSSI: ${connectionRssi?.let { "$it dBm" } ?: "-"}"
        binding.rssiColorBar.setRssi(connectionRssi)
    }

    override fun onConnectionRssi(address: String, rssi: Int?, timestamp: Long) {
        runOnUiThread {
            if (transportStopped && rssi != null) return@runOnUiThread
            connectionRssi = rssi
            if (rssi != null) {
                recorder.connectionRssi(ConnectionRssiSample(rssi, timestamp))
                log("Connection RSSI=$rssi dBm peer=$address", "BLE")
            }
            updateRssiUi()
        }
    }
    private var controlReady = false

    /* Reflector가 완전히 준비된 뒤 Initiator를 약간 늦게 시작하여 역할 반전 안정성 향상 */
    private val csStartHandler = Handler(Looper.getMainLooper())
    private var measurementPhase = "IDLE"
    private var sessionResultCount = 0
    private var reflectorCsActive = false
    private var measurementPeer: String? = null
    private var measurementHardware = false
    private var measurementSecurity = 1

    private fun hasActiveMeasurement() = recording && !stopping && uiRole == UiRole.INITIATOR &&
        measurementPeer != null && selectedAddress == measurementPeer

    private fun startMeasurementAttempt() {
        if (!hasActiveMeasurement()) return
        binding.startCsButton.isEnabled = false
        if (!bleConnected || !controlReady) {
            measurementPhase = "CONNECTING"
            csState = "Waiting for GATT ready"
            bleCoordinator.connectAndPairSelected()
            updateStatus()
            return
        }
        val controller = csController
        if (controller == null || Build.VERSION.SDK_INT < 36) {
            onError("CS controller unavailable")
            return
        }
        sessionResultCount = 0
        if (measurementHardware) {
            measurementPhase = "STARTING"
            csState = "Starting CS"
            controller.startInitiator(measurementPeer!!.uppercase(Locale.US), measurementSecurity)
        } else {
            measurementPhase = "READY_WAIT"
            csState = "Waiting for Reflector READY"
            bleCoordinator.requestStartFromReflector()
        }
        updateStatus()
    }

    private val logLines = ArrayDeque<String>()
    private val logRenderHandler = Handler(Looper.getMainLooper())
    private var logRenderPending = false
    private val renderLog = Runnable {
        logRenderPending = false
        if (!isDestroyed && ::binding.isInitialized) {
            binding.statusText.text = logLines.joinToString("\n")
            binding.latestLogText.text = "최신 로그: ${logLines.firstOrNull() ?: "-"}"
        }
    }

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
        log("DEVICE model=${Build.MODEL} sdk=${Build.VERSION.SDK_INT} release=${Build.VERSION.RELEASE} " +
            "securityPatch=${Build.VERSION.SECURITY_PATCH} fingerprint=${Build.FINGERPRINT}", "APP")

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
        binding.phySelectButton.setOnClickListener {
            val choices = BlePhyChoice.entries
            AlertDialog.Builder(this)
                .setTitle("BLE 연결 PHY 선택")
                .setItems(choices.map { it.label }.toTypedArray()) { _, index ->
                    bleCoordinator.selectPhy(choices[index])
                }
                .setNegativeButton("취소", null)
                .show()
        }
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
            if (uiRole == UiRole.INITIATOR) {
                binding.startCsButton.isEnabled = true
            }
        }

        binding.peerTypeGroup.setOnCheckedChangeListener { _, _ ->
            val isNordic = binding.nordicPeerRadio.isChecked
            binding.securityLevelContainer.visibility = if (isNordic) View.VISIBLE else View.GONE
            log("Target Reflector 모드: ${if (isNordic) "Nordic / HW Reflector" else "Phone CS"}")
        }

        binding.applyRoleButton.setOnClickListener {
            applySelectedRole()
        }

        binding.scanButton.setOnClickListener {
            transportStopped = false
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
            transportStopped = false
            controlReady = false
            binding.startCsButton.isEnabled = false
            bleState = "Connecting / Pairing"
            updateStatus()
            bleCoordinator.connectAndPairSelected()
        }

        binding.startCsButton.setOnClickListener {
            transportStopped = false
            val address = selectedAddress ?: run {
                log("선택된 장치가 없습니다.")
                return@setOnClickListener
            }

            binding.startCsButton.isEnabled = false
            startCsvSession(UiRole.INITIATOR, address)

            measurementPeer = address
            measurementHardware = binding.nordicPeerRadio.isChecked || bleCoordinator.isHardwareReflector
            measurementSecurity = if (binding.secLevel0Radio.isChecked) 0 else 1
            measurementPhase = "IDLE"
            startMeasurementAttempt()
        }

        binding.advertiseButton.setOnClickListener {
            transportStopped = false
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
        transportStopped = true
        connectionRssi = null
        updateRssiUi()
        measurementPhase = "IDLE"
        measurementPeer = null
        reflectorCsActive = false
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
        logRenderHandler.removeCallbacks(renderLog)
        logRenderPending = false
        super.onDestroy()
    }

    override fun onLog(message: String) {
        log(message, "BLE")
    }

    @SuppressLint("MissingPermission")
    override fun onScanDevice(device: BluetoothDevice, name: String, rssi: Int, txPower: Int?, timestamp: Long) {
        runOnUiThread {
            if (transportStopped) return@runOnUiThread
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
                updateRssiUi()
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
            if (transportStopped) return@runOnUiThread
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
            if (transportStopped) return@runOnUiThread
            controlReady = true
            val isHw = bleCoordinator.isHardwareReflector
            bleState = if (isHw) "Connected / Hardware Reflector" else "Connected / GATT ready"
            updateStatus()
            binding.startCsButton.isEnabled = !recording
            if (hasActiveMeasurement() && measurementPhase == "CONNECTING" && address.equals(measurementPeer, true)) {
                log("GATT 준비 완료 → CS 시작", "BLE")
                startMeasurementAttempt()
            }
            val peerType = if (isHw) "하드웨어 Reflector (Nordic 등)" else "페어링 및 제어·거리 채널"
            binding.selectedDeviceText.text = "$peerType 준비 완료: $address"
        }

        val peerTypeLog = if (bleCoordinator.isHardwareReflector) "하드웨어 Reflector" else "Phone CS"
        log("Reflector 준비 완료 ($peerTypeLog): $address")
    }

    override fun onStartReflectorRequested(initiatorAddress: String) {
        if (uiRole != UiRole.REFLECTOR) return

        runOnUiThread {
            if (transportStopped) return@runOnUiThread
            if (reflectorCsActive) { log("Duplicate START ignored", "BLE"); return@runOnUiThread }
            reflectorCsActive = true
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
        runOnUiThread {
            if (!hasActiveMeasurement() || measurementPhase != "READY_WAIT" ||
                !reflectorAddress.equals(measurementPeer, true)) return@runOnUiThread
            measurementPhase = "STARTING"
            csStartHandler.removeCallbacksAndMessages(null)
            log("Reflector READY 수신 → 800ms 후 CS 시작", "BLE")
            csStartHandler.postDelayed({
                if (!hasActiveMeasurement()) return@postDelayed
                if (!bleConnected || !controlReady) {
                    onError("GATT unavailable after READY")
                    return@postDelayed
                }
                if (Build.VERSION.SDK_INT >= 36) csController?.startInitiator(
                    measurementPeer!!.uppercase(Locale.US), measurementSecurity)
            }, 800L)
        }
    }

    override fun onReflectorError(message: String) {
        log("BLE 오류: $message")
        runOnUiThread {
            stopEverything(keepLog = true)
        }
    }

    override fun onRemoteDistance(
        rawMeters: Double,
        smoothedMeters: Double,
        sampleCount: Int
    ) {
        if (uiRole != UiRole.REFLECTOR || !recording || transportStopped) return

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
            log("Peer 연결 해제; 자동 재연결하지 않음", "BLE")
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
        if (uiRole == UiRole.INITIATOR && hasActiveMeasurement()) measurementPhase = "MEASURING"
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
        if (uiRole != UiRole.INITIATOR || !hasActiveMeasurement() ||
            measurementPhase !in listOf("STARTING", "MEASURING")) return
        sessionResultCount++
        measurementPhase = "MEASURING"
        csState = "Measuring"

        recorder.distance(DistanceSample(rawMeters, smoothedMeters, sampleCount,
            timestampEpochMs, rssi, "LOCAL_RANGING"))
        log("RANGING RSSI=${rssi?.let { "$it dBm" } ?: "N/A"} distance=$rawMeters smoothed=$smoothedMeters samples=$sampleCount", "CS")
        runOnUiThread { updateRssiUi() }

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
        if (transportStopped) return
        log("CS stopped: role=$role results=$sessionResultCount; 모든 수집/연결 중단", "CS")
        stopEverything(keepLog = true)
        csState = "Stopped"
        updateStatus()
    }

    override fun onError(message: String) {
        log("CS 오류: $message", "CS")
        if (uiRole == UiRole.REFLECTOR && !transportStopped)
            bleCoordinator.notifyReflectorFailure(message)
        stopEverything(keepLog = true)
        csState = "Error"
        updateStatus()
    }

    override fun onClosed(reason: Int) {
        if (transportStopped || !recording) return // Ignore completion of an earlier teardown.
        val reasonText = if (Build.VERSION.SDK_INT >= 36)
            ChannelSoundingController.reasonToText(reason) else "reason=$reason"
        log("RangingSession closed: $reasonText; 모든 수집/연결 중단", "CS")
        stopEverything(keepLog = true)
        csState = "Closed ($reasonText)"
        updateStatus()
    }

    private fun restoreStartButton() {
        if (uiRole != UiRole.INITIATOR) return

        runOnUiThread {
            if (!recording && !stopping && selectedAddress != null &&
                (Build.VERSION.SDK_INT < 36 || csController?.isClosing != true)) {
                binding.startCsButton.isEnabled = true
            }
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
        // Preserve discovery/permission/start failures even before a CSV session exists.
        Log.i("PhoneCS", "source=$source role=$uiRole $message")
        val time = SimpleDateFormat(
            "HH:mm:ss.SSS",
            Locale.US
        ).format(Date())

        val line = "$time  $message"

        if (persist && ::recorder.isInitialized) recorder.event(source, message, uiRole.name)
        runOnUiThread {
            if (isDestroyed) return@runOnUiThread
            while (logLines.size >= 500) logLines.removeLast()
            logLines.addFirst(line)
            // Reflowing all 500 lines on every scan callback can starve touch input on Wear OS.
            if (!logRenderPending) {
                logRenderPending = true
                logRenderHandler.postDelayed(renderLog, 500L)
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

