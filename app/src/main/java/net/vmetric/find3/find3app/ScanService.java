package net.vmetric.find3.find3app;

import android.Manifest;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.PackageManager;
import android.location.Location;
import android.location.LocationManager;
import android.net.wifi.ScanResult;
import android.net.wifi.WifiManager;
import android.os.IBinder;
import android.util.Log;

import androidx.core.app.ActivityCompat;
import androidx.core.app.NotificationCompat;
import androidx.localbroadcastmanager.content.LocalBroadcastManager;

import com.android.volley.AuthFailureError;
import com.android.volley.NetworkResponse;
import com.android.volley.Request;
import com.android.volley.RequestQueue;
import com.android.volley.Response;
import com.android.volley.VolleyError;
import com.android.volley.VolleyLog;
import com.android.volley.toolbox.HttpHeaderParser;
import com.android.volley.toolbox.StringRequest;
import com.android.volley.toolbox.Volley;

import org.json.JSONException;
import org.json.JSONObject;

import java.io.UnsupportedEncodingException;
import java.util.List;
import java.util.Timer;
import java.util.TimerTask;

/**
 * Created by zacks on 3/2/2018.
 */

public class ScanService extends Service {
    // Logging
    private final String TAG = "ScanService";

    int mStartMode;       // indicates how to behave if the service is killed
    IBinder mBinder;      // interface for clients that bind
    boolean mAllowRebind; // indicates whether onRebind should be used
    Timer scanTimer; // Timer used for scanning.

    boolean isScanning = false;
    boolean scanTimerIsRunning = false;
    private final Object lock = new Object();

    // Get a WifiManager, responsible for handling all things wifi (like scanning).
    private WifiManager wifi;

    // Get the default Bluetooth adapter.
    private BluetoothAdapter BTAdapter = BluetoothAdapter.getDefaultAdapter();
    BluetoothBroadcastReceiver bluetoothBroadcastReceiver = null;

    // Post data request queue
    RequestQueue queue;
    private JSONObject jsonBody = new JSONObject();
    private JSONObject bluetoothResults = new JSONObject();
    private JSONObject wifiResults = new JSONObject();

    private String familyName = "";
    private String locationName = "";
    private String deviceName = "";
    private String serverAddress = "";
    private boolean allowGPS = false;

    @Override
    public void onCreate() {
        // The service is being created
        Log.d(TAG, "creating new scan service");
        queue = Volley.newRequestQueue(this);
        // Setup wifi
        wifi = (WifiManager) this.getSystemService(Context.WIFI_SERVICE);
        // If WiFi is not enabled...
        if (!wifi.isWifiEnabled()) {
            // ...enable it.
            wifi.setWifiEnabled(true);
        }
        // Register wifi intent filter
        IntentFilter intentFilter = new IntentFilter();
        intentFilter.addAction(WifiManager.SCAN_RESULTS_AVAILABLE_ACTION);
        registerReceiver(mWifiScanReceiver, intentFilter);

        try {
            // Setup bluetooth
            Log.d(TAG, "setting up bluetooth");
            // If the bluetooth receiver is null...
            if (bluetoothBroadcastReceiver == null) {
                // ...create a new one and apply a new IntentFilter to it.
                bluetoothBroadcastReceiver = new BluetoothBroadcastReceiver();
                registerReceiver(bluetoothBroadcastReceiver, new IntentFilter(BluetoothDevice.ACTION_FOUND));
            }
        } catch (Exception e) {
            Log.e(TAG, e.toString());
        }
    }


    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        super.onStartCommand(intent, flags, startId);

        deviceName = intent.getStringExtra("deviceName");
        familyName = intent.getStringExtra("familyName");
        locationName = intent.getStringExtra("locationName");
        serverAddress = intent.getStringExtra("serverAddress");
        allowGPS = intent.getBooleanExtra("allowGPS", false);

        Log.d(TAG, "familyName: " + familyName);

        // Start ScanService (itself) in foreground,
        // so we can continue scanning after the app leaves foreground.
        startForeground(1, makeNotification());
        // Wait one second, then scan every five seconds.
        // Scan Timer
        // TODO make this be done asynchronously?

        scanTimer = new Timer();
        TimerTask scanTimerTask = new TimerTask() {
            @Override
            public void run() {
                scanTimerIsRunning = true;
                synchronized (lock) {
                    if (isScanning == false) {
                        doScan();
                    }
                }
            }
        };

        scanTimer.scheduleAtFixedRate(scanTimerTask, 1000, 5000); // TODO add user-settable "scan delay," rather than forcing hard-coded values

