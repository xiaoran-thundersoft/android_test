# Camera BLE Test APK

Standalone Android receiver for the earphone `camera_ble_test` eShell command.

It scans BLE devices, connects to the firmware interaction service, requests MTU 517,
subscribes to notifications, rebuilds one JPEG in RAM, verifies CRC-32, and writes the
START/DATA/END acknowledgements required by the earphone firmware.

## Test procedure

1. Install `app-debug.apk`, grant Bluetooth permissions, then turn on Bluetooth.
2. Tap **扫描耳机**, select the intended earphone, and wait for **测试通道已就绪**.
3. In the earphone eShell, run `camera_ble_test 0 85 1`.
4. The APK displays image bytes, APP receive duration, APP receive throughput, and CRC.
   The earphone `[CAM_BLE_TEST]` line is the authoritative end-to-end measurement because
   it ends only after the APP END ACK has been received.

## BLE service

| Item | UUID |
|---|---|
| Service | `4746414e-0000-1000-8000-00805f9b34fb` |
| Earphone -> APP Notification | `00005555-0000-1000-8000-00805f9b34fb` |
| APP -> Earphone Write ACK | `00006666-0000-1000-8000-00805f9b34fb` |

The protocol is documented in the firmware repository at
`vendor/lightsail/apps/app_camera/camera_ble_test_protocol.md`.
