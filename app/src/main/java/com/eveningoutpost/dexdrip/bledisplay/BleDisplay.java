package com.eveningoutpost.dexdrip.bledisplay;

// anbraten

import android.bluetooth.BluetoothGattCharacteristic;
import android.bluetooth.BluetoothGattService;
import android.content.Intent;
import android.os.PowerManager;
import com.eveningoutpost.dexdrip.models.BgReading;
import com.eveningoutpost.dexdrip.models.JoH;
import com.eveningoutpost.dexdrip.models.UserError;
import com.eveningoutpost.dexdrip.services.JamBaseBluetoothSequencer;
import com.eveningoutpost.dexdrip.utilitymodels.Pref;
import com.eveningoutpost.dexdrip.watch.miband.message.AlertMessage;
import com.polidea.rxandroidble2.RxBleDeviceServices;
import static com.eveningoutpost.dexdrip.models.JoH.emptyString;
import static com.eveningoutpost.dexdrip.services.JamBaseBluetoothSequencer.BaseState.INIT;
import static com.eveningoutpost.dexdrip.services.JamBaseBluetoothSequencer.BaseState.SLEEP;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

public class BleDisplay extends JamBaseBluetoothSequencer {
    private static final boolean d = true;
    private static final String TAG = "BleDisplay";
    private static final String BLE_SERVICE_UUID = "AF6E5F78-706A-43FB-B1F4-C27D7D5C762F";
    private static final String BLE_CHARACTERISTIC_UUID = "6D810E9F-0983-4030-BDA7-C7C9A6A19C1C";

    public static final String PREF_BLE_DISPLAY_ENABLED = "ble_display_enabled";
    public static final String PREF_BLE_DISPLAY_MAC = "ble_display_mac";

    private static final int QUEUE_EXPIRED_TIME = 30; //second
    private static final int QUEUE_DELAY = 0; //ms


    @Override
    public void onCreate() {
        super.onCreate();
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {

        final PowerManager.WakeLock wl = JoH.getWakeLock("ble-display service", 60000);
        try {
            // InPenEntry.started_at = JoH.tsl();
            UserError.Log.d(TAG, "WAKE UP WAKE UP WAKE UP");
            if (!isEnabled()) {
                UserError.Log.d(TAG, "Service is NOT set be active - shutting down");
                stopSelf();
                return START_NOT_STICKY;
            }

            final String mac = Pref.getStringDefaultBlank(PREF_BLE_DISPLAY_MAC);
            if (emptyString(mac)) {
                // setFailOverTimer();
                return START_STICKY;
            }

            setAddress(mac);
            JoH.static_toast_long("BLE Display");

            if (intent != null) {
                final String function = intent.getStringExtra("function");
                if (function != null) {
                    switch (function) {
                        case "sendglucose":
                            sendGlucose();
                            break;
                        default:
                            UserError.Log.e(TAG, "Unknown function: " + function);
                            break;
                    }
                }
            }

            // setFailOverTimer();
            return START_STICKY;
        } finally {
            JoH.releaseWakeLock(wl);
        }
    }

    public static boolean isEnabled() {
        return Pref.getBooleanDefaultFalse(PREF_BLE_DISPLAY_ENABLED);
    }

    static final List<UUID> huntCharacterstics = new ArrayList<>();

    static {
        // huntCharacterstics.add(Constants.BATTERY); // specimen TODO improve
    }


    @Override
    protected void onServicesDiscovered(RxBleDeviceServices services) {
        boolean found = false;
        for (BluetoothGattService service : services.getBluetoothGattServices()) {
            UserError.Log.d(TAG, "Service: " + getUUIDName(service.getUuid()));
            for (BluetoothGattCharacteristic characteristic : service.getCharacteristics()) {
                UserError.Log.d(TAG, "-- Character: " + getUUIDName(characteristic.getUuid()));

                for (final UUID check : huntCharacterstics) {
                    if (characteristic.getUuid().equals(check)) {
                        I.readCharacteristic = check;
                        found = true;
                    }
                }
            }
        }
        if (found) {
            I.isDiscoveryComplete = true;
            I.discoverOnce = true;
            changeNextState();
        } else {
            UserError.Log.e(TAG, "Could not find characteristic during service discovery. This is very unusual");
        }
    }

    private Boolean sendGlucose() {
        BgReading last = BgReading.last();
        AlertMessage message = new AlertMessage();
        if (last == null || last.isStale()) {
            return false;
        } else {
            // TODO: send proper bg message
            String messageText = "BG: " + last.displayValue(null) + " " + last.displaySlopeArrow();
            UserError.Log.uel(TAG, "Send alert msg: " + messageText);
            new QueueMe()
                    .setBytes(message.getAlertMessageOld(messageText.toUpperCase(), AlertMessage.AlertCategory.SMS_MMS))
                    .setDescription("Send alert msg: " + messageText)
                    .setQueueWriteCharacterstic(UUID.fromString(BLE_CHARACTERISTIC_UUID))
                    .expireInSeconds(QUEUE_EXPIRED_TIME)
                    .setDelayMs(QUEUE_DELAY)
                    .queue();
        }
        return true;
    }

    @Override
    protected synchronized boolean automata() {
        if (d)
            UserError.Log.d(TAG, "Automata called in" + TAG);
        extendWakeLock(2000);
        if (isEnabled()) {
            switch (I.state) {
                case INIT:
                    // connect by default
                    changeNextState();
                    break;
                case SLEEP:
                    sendGlucose();
                    break;
                default:
                    return super.automata();
            }
        } else {
            UserError.Log.d(TAG, "Service should not be running inside automata");
            stopSelf();
        }
        return true; // lies
    }

    public static void sendLatestBG() {
        if (isEnabled()) {
            // already on background thread and debounced
            JoH.startService(BleDisplay.class, "function", "sendglucose");
        }
    }
}
