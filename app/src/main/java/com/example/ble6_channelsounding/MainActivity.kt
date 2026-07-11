package com.example.ble6_channelsounding

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.view.View
import android.widget.ArrayAdapter
import androidx.activity.result.contract.ActivityResultContracts
import androidx.annotation.RequiresApi
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
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

    private enum class UiRole { INITIATOR, REFLECTOR }

    private var uiRole = UiRole.INITIATOR

    private val foundDevices = linkedMapOf<String, BluetoothDevice>()
    private val deviceLabels = mutableListOf<String>()
    private lateinit var deviceAdapter: ArrayAdapter<String>

    private var selectedAddress: String? = null
    private var controlReady = false

    private val logLines = ArrayDeque<String>()

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { grants ->
        val denied = grants.filterValues { !it }.keys

        if (denied.isEmpty()) {
            log("모든 권한 허용 완료")
            initChannelSounding()
        } else {
            log("권한 거부: $denied")
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        if (Build.VERSION.SDK_INT < 36) {
            binding.capabilityText.text = "Android 16 / API 36 이상 필요"
            disableAllActions()
            return
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
            foundDevices[address]?.let(bleCoordinator::selectDevice)
            binding.selectedDeviceText.text = "선택 장치: $address"
        }

        binding.applyRoleButton.setOnClickListener {
            applySelectedRole()
        }

        binding.scanButton.setOnClickListener {
            foundDevices.clear()
            deviceLabels.clear()
            deviceAdapter.notifyDataSetChanged()

            selectedAddress = null
            controlReady = false
            binding.startCsButton.isEnabled = false

            bleCoordinator.startScan()
        }

        binding.pairButton.setOnClickListener {
            controlReady = false
            binding.startCsButton.isEnabled = false
            bleCoordinator.connectAndPairSelected()
        }

        binding.startCsButton.setOnClickListener {
            if (!controlReady) {
                log("먼저 연결·페어링·GATT 준비를 완료하세요.")
                return@setOnClickListener
            }

            /*
             * 중복 START 방지.
             * 실패하면 onReflectorError/onError/onClosed에서 다시 활성화합니다.
             */
            binding.startCsButton.isEnabled = false
            bleCoordinator.requestStartFromReflector()
        }

        binding.advertiseButton.setOnClickListener {
            bleCoordinator.startReflectorAdvertising()
        }

        binding.stopButton.setOnClickListener {
            stopEverything()
        }

        requestPermissionsIfNeeded()
        applySelectedRole()
    }

    @RequiresApi(36)
    private fun initChannelSounding() {
        if (csController == null) {
            csController = ChannelSoundingController(this, this)
        }

        csController?.checkCapability()
    }

    private fun requestPermissionsIfNeeded() {
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

        val initiator = uiRole == UiRole.INITIATOR

        binding.initiatorPanel.visibility =
            if (initiator) View.VISIBLE else View.GONE

        binding.reflectorPanel.visibility =
            if (initiator) View.GONE else View.VISIBLE

        binding.distanceText.text =
            if (initiator) "--.-- m" else "Initiator only"

        binding.rawDistanceText.text = if (initiator) {
            "Raw: - / samples: 0"
        } else {
            "Android BLE CS 결과는 Initiator에만 전달됩니다."
        }

        controlReady = false
        binding.startCsButton.isEnabled = false

        log("역할 적용: $uiRole")
    }

    private fun stopEverything(keepLog: Boolean = false) {
        csController?.stop()

        if (::bleCoordinator.isInitialized) {
            /* stopAll() 내부에서 Reflector에 STOP을 한 번만 전송합니다. */
            bleCoordinator.stopAll()
        }

        controlReady = false

        if (::binding.isInitialized) {
            binding.startCsButton.isEnabled = false
        }

        if (!keepLog) {
            log("전체 세션 중지")
        }
    }

    override fun onDestroy() {
        stopEverything(keepLog = true)

        if (::bleCoordinator.isInitialized) {
            bleCoordinator.unregister()
        }

        super.onDestroy()
    }

    override fun onLog(message: String) {
        log(message)
    }

    @SuppressLint("MissingPermission")
    override fun onScanDevice(
        device: BluetoothDevice,
        name: String,
        rssi: Int
    ) {
        val address = device.address.uppercase(Locale.US)

        if (!foundDevices.containsKey(address)) {
            foundDevices[address] = device
            deviceLabels.add("$name\n$address   RSSI=$rssi dBm")

            runOnUiThread {
                deviceAdapter.notifyDataSetChanged()
            }
        }
    }

    override fun onSelectedPeerConnected(address: String) {
        runOnUiThread {
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
        controlReady = true

        runOnUiThread {
            binding.startCsButton.isEnabled = true
            binding.selectedDeviceText.text =
                "페어링 및 제어 채널 준비 완료: $address"
        }

        log("GATT 제어 채널 준비 완료")
    }

    override fun onStartReflectorRequested(initiatorAddress: String) {
        if (uiRole != UiRole.REFLECTOR) return

        log("Initiator로부터 START 수신: $initiatorAddress")

        runOnUiThread {
            val controller = csController

            if (controller == null) {
                val message = "ChannelSoundingController가 초기화되지 않았습니다."
                log(message)
                bleCoordinator.notifyReflectorFailure(message)
                return@runOnUiThread
            }

            bleCoordinator.notifyReflectorPreparing()
            controller.startReflector(
                initiatorAddress.uppercase(Locale.US)
            )
        }
    }

    override fun onStopReflectorRequested() {
        log("Initiator로부터 STOP 수신")

        runOnUiThread {
            csController?.stop()
            bleCoordinator.notifyReflectorStopped()
        }
    }

    override fun onReflectorReady(reflectorAddress: String) {
        if (uiRole != UiRole.INITIATOR) return

        log("Reflector READY 수신 → Initiator CS 시작")

        val controller = csController
        if (controller == null) {
            log("CS 오류: ChannelSoundingController가 초기화되지 않았습니다.")
            restoreStartButton()
            return
        }

        controller.startInitiator(
            reflectorAddress.uppercase(Locale.US)
        )
    }

    override fun onReflectorError(message: String) {
        log("BLE 오류: $message")
        restoreStartButton()
    }

    override fun onDisconnected() {
        controlReady = false

        runOnUiThread {
            binding.startCsButton.isEnabled = false
        }

        log("Peer 연결 해제")
    }

    override fun onCapability(supported: Boolean, detail: String) {
        runOnUiThread {
            binding.capabilityText.text = "CS capability: $detail"
        }

        log("Capability: $supported / $detail")
    }

    override fun onSessionOpened(role: ChannelSoundingController.Role) {
        log("RangingSession opened: $role")

        if (role == ChannelSoundingController.Role.REFLECTOR) {
            /* Reflector 세션이 실제로 열린 다음에만 READY 전송 */
            bleCoordinator.notifyReflectorReady()
        }
    }

    override fun onRangingStarted(role: ChannelSoundingController.Role) {
        log("CS started: $role")
    }

    override fun onDistance(
        rawMeters: Double,
        smoothedMeters: Double,
        sampleCount: Int
    ) {
        runOnUiThread {
            binding.distanceText.text = String.format(
                Locale.US,
                "%.2f m",
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
    }

    override fun onRangingStopped(role: ChannelSoundingController.Role) {
        log("CS stopped: $role")
        restoreStartButton()
    }

    override fun onError(message: String) {
        log("CS 오류: $message")

        if (uiRole == UiRole.REFLECTOR) {
            bleCoordinator.notifyReflectorFailure(message)
        } else {
            restoreStartButton()
        }
    }

    override fun onClosed(reason: Int) {
        log("RangingSession closed: reason=$reason")
        restoreStartButton()
    }

    private fun restoreStartButton() {
        if (uiRole != UiRole.INITIATOR || !controlReady) return

        runOnUiThread {
            binding.startCsButton.isEnabled = true
        }
    }

    private fun log(message: String) {
        val time = SimpleDateFormat(
            "HH:mm:ss.SSS",
            Locale.US
        ).format(Date())

        val line = "$time  $message"

        while (logLines.size >= 80) {
            logLines.removeFirst()
        }

        logLines.addLast(line)

        if (::binding.isInitialized) {
            runOnUiThread {
                binding.statusText.text = logLines.joinToString("\n")
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
    }
}
