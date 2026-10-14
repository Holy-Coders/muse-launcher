package dev.puck

import android.annotation.SuppressLint
import android.app.*
import android.bluetooth.*
import android.bluetooth.le.*
import android.content.Intent
import android.content.pm.PackageManager
import android.os.*
import android.Manifest
import android.util.Base64
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/** Visible, ten-minute BLE setup window. Never captures audio or executes device actions. */
@SuppressLint("MissingPermission")
class MusePairingService : Service() {
    companion object {
        const val PAIR = "dev.puck.MUSE_PAIR"
        const val STOP = "dev.puck.MUSE_STOP_PAIRING"
        private val SERVICE = UUID.fromString("7fdd3d1c-38ea-46cf-8b46-314ecf5f240c")
        private val RX = UUID.fromString("4d593029-28a2-4a6e-a1f0-3c2d5e8f9b01")
        private val TX = UUID.fromString("d75dc4ca-7b2b-4e9c-8f0a-1d2e3f4a5b6c")
        private val CCC = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")
    }
    private lateinit var runtime: MuseRuntime
    private var adapter: BluetoothAdapter? = null
    private var server: BluetoothGattServer? = null
    private var advertiser: BluetoothLeAdvertiser? = null
    @Volatile private var peer: BluetoothDevice? = null
    @Volatile private var subscribed = false
    @Volatile var mtu = 23
        private set
    @Volatile private var latch: CountDownLatch? = null
    @Volatile private var notificationOk = false
    private val tx = BluetoothGattCharacteristic(TX, BluetoothGattCharacteristic.PROPERTY_NOTIFY, BluetoothGattCharacteristic.PERMISSION_READ)
    private val handler = Handler(Looper.getMainLooper())
    private var stopping = false
    private val advertisingCallback = object : AdvertiseCallback() {
        override fun onStartSuccess(settingsInEffect: AdvertiseSettings) {
            runtime.pairingOpen.value = true
            runtime.setupStatus.value = "Pairing open: ${runtime.vault.bleName}. In Muse on another phone, Devices → Add a device."
        }
        override fun onStartFailure(errorCode: Int) {
            runtime.setupStatus.value = "Bluetooth advertising failed ($errorCode). This phone may not support gadget mode."
            stopPairing()
        }
    }
    private val callback = object : BluetoothGattServerCallback() {
        override fun onServiceAdded(status: Int, service: BluetoothGattService) {
            if (status == BluetoothGatt.GATT_SUCCESS) beginAdvertising()
            else { runtime.setupStatus.value = "Bluetooth service unavailable"; stopPairing() }
        }
        override fun onConnectionStateChange(device: BluetoothDevice, status: Int, newState: Int) {
            if (newState == BluetoothProfile.STATE_CONNECTED) {
                if (peer != null && peer != device) { server?.cancelConnection(device); return }
                peer = device; subscribed = false; mtu = 23
            } else if (peer == device) {
                peer = null; subscribed = false; latch?.countDown(); runtime.peerDisconnected()
            }
        }
        override fun onMtuChanged(device: BluetoothDevice, value: Int) { if (peer == device) mtu = value.coerceIn(23, 517) }
        override fun onCharacteristicWriteRequest(device: BluetoothDevice, requestId: Int, characteristic: BluetoothGattCharacteristic, preparedWrite: Boolean, responseNeeded: Boolean, offset: Int, value: ByteArray) {
            val accepted = peer == device && characteristic.uuid == RX && !preparedWrite && offset == 0 && value.size <= 512 && subscribed
            if (accepted) runtime.packet(value.copyOf())
            if (responseNeeded) server?.sendResponse(device, requestId, if (accepted) BluetoothGatt.GATT_SUCCESS else BluetoothGatt.GATT_REQUEST_NOT_SUPPORTED, 0, null)
        }
        override fun onDescriptorWriteRequest(device: BluetoothDevice, requestId: Int, descriptor: BluetoothGattDescriptor, preparedWrite: Boolean, responseNeeded: Boolean, offset: Int, value: ByteArray) {
            val valid = peer == device && descriptor.uuid == CCC && !preparedWrite && offset == 0
            if (valid) subscribed = value.contentEquals(BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE)
            if (responseNeeded) server?.sendResponse(device, requestId, if (valid) BluetoothGatt.GATT_SUCCESS else BluetoothGatt.GATT_REQUEST_NOT_SUPPORTED, 0, null)
        }
        override fun onDescriptorReadRequest(device: BluetoothDevice, requestId: Int, offset: Int, descriptor: BluetoothGattDescriptor) {
            val value = if (subscribed) BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE else BluetoothGattDescriptor.DISABLE_NOTIFICATION_VALUE
            server?.sendResponse(device, requestId, if (offset == 0) BluetoothGatt.GATT_SUCCESS else BluetoothGatt.GATT_INVALID_OFFSET, 0, value)
        }
        override fun onNotificationSent(device: BluetoothDevice, status: Int) {
            notificationOk = status == BluetoothGatt.GATT_SUCCESS; latch?.countDown()
        }
    }
    override fun onCreate() {
        super.onCreate()
        runtime = MuseRuntime.get(this)
        runtime.bluetooth = this
        getSystemService(NotificationManager::class.java).createNotificationChannel(NotificationChannel("muse_pairing", "Muse device pairing", NotificationManager.IMPORTANCE_LOW))
        val stop = PendingIntent.getService(this, 1, Intent(this, MusePairingService::class.java).setAction(STOP), PendingIntent.FLAG_IMMUTABLE)
        startForeground(32, Notification.Builder(this, "muse_pairing").setSmallIcon(R.drawable.puck_icon)
            .setContentTitle("Muse Launcher Muse pairing").setContentText("Bluetooth setup window open; microphone off")
            .setOngoing(true).addAction(Notification.Action.Builder(null, "Stop pairing", stop).build()).build())
    }
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == STOP) { stopPairing(); return START_NOT_STICKY }
        val permitted = Build.VERSION.SDK_INT < 31 ||
            (checkSelfPermission(Manifest.permission.BLUETOOTH_ADVERTISE) == PackageManager.PERMISSION_GRANTED && checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED)
        if (!permitted) { runtime.setupStatus.value = "Bluetooth permission is required for pairing"; stopPairing(); return START_NOT_STICKY }
        adapter = getSystemService(BluetoothManager::class.java).adapter
        if (adapter?.isEnabled != true || adapter?.isMultipleAdvertisementSupported != true) {
            runtime.setupStatus.value = "Enable Bluetooth; gadget advertising must be supported by this phone"
            stopPairing(); return START_NOT_STICKY
        }
        runtime.activatePairing()
        handler.postDelayed({ stopPairing() }, 10 * 60 * 1000L)
        return START_NOT_STICKY
    }
    fun advertise() {
        if (stopping) return
        val bluetooth = adapter ?: return
        runtime.vault.originalBluetoothName()?.let { bluetooth.name = it; runtime.vault.clearedBluetoothName() }
        runtime.vault.rememberBluetoothName(bluetooth.name ?: "Jelly Star")
        if (!bluetooth.setName(runtime.vault.bleName)) {
            runtime.setupStatus.value = "Could not set the temporary pairing name"; stopPairing(); return
        }
        server = getSystemService(BluetoothManager::class.java).openGattServer(this, callback)
        val service = BluetoothGattService(SERVICE, BluetoothGattService.SERVICE_TYPE_PRIMARY)
        service.addCharacteristic(BluetoothGattCharacteristic(RX, BluetoothGattCharacteristic.PROPERTY_WRITE or BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE, BluetoothGattCharacteristic.PERMISSION_WRITE))
        tx.addDescriptor(BluetoothGattDescriptor(CCC, BluetoothGattDescriptor.PERMISSION_READ or BluetoothGattDescriptor.PERMISSION_WRITE))
        service.addCharacteristic(tx)
        if (server?.addService(service) != true) { runtime.setupStatus.value = "GATT server could not start"; stopPairing() }
    }
    private fun beginAdvertising() {
        if (stopping) return
        advertiser = adapter?.bluetoothLeAdvertiser
        val data = AdvertiseData.Builder().addServiceUuid(ParcelUuid(SERVICE)).addManufacturerData(0xffff, byteArrayOf(0)).build()
        val response = AdvertiseData.Builder().setIncludeDeviceName(true).build()
        advertiser?.startAdvertising(AdvertiseSettings.Builder().setConnectable(true).setAdvertiseMode(AdvertiseSettings.ADVERTISE_MODE_LOW_LATENCY).setTimeout(0).build(), data, response, advertisingCallback)
    }
    @Synchronized fun sendPacket(data: ByteArray): Boolean {
        val device = peer ?: return false
        val gatt = server ?: return false
        if (!subscribed || stopping || data.size > minOf(mtu - 3, 160)) return false
        val wait = CountDownLatch(1); latch = wait; notificationOk = false
        val submitted = if (Build.VERSION.SDK_INT >= 33) gatt.notifyCharacteristicChanged(device, tx, false, data) == BluetoothStatusCodes.SUCCESS
            else { tx.value = data; gatt.notifyCharacteristicChanged(device, tx, false) }
        val done = submitted && wait.await(5, TimeUnit.SECONDS) && notificationOk
        latch = null
        return done
    }
    fun disconnectPeer() { peer?.let { server?.cancelConnection(it) } }
    fun stopPairing() {
        handler.post {
            if (stopping) return@post
            stopping = true
            handler.removeCallbacksAndMessages(null)
            advertiser?.stopAdvertising(advertisingCallback)
            disconnectPeer(); server?.close(); server = null; peer = null; latch?.countDown()
            runtime.vault.originalBluetoothName()?.let { name ->
                if (adapter?.setName(name) == true) runtime.vault.clearedBluetoothName()
            }
            runtime.pairingStopped(); runtime.bluetooth = null
            stopForeground(STOP_FOREGROUND_REMOVE); stopSelf()
        }
    }
    override fun onDestroy() { if (!stopping) stopPairing(); super.onDestroy() }
    override fun onBind(intent: Intent?) = null
}
