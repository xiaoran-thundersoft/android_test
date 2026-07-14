package com.lightsail.camerabletest;

import android.Manifest;
import android.annotation.SuppressLint;
import android.app.Activity;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothGatt;
import android.bluetooth.BluetoothGattCallback;
import android.bluetooth.BluetoothGattCharacteristic;
import android.bluetooth.BluetoothGattDescriptor;
import android.bluetooth.BluetoothManager;
import android.bluetooth.le.BluetoothLeScanner;
import android.bluetooth.le.ScanCallback;
import android.bluetooth.le.ScanResult;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.Gravity;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.TextView;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.zip.CRC32;

/**
 * Standalone receiver for the earphone camera_ble_test eShell command.
 * The protocol is defined in the firmware file camera_ble_test_protocol.md.
 */
public final class MainActivity extends Activity {
    private static final int REQUEST_PERMISSIONS = 100;
    private static final long SCAN_DURATION_MS = 10_000;
    private static final int MAX_IMAGE_SIZE = 512 * 1024;

    private static final UUID SERVICE_UUID = UUID.fromString("4746414e-0000-1000-8000-00805f9b34fb");
    private static final UUID TX_UUID = UUID.fromString("00005555-0000-1000-8000-00805f9b34fb");
    private static final UUID RX_UUID = UUID.fromString("00006666-0000-1000-8000-00805f9b34fb");
    private static final UUID CCCD_UUID = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb");

    private static final int MAGIC = 0xCA71;
    private static final int VERSION = 1;
    private static final int TYPE_START = 1;
    private static final int TYPE_DATA = 2;
    private static final int TYPE_END = 3;
    private static final int ACK_START = 1;
    private static final int ACK_DATA = 2;
    private static final int ACK_END = 3;

    private final Handler handler = new Handler(Looper.getMainLooper());
    private final Map<String, BluetoothDevice> devices = new LinkedHashMap<>();
    private final ArrayList<String> deviceRows = new ArrayList<>();

    private BluetoothAdapter adapter;
    private BluetoothLeScanner scanner;
    private BluetoothGatt gatt;
    private BluetoothGattCharacteristic rxCharacteristic;
    private ArrayAdapter<String> deviceAdapter;
    private TextView statusView;
    private TextView resultView;
    private Button scanButton;
    private boolean scanning;

    private byte[] image;
    private int imageSize;
    private int received;
    private int prnPackets;
    private int packetsSinceAck;
    private int sessionId;
    private long expectedCrc;
    private long startNs;