        return START_STICKY;
    }

    @Override
    public IBinder onBind(Intent intent) {
        // A client is binding to the service with bindService()
        return null;
    }

    @Override
    public boolean onUnbind(Intent intent) {
        // All clients have unbound with unbindService()
        return mAllowRebind;
    }

    @Override
    public void onRebind(Intent intent) {
        // A client is binding to the service with bindService(),
        // after onUnbind() has already been called
    }

    @Override
    public void onDestroy() {
        // The service is no longer needed and is being destroyed
        Log.v(TAG, "onDestroy");
        // Stop the scan timer, i.e., stop scanning.
        stopScanTimer();

        try {
            // If the bluetoothBroadcastReceiver is not null, then we can unregister it.
            if (bluetoothBroadcastReceiver != null)
                unregisterReceiver(bluetoothBroadcastReceiver);
        } catch (Exception e) {
            Log.w(TAG, e.toString());
        }
        try {
            // If the wifi scan receiver is not null, we can unregister it.
            if (mWifiScanReceiver != null)
                unregisterReceiver(mWifiScanReceiver);
        } catch (Exception e) {
            Log.w(TAG, e.toString());
        }
        // Stop itself running in foreground, and remove out notification.
        stopForeground(true);

        // Just in case stopForeground wasn't enough, literally stop itself.
        stopSelf();

        super.onDestroy();

        sendServiceInfoUpdate("Scan service stopped.");
    }

    // This method is responsible for stopping the Scan Timer.
    private void stopScanTimer() {
        // Check if scan timer is currently running; we can't stop what isn't running.
        if (scanTimer != null && scanTimerIsRunning) {
            scanTimer.cancel();
            scanTimer.purge();
            scanTimer = null;
            scanTimerIsRunning = false;
        }

    }
    // This method is responsible for creating the notification that runs when we're scanning.
    // TODO tapping the notification currently does nothing; make it open app on tap
    private Notification makeNotification() {
        String scanningMessage = "Scanning for " + familyName + "/" + deviceName; // What we want our notification to say (be titled)
        String channelId = "scanServiceNotificationsChannelId"; // TODO research what channel id is for (reaching notification from elsewhere?)
        String channelName = "scanServiceNotificationsChannelName"; // TODO research what channel name is for
        NotificationChannel channel = new NotificationChannel(channelId, channelName, NotificationManager.IMPORTANCE_DEFAULT); // TODO research notification channels (groups notifications of similar purposes, like multiple Imgur downloads?)

        // Build our intent responsible for opening the app when notification is tapped.
        Intent resultIntent = new Intent(getApplicationContext(), MainActivity.class);
        resultIntent.setAction("android.intent.action.MAIN");
        resultIntent.addCategory("android.intent.category.LAUNCHER");
        PendingIntent resultPendingIntent = PendingIntent.getActivity(getApplicationContext(), 0, resultIntent, PendingIntent.FLAG_UPDATE_CURRENT);

        // If locationName is not empty, append it to scanningMessage
        if (!locationName.equals("")) {
            scanningMessage += " at " + locationName;
        }

        // I don't fully understand this line. Are we asking the Notification_Service to create our notification channel, with NotificationManager?
        ((NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE)).createNotificationChannel(channel);

        // Return a new, hot&ready pizza - I mean Notification by calling a new NotificationCompat.Builder.
        return new NotificationCompat.Builder(this, channelId)
                .setSmallIcon(R.drawable.ic_stat_name) // Notification's icon
                .setNotificationSilent() // Make notification silent // TODO setting - make this user-toggleable
                .setContentIntent(resultPendingIntent) // Sets the intent to fire when tapping the notification(?)
                .setContentTitle(scanningMessage).build(); // Build our notification.
    }

    private void sendServiceInfoUpdate(String update) {
        if (update != null) {
            Intent intent = new Intent("serviceInfoUpdate");
            intent.putExtra("update", update);
            // TODO replace with LiveData?
            LocalBroadcastManager.getInstance(this).sendBroadcast(intent);
        }
    }

    private final BroadcastReceiver mWifiScanReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context c, Intent intent) {
            // This condition is not necessary if you listen to only one action // TODO what does this mean?
            if (intent.getAction().equals(WifiManager.SCAN_RESULTS_AVAILABLE_ACTION)) {
                //Log.d(TAG, "timer off, trying to send data");
                sendServiceInfoUpdate("Scan results available, attempting to send to server.");
                List<ScanResult> wifiScanList = wifi.getScanResults();
                for (int i = 0; i < wifiScanList.size(); i++) {
                    String name = wifiScanList.get(i).BSSID.toLowerCase();
                    int rssi = wifiScanList.get(i).level;
                    Log.v(TAG, "wifi: " + name + " => " + rssi + "dBm");
                    try {
                        wifiResults.put(name, rssi);
                    } catch (Exception e) {
                        Log.e(TAG, e.toString());
                    }
                }
                sendData();
                BTAdapter.cancelDiscovery();
                BTAdapter = BluetoothAdapter.getDefaultAdapter();
                synchronized (lock) {
                    isScanning = false;
                }
            }
        }
    };

    private void doScan() {
        synchronized (lock) {
            if (isScanning) {
                return;
            }
            isScanning = true;
        }
        bluetoothResults = new JSONObject();
        wifiResults = new JSONObject();
        BTAdapter.startDiscovery();
        if (wifi.startScan()) { // Until WiFiRTT (802.11mc) is widely supported, is there any alternative to startScan()?
            sendServiceInfoUpdate("Started WiFi scan"); // TODO after tapping "stop scan" once data has been sent to server, this line will be called. It shouldn't be called. Why is it getting called?
            //Log.d(TAG, "started wifi scan"); TODO delete this
        } else {
            sendServiceInfoUpdate("Started WiFi scan false?");
            //Log.w(TAG, "started wifi scan false?"); TODO delete this
        }
        Log.d(TAG, "started discovery");
    }

    // Bluetooth receiver
    private class BluetoothBroadcastReceiver extends BroadcastReceiver {
        @Override
        public void onReceive(Context context, Intent intent) {
            String action = intent.getAction();
            if (BluetoothDevice.ACTION_FOUND.equals(action)) {
                int rssi = intent.getShortExtra(BluetoothDevice.EXTRA_RSSI, Short.MIN_VALUE);
                BluetoothDevice device = intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE);
                if (device != null) {
                    String name = device.getAddress().toLowerCase();
                    try {
                        bluetoothResults.put(name, rssi);
                    } catch (Exception e) {
                        Log.e(TAG, e.toString());
                    }
                    Log.v(TAG, "bluetooth: " + name + " => " + rssi + "dBm");
                }
            }
        }
    }


    public void sendData() {
        try {
            sendServiceInfoUpdate("Compiling data...");
            String URL = serverAddress + "/data";
            jsonBody.put("f", familyName);
            jsonBody.put("d", deviceName);
            jsonBody.put("l", locationName);
            jsonBody.put("t", System.currentTimeMillis());
            JSONObject sensors = new JSONObject();
            sensors.put("bluetooth", bluetoothResults);
            sensors.put("wifi", wifiResults);
            jsonBody.put("s", sensors);
            if (allowGPS) {
                JSONObject gps = new JSONObject();
                Location loc = getLastBestLocation();
                if (loc != null) {
                    gps.put("lat", loc.getLatitude());
                    gps.put("lon", loc.getLongitude());
                    gps.put("alt", loc.getAltitude());
                    jsonBody.put("gps", gps);
                }
            }

            final String mRequestBody = jsonBody.toString();
            Log.d(TAG, mRequestBody);

            StringRequest stringRequest = new StringRequest(Request.Method.POST, URL, new Response.Listener<String>() {
                @Override
                public void onResponse(String response) {
                    Log.d(TAG, response);
                }
            }, new Response.ErrorListener() {
                @Override
                public void onErrorResponse(VolleyError error) {
                    Log.e(TAG, error.toString());
                }
            }) {
                @Override
                public String getBodyContentType() {
                    return "application/json; charset=utf-8";
                }

                @Override
                public byte[] getBody() throws AuthFailureError {
                    try {
                        return mRequestBody == null ? null : mRequestBody.getBytes("utf-8");
                    } catch (UnsupportedEncodingException uee) {
                        sendServiceInfoUpdate("Unsupported Encoding: ScanService.java line 348");
                        VolleyLog.wtf("Unsupported Encoding while trying to get the bytes of %s using %s", mRequestBody, "utf-8");
                        return null;
                    }
                }

                @Override
                protected Response<String> parseNetworkResponse(NetworkResponse response) {
                    String responseString = "";
                    if (response != null) {
                        responseString = new String(response.data);
                    }
                    assert response != null;
                    return Response.success(responseString, HttpHeaderParser.parseCacheHeaders(response));
                }
            };

            sendServiceInfoUpdate("Sending data...");
            queue.add(stringRequest);
            sendServiceInfoUpdate("Data sent to server!");
        } catch (JSONException e) {
            e.printStackTrace();
        }
    }

    /**
     * @return the last know best location
     */
    private Location getLastBestLocation() {
        sendServiceInfoUpdate("Getting GPS data");
        LocationManager mLocationManager = (LocationManager)
                getSystemService(Context.LOCATION_SERVICE);
        // If we do not have permissions...
        if (ActivityCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED && ActivityCompat.checkSelfPermission(this, Manifest.permission.ACCESS_COARSE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
            // TODO maybe add permission request? Though that seems like MainActivity's job.

            // ...return null.
            return null;
        }
        Location locationGPS = mLocationManager.getLastKnownLocation(LocationManager.GPS_PROVIDER);
        Location locationNet = mLocationManager.getLastKnownLocation(LocationManager.NETWORK_PROVIDER);

        sendServiceInfoUpdate("Done getting GPS data");

        long GPSLocationTime = 0;
        if (null != locationGPS) {
            GPSLocationTime = locationGPS.getTime();
        }

        long NetLocationTime = 0;

        if (null != locationNet) {
            NetLocationTime = locationNet.getTime();
        }

        if (0 < GPSLocationTime - NetLocationTime) {
            Log.d("GPS",locationGPS.toString());
            return locationGPS;
        } else {
            Log.d("GPS",locationNet.toString());
            return locationNet;
        }

    }
}