package com.example.carcamerasandlightsbluetooth.data.bluetooth

import android.Manifest.permission
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothDevice.BOND_BONDED
import android.bluetooth.BluetoothDevice.BOND_BONDING
import android.bluetooth.BluetoothDevice.BOND_NONE
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGatt.GATT_FAILURE
import android.bluetooth.BluetoothGatt.GATT_SUCCESS
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattService
import android.bluetooth.BluetoothProfile
import android.bluetooth.le.BluetoothLeScanner
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanFilter
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.Log
import androidx.core.app.ActivityCompat
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import com.example.carcamerasandlightsbluetooth.utils.runWithPermissionCheck
import com.welie.blessed.supportsIndicate
import com.welie.blessed.supportsNotify
import com.welie.blessed.supportsReading
import com.welie.blessed.supportsWritingWithResponse
import com.welie.blessed.supportsWritingWithoutResponse
import kotlinx.coroutines.Job
import kotlinx.coroutines.Runnable
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.runBlocking
import java.util.UUID

const val START_PACKAGE_SIGNATURE = 's'
const val BORDER_OF_PACKAGE_SIGN = '\n'

class SimpleBleRepository(
    private val context: Context,
    private val serviceToFindUUID: UUID = UUID.fromString("0000ffe0-0000-1000-8000-00805f9b34fb"),
    private val characteristicToFindUUID: UUID = UUID.fromString("0000ffe1-0000-1000-8000-00805f9b34fb")
) {
    enum class BleStatus {
        NOT_CONNECTED, CONNECTED, CONNECTED_NOTIFICATIONS
    }

    private var statusLiveData = MutableLiveData(BleStatus.NOT_CONNECTED)
    val statusToObserve: LiveData<BleStatus> = statusLiveData
    private var bleHandler = Handler(Looper.getMainLooper())
    private var scanJob: Job? = null
    private var discoverServicesRunnable: Runnable? = null
    private var controllerDevice: BluetoothDevice? = null
    private var adapter: BluetoothAdapter = BluetoothAdapter.getDefaultAdapter()
    private var scanner: BluetoothLeScanner = adapter.bluetoothLeScanner
    private var currentGattProfile: BluetoothGatt? = null
    private var serviceToCommunicateWith: BluetoothGattService? = null
    private var characteristicToWriteTo: BluetoothGattCharacteristic? = null
    private var characteristicToNotifyOf: BluetoothGattCharacteristic? = null
    private val scanSettings =
        ScanSettings.Builder().setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
            .setCallbackType(ScanSettings.CALLBACK_TYPE_ALL_MATCHES)
            .setMatchMode(ScanSettings.MATCH_MODE_STICKY)
            .setNumOfMatches(ScanSettings.MATCH_NUM_ONE_ADVERTISEMENT).setReportDelay(3L).build()


    suspend fun startRawScan(): Flow<List<ScanResult>> = callbackFlow {
        val scanCallback: ScanCallback = object : ScanCallback() {
            override fun onScanResult(callbackType: Int, result: ScanResult) {
                trySend(listOf(result)).isSuccess
            }

            override fun onBatchScanResults(results: List<ScanResult?>?) {
                if (!results.isNullOrEmpty()) {
                    trySend(results.mapNotNull { scanResult ->
                        scanResult!!
                    }).isSuccess
                }
            }

            override fun onScanFailed(errorCode: Int) {
                Log.d("SimpleBle", "scan failed")
            }
        }
        scanJob?.cancel()
        scanRunPermissionSafe(filter = null, scanCallback)
        awaitClose {
            stopScan()
        }
    }

    suspend fun startScanByAddress(macToScan: String): Flow<List<ScanResult>> = callbackFlow {
        val scanCallback: ScanCallback = object : ScanCallback() {
            override fun onScanResult(callbackType: Int, result: ScanResult) {
                trySend(listOf(result)).isSuccess
            }

            override fun onBatchScanResults(results: List<ScanResult?>?) {
                if (!results.isNullOrEmpty()) {
                    trySend(results.mapNotNull { scanResult ->
                        scanResult!!
                    }).isSuccess
                }
            }

            override fun onScanFailed(errorCode: Int) {
                Log.d("SimpleBle", "scan failed")
            }
        }
        scanJob?.cancel()
        scanRunPermissionSafe(listOf(getFilterByAddress(macToScan)), scanCallback)
        awaitClose {
            stopScan()
        }
    }

    private fun getFilterByAddress(deviceAddress: String): ScanFilter {
        return ScanFilter.Builder().setDeviceAddress(deviceAddress).build()
    }

    fun stopScan() {
        scanJob?.cancel()
    }

    suspend fun connectTo(device: BluetoothDevice): Flow<ByteArray> = callbackFlow {
        val connectionStateCallback = object : BluetoothGattCallback() {

            override fun onServicesDiscovered(gattProfile: BluetoothGatt?, status: Int) {
                super.onServicesDiscovered(gattProfile, status)

                if (status == GATT_FAILURE) {
                    Log.d("SimpleBle", "Service discovery failed")
                    gattProfile?.disconnect()
                }

                gattProfile!!.services.forEach { gattService ->
                    Log.d("SimpleBle", "discovered ${gattService.uuid} ")
                    if (gattService.uuid == serviceToFindUUID) {
                        serviceToCommunicateWith = gattService
                        gattService.characteristics.forEach { characteristic ->
                            if (characteristic.uuid == characteristicToFindUUID) {
                                subscribeForNotifyAndWrite(gattProfile, characteristic)
                            }
                        }
                    }
                }
            }

            override fun onCharacteristicChanged(
                gatt: BluetoothGatt?, characteristic: BluetoothGattCharacteristic?
            ) {
                if (characteristic?.value != null) {
                    trySend(characteristic.value!!)
                }
            }

            override fun onConnectionStateChange(
                gatt: BluetoothGatt, status: Int, newState: Int
            ) {
                if (status == GATT_SUCCESS) {
                    if (newState == BluetoothProfile.STATE_CONNECTED) {
                        if (this@SimpleBleRepository.statusLiveData.value != BleStatus.CONNECTED) {
                            this@SimpleBleRepository.statusLiveData.postValue(BleStatus.CONNECTED)
                        }
                        currentGattProfile = gatt
                        val bondState: Int? = controllerDevice?.getBondState()
                        if (bondState == BOND_NONE || bondState == BOND_BONDED) {
                            var delayWhenBonded = 0
                            val delay = if (bondState == BOND_BONDED) delayWhenBonded else 0
                            discoverServicesRunnable = Runnable {
                                Log.d("SimpleBle", "discover ${gatt.device} delay $delay ms")
                                val result = gatt.discoverServices()
                                if (!result) {
                                    Log.e("SimpleBle", "discoverServices failed to start")
                                }
                                discoverServicesRunnable = null
                            }
                            bleHandler.postDelayed(discoverServicesRunnable!!, delay.toLong())
                        } else if (bondState == BOND_BONDING) {
                            Log.d("SimpleBle", "waiting for bonding to complete")
                        }
                    } else {
                        if (this@SimpleBleRepository.statusLiveData.value != BleStatus.NOT_CONNECTED)
                            this@SimpleBleRepository.statusLiveData.postValue(BleStatus.NOT_CONNECTED)
                        serviceToCommunicateWith = null
                        characteristicToNotifyOf = null
                        characteristicToWriteTo = null
                        Log.d("SimpleBle", "${gatt.device.name} is no connected")
                        gatt.close()
                    }
                } else {
                    if (this@SimpleBleRepository.statusLiveData.value != BleStatus.NOT_CONNECTED)
                        this@SimpleBleRepository.statusLiveData.postValue(BleStatus.NOT_CONNECTED)
                    Log.d("SimpleBle", "Failed to connect")
                    gatt.close()
                    gatt.disconnect()
                    gatt.connect()
                }
            }
        }
        controllerDevice = device
        gattOperationRunPermissionSafe({currentGattProfile=
            controllerDevice!!.connectGatt(
                context,
                false,
                connectionStateCallback
            )}
        )
        awaitClose {
            stopScan()
        }
    }

    fun sendBytes(data: ByteArray) {
        if (statusLiveData.value == BleStatus.NOT_CONNECTED) return
        if (currentGattProfile == null && characteristicToWriteTo == null) return
        val bytesToSend: ByteArray = byteArrayOf(
            BORDER_OF_PACKAGE_SIGN.code.toByte(), START_PACKAGE_SIGNATURE.code.toByte()
        ) + data + BORDER_OF_PACKAGE_SIGN.code.toByte()
        characteristicToWriteTo!!.setValue(bytesToSend)
        characteristicToWriteTo!!.writeType = BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE
        currentGattProfile!!.writeCharacteristic(characteristicToWriteTo)
        Log.d("SimpleBle", "send ${bytesToSend.toList()}")

    }

    fun showGattContents(profile: BluetoothGatt): String {
        var outLog = ""
        profile.services.forEach { gattService ->
            outLog += "Discovered ${gattService.uuid} service:\n"
            gattService.characteristics.forEach { characteristic ->
                outLog += " Characteristic ${characteristic.uuid}\n  "
                if (characteristic.supportsReading()) outLog += "read |"
                if (characteristic.supportsWritingWithResponse()) outLog += "write with response |"
                if (characteristic.supportsWritingWithoutResponse()) outLog += "write NO response |"
                if (characteristic.supportsIndicate()) outLog += "indicate |"
                if (characteristic.supportsNotify()) outLog += "notify |"
                outLog += " \n"
            }
        }
        return outLog
    }


    fun subscribeForNotifyAndWrite(
        gattProfile: BluetoothGatt, characteristic: BluetoothGattCharacteristic
    ) {
        runBlocking() {
            val subscribe: () -> Boolean =
                { gattProfile.setCharacteristicNotification(characteristic, true) }
            if (characteristic.supportsNotify()) {
                characteristicToNotifyOf = characteristic
                if (ActivityCompat.checkSelfPermission(
                        context,
                        permission.BLUETOOTH_CONNECT
                    ) != PackageManager.PERMISSION_GRANTED
                ) {
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                        runWithPermissionCheck(
                            run(subscribe),
                            permission.BLUETOOTH_CONNECT,
                            context
                        )
                    } else {
                        runWithPermissionCheck(
                            run(subscribe),
                            permission.ACCESS_COARSE_LOCATION,
                            context
                        )
                    }
                }

                if (this@SimpleBleRepository.statusLiveData.value != BleStatus.CONNECTED_NOTIFICATIONS)
                    this@SimpleBleRepository.statusLiveData.postValue(BleStatus.CONNECTED_NOTIFICATIONS)
            }
            if (characteristic.supportsWritingWithResponse() || characteristic.supportsWritingWithoutResponse()) {
                characteristicToWriteTo = characteristic
            }
        }
    }

    private suspend fun scanRunPermissionSafe(
        filter: List<ScanFilter>? = null,
        scanCallback: ScanCallback
    ) {
        Log.d("SimpleBle", "in scan permission ")
        val scan: () -> Unit = { scanner.startScan(filter, scanSettings, scanCallback) }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            if ((ActivityCompat.checkSelfPermission(
                    context,
                    permission.BLUETOOTH_SCAN
                ) != PackageManager.PERMISSION_GRANTED
                        )
            ){
                Log.d("SimpleBle", "in scan runWithPermissionCheck ")
                runWithPermissionCheck(run(scan), permission.BLUETOOTH_SCAN, context)
            }
            else{
                Log.d("SimpleBle", "in scan run raw ")
                run(scan)
            }
        } else {
            Log.d("SimpleBle", "in scan run old ACCESS_COARSE_LOCATION")
            runWithPermissionCheck(run(scan), permission.ACCESS_COARSE_LOCATION, context)
            scanner.startScan(filter, scanSettings, scanCallback)
        }
    }

    private suspend fun gattOperationRunPermissionSafe(action: () -> Unit) {
        runBlocking {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                runWithPermissionCheck(
                    run(action),
                    permission.BLUETOOTH_CONNECT,
                    context
                )
            } else {
                runWithPermissionCheck(
                    run(action),
                    permission.ACCESS_COARSE_LOCATION,
                    context
                )
            }
        }
    }
}