    @Override
    public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        buildUi();
        BluetoothManager manager = getSystemService(BluetoothManager.class);
        adapter = manager == null ? null : manager.getAdapter();
        if (adapter == null) {
            setStatus("此手机不支持蓝牙。");
        }
    }

    private void buildUi() {
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(32, 32, 32, 32);

        TextView title = new TextView(this);
        title.setText("Camera BLE Test Receiver");
        title.setTextSize(22);
        root.addView(title);

        scanButton = new Button(this);
        scanButton.setText("扫描耳机");
        scanButton.setOnClickListener(v -> ensurePermissionsAndScan());
        root.addView(scanButton);

        ListView list = new ListView(this);
        deviceAdapter = new ArrayAdapter<>(this, android.R.layout.simple_list_item_1, deviceRows);
        list.setAdapter(deviceAdapter);
        list.setOnItemClickListener((parent, view, position, id) -> connect(position));
        root.addView(list, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, 0, 1));

        statusView = textView();
        root.addView(statusView);
        resultView = textView();
        root.addView(resultView);

        setContentView(root);
        setStatus("点击“扫描耳机”，选择设备后等待“测试通道已就绪”。");
    }

    private TextView textView() {
        TextView view = new TextView(this);
        view.setPadding(0, 16, 0, 0);
        view.setTextSize(15);
        view.setGravity(Gravity.START);
        return view;
    }

    private boolean hasPermissions() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            return checkSelfPermission(Manifest.permission.BLUETOOTH_SCAN) == PackageManager.PERMISSION_GRANTED
                    && checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED;
        }
        return checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED;
    }

    private void ensurePermissionsAndScan() {
        if (!hasPermissions()) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                requestPermissions(new String[]{Manifest.permission.BLUETOOTH_SCAN, Manifest.permission.BLUETOOTH_CONNECT},
                        REQUEST_PERMISSIONS);
            } else {
                requestPermissions(new String[]{Manifest.permission.ACCESS_FINE_LOCATION}, REQUEST_PERMISSIONS);
            }
            return;
        }
        startScan();
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == REQUEST_PERMISSIONS && hasPermissions()) {
            startScan();
        } else if (requestCode == REQUEST_PERMISSIONS) {
            setStatus("未授予蓝牙权限，无法扫描或连接。");
        }
    }

    @SuppressLint("MissingPermission")
    private void startScan() {
        if (adapter == null || !adapter.isEnabled()) {
            setStatus("请先打开系统蓝牙。");
            return;
        }
        if (scanning) {
            return;
        }
        scanner = adapter.getBluetoothLeScanner();
        if (scanner == null) {
            setStatus("无法取得 BLE 扫描器。");
            return;
        }
        devices.clear();
        deviceRows.clear();
        deviceAdapter.notifyDataSetChanged();
        scanner.startScan(scanCallback);
        scanning = true;
        scanButton.setEnabled(false);
        setStatus("正在扫描 10 秒；点击列表中的耳机连接。");
        handler.postDelayed(this::stopScan, SCAN_DURATION_MS);
    }

    @SuppressLint("MissingPermission")
    private void stopScan() {
        if (scanning && scanner != null) {
            scanner.stopScan(scanCallback);
        }
        scanning = false;
        scanButton.setEnabled(true);
    }

    private final ScanCallback scanCallback = new ScanCallback() {
        @Override
        @SuppressLint("MissingPermission")
        public void onScanResult(int callbackType, ScanResult result) {
            BluetoothDevice device = result.getDevice();
            if (device == null || devices.containsKey(device.getAddress())) {
                return;
            }
            String name = device.getName();
            String row = String.format(Locale.US, "%s\n%s  RSSI %d", name == null ? "未命名 BLE 设备" : name,
                    device.getAddress(), result.getRssi());
            devices.put(device.getAddress(), device);
            deviceRows.add(row);
            deviceAdapter.notifyDataSetChanged();
        }

        @Override
        public void onScanFailed(int errorCode) {
            stopScan();
            setStatus("扫描失败，错误码=" + errorCode);
        }
    };

    @SuppressLint("MissingPermission")
    private void connect(int position) {
        stopScan();
        if (position < 0 || position >= devices.size()) {
            return;
        }
        BluetoothDevice device = new ArrayList<>(devices.values()).get(position);
        closeGatt();
        setStatus("正在连接 " + device.getAddress());
        gatt = device.connectGatt(this, false, gattCallback, BluetoothDevice.TRANSPORT_LE);
    }

    private final BluetoothGattCallback gattCallback = new BluetoothGattCallback() {
        @Override
        @SuppressLint("MissingPermission")
        public void onConnectionStateChange(BluetoothGatt currentGatt, int status, int newState) {
            runOnUiThread(() -> {
                if (status != BluetoothGatt.GATT_SUCCESS) {
                    setStatus("连接失败，GATT status=" + status);
                    return;
                }
                if (newState == BluetoothGatt.STATE_CONNECTED) {
                    setStatus("已连接，正在发现服务…");
                    currentGatt.discoverServices();
                } else if (newState == BluetoothGatt.STATE_DISCONNECTED) {
                    setStatus("BLE 已断开。");
                    rxCharacteristic = null;
                }
            });
        }

        @Override
        @SuppressLint("MissingPermission")
        public void onServicesDiscovered(BluetoothGatt currentGatt, int status) {
            if (status != BluetoothGatt.GATT_SUCCESS) {
                setStatus("服务发现失败，status=" + status);
                return;
            }
            BluetoothGattCharacteristic tx = currentGatt.getService(SERVICE_UUID) == null ? null
                    : currentGatt.getService(SERVICE_UUID).getCharacteristic(TX_UUID);
            rxCharacteristic = currentGatt.getService(SERVICE_UUID) == null ? null
                    : currentGatt.getService(SERVICE_UUID).getCharacteristic(RX_UUID);
            if (tx == null || rxCharacteristic == null) {
                setStatus("未找到测试服务。请确认耳机已刷入对应固件。");
                return;
            }
            if (!currentGatt.requestMtu(517)) {
                subscribeToImageNotifications(currentGatt, tx);
            }
        }

        @SuppressLint("MissingPermission")
        private void subscribeToImageNotifications(BluetoothGatt currentGatt, BluetoothGattCharacteristic tx) {
            if (!currentGatt.setCharacteristicNotification(tx, true)) {
                setStatus("无法订阅图片 Notification。");
                return;
            }
            BluetoothGattDescriptor cccd = tx.getDescriptor(CCCD_UUID);
            if (cccd == null) {
                setStatus("TX 特征缺少 CCCD。");
                return;
            }
            cccd.setValue(BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE);
            if (!currentGatt.writeDescriptor(cccd)) {
                setStatus("写入 CCCD 失败。");
            }
        }

        @Override
        public void onDescriptorWrite(BluetoothGatt currentGatt, BluetoothGattDescriptor descriptor, int status) {
            if (CCCD_UUID.equals(descriptor.getUuid()) && status == BluetoothGatt.GATT_SUCCESS) {
                setStatus("测试通道已就绪。现在在耳机 eShell 执行：camera_ble_test");
            } else if (CCCD_UUID.equals(descriptor.getUuid())) {
                setStatus("订阅失败，status=" + status);
            }
        }

        @Override
        public void onMtuChanged(BluetoothGatt currentGatt, int mtu, int status) {
            if (status == BluetoothGatt.GATT_SUCCESS) {
                setStatus("协商 MTU=" + mtu + "，正在订阅图片通道…");
            } else {
                setStatus("MTU 协商失败，使用默认 MTU，正在订阅图片通道…");
            }
            if (currentGatt.getService(SERVICE_UUID) != null) {
                BluetoothGattCharacteristic tx = currentGatt.getService(SERVICE_UUID).getCharacteristic(TX_UUID);
                if (tx != null) {
                    subscribeToImageNotifications(currentGatt, tx);
                }
            }
        }

        @Override
        public void onCharacteristicChanged(BluetoothGatt currentGatt, BluetoothGattCharacteristic characteristic) {
            handleNotification(characteristic.getValue());
        }

        @Override
        public void onCharacteristicChanged(BluetoothGatt currentGatt, BluetoothGattCharacteristic characteristic,
                                            byte[] value) {
            handleNotification(value);
        }
    };

    private void handleNotification(byte[] frame) {
        if (frame == null || frame.length < 6 || le16(frame, 0) != MAGIC || frame[2] != VERSION) {
            return;
        }
        int type = frame[3] & 0xFF;
        int incomingSession = le16(frame, 4);
        if (type == TYPE_START) {
            handleStart(frame, incomingSession);
        } else if (type == TYPE_DATA) {
            handleData(frame, incomingSession);
        } else if (type == TYPE_END) {
            handleEnd(frame, incomingSession);
        }
    }

    private void handleStart(byte[] frame, int incomingSession) {
        if (frame.length != 17) {
            return;
        }
        int size = le32(frame, 6);
        if (size <= 0 || size > MAX_IMAGE_SIZE) {
            sendAck(incomingSession, ACK_START, 1, 0, 0);
            setStatus("拒绝图片：大小=" + size + "，上限=" + MAX_IMAGE_SIZE);
            return;
        }
        image = new byte[size];
        imageSize = size;
        received = 0;
        packetsSinceAck = 0;
        sessionId = incomingSession;
        expectedCrc = le32Unsigned(frame, 10);
        prnPackets = frame[14] & 0xFF;
        if (prnPackets == 0) {
            prnPackets = 1;
        }
        startNs = System.nanoTime();
        sendAck(sessionId, ACK_START, 0, 0, 0);
        setStatus("开始接收：" + imageSize + " B，session=" + sessionId + "，PRN=" + prnPackets);
    }

    private void handleData(byte[] frame, int incomingSession) {
        if (image == null || incomingSession != sessionId || frame.length < 12) {
            return;
        }
        int offset = le32(frame, 6);
        int payloadLen = le16(frame, 10);
        if (payloadLen != frame.length - 12 || offset != received || offset + payloadLen > imageSize) {
            sendAck(sessionId, ACK_DATA, 1, received, 0);
            setStatus("图片分包异常：offset=" + offset + " expected=" + received + " len=" + payloadLen);
            return;
        }
        System.arraycopy(frame, 12, image, offset, payloadLen);
        received += payloadLen;
        packetsSinceAck++;
        if (packetsSinceAck >= prnPackets || received == imageSize) {
            sendAck(sessionId, ACK_DATA, 0, received, 0);
            packetsSinceAck = 0;
        }
        if ((received & 0x3FFF) == 0 || received == imageSize) {
            long elapsedMs = Math.max(1, (System.nanoTime() - startNs) / 1_000_000L);
            long rate = received * 1000L / elapsedMs;
            setStatus("接收中：" + received + "/" + imageSize + " B，" + rate + " B/s");
        }
    }

    private void handleEnd(byte[] frame, int incomingSession) {
        if (image == null || incomingSession != sessionId || frame.length != 14) {
            return;
        }
        int declaredSize = le32(frame, 6);
        long declaredCrc = le32Unsigned(frame, 10);
        CRC32 crc32 = new CRC32();
        crc32.update(image, 0, received);
        long actualCrc = crc32.getValue();
        boolean valid = declaredSize == imageSize && received == imageSize && declaredCrc == expectedCrc
                && actualCrc == declaredCrc;
        long elapsedMs = Math.max(1, (System.nanoTime() - startNs) / 1_000_000L);
        long rate = received * 1000L / elapsedMs;
        sendAck(sessionId, ACK_END, valid ? 0 : 1, received, actualCrc);
        String message = String.format(Locale.US,
                "%s：图片=%d B，APP 接收=%d ms，APP 接收速率=%d B/s，CRC=%08X",
                valid ? "完成" : "失败", received, elapsedMs, rate, actualCrc);
        runOnUiThread(() -> resultView.setText(message));
        setStatus(valid ? "已回 END ACK；查看耳机 [CAM_BLE_TEST] 日志取得端到端结果。" : "CRC/长度校验失败，已回错误 ACK。");
        image = null;
    }

    @SuppressLint("MissingPermission")
    private void sendAck(int session, int phase, int status, int nextOffset, long crc) {
        if (gatt == null || rxCharacteristic == null) {
            return;
        }
        byte[] ack = new byte[16];
        putLe16(ack, 0, MAGIC);
        ack[2] = VERSION;
        ack[3] = 4;
        putLe16(ack, 4, session);
        ack[6] = (byte) phase;
        ack[7] = (byte) status;
        putLe32(ack, 8, nextOffset);
        putLe32(ack, 12, crc);
        rxCharacteristic.setWriteType(BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT);
        rxCharacteristic.setValue(ack);
        if (!gatt.writeCharacteristic(rxCharacteristic)) {
            setStatus("ACK 写入未进入 GATT 队列。");
        }
    }

    private void setStatus(String value) {
        runOnUiThread(() -> statusView.setText(value));
    }

    private static int le16(byte[] value, int offset) {
        return (value[offset] & 0xFF) | ((value[offset + 1] & 0xFF) << 8);
    }

    private static int le32(byte[] value, int offset) {
        return (value[offset] & 0xFF) | ((value[offset + 1] & 0xFF) << 8)
                | ((value[offset + 2] & 0xFF) << 16) | ((value[offset + 3] & 0xFF) << 24);
    }

    private static long le32Unsigned(byte[] value, int offset) {
        return le32(value, offset) & 0xFFFFFFFFL;
    }

    private static void putLe16(byte[] value, int offset, int number) {
        value[offset] = (byte) number;
        value[offset + 1] = (byte) (number >>> 8);
    }

    private static void putLe32(byte[] value, int offset, long number) {
        value[offset] = (byte) number;
        value[offset + 1] = (byte) (number >>> 8);
        value[offset + 2] = (byte) (number >>> 16);
        value[offset + 3] = (byte) (number >>> 24);
    }

    @SuppressLint("MissingPermission")
    private void closeGatt() {
        if (gatt != null) {
            gatt.disconnect();
            gatt.close();
            gatt = null;
        }
        rxCharacteristic = null;
    }

    @Override
    protected void onDestroy() {
        stopScan();
        closeGatt();
        super.onDestroy();
    }
}
