package net.vmetric.find3.find3app;

import android.Manifest;
import android.app.Activity;
import android.app.AlarmManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.SystemClock;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.annotation.RequiresApi;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;
import androidx.appcompat.app.AppCompatActivity;
import android.os.Bundle;
import androidx.core.app.NotificationCompat;
import android.text.util.Linkify;
import android.util.Log;
import android.widget.ArrayAdapter;
import android.widget.AutoCompleteTextView;
import android.widget.CheckBox;
import android.widget.CompoundButton;
import android.widget.EditText;
import android.widget.TextView;
import android.widget.ToggleButton;

import net.vmetric.find3.find3app.R;

import org.java_websocket.client.WebSocketClient;
import org.java_websocket.handshake.ServerHandshake;
import org.json.JSONObject;

import java.net.URI;
import java.net.URISyntaxException;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Date;
import java.util.Iterator;
import java.util.List;
import java.util.ListIterator;
import java.util.Timer;
import java.util.TimerTask;

public class MainActivity extends AppCompatActivity {

    // logging
    private final String TAG = "MainActivity";

    // background manager
    private PendingIntent recurringLl24 = null;
    private Intent ll24 = null;
    AlarmManager alarms = null;
    WebSocketClient mWebSocketClient = null;
    Timer timer = null;
    private RemindTask oneSecondTimer = null;
    // TODO Make autocomplete dependant on family's locations
    private String[] autocompleteLocations = new String[] {"bedroom","living room","kitchen","bathroom", "office"};

    @Override
    protected void onDestroy() {
        Log.d(TAG, "MainActivity onDestroy()");
        if (alarms != null) alarms.cancel(recurringLl24);
        if (timer != null) timer.cancel();
        if (mWebSocketClient != null) {
            mWebSocketClient.close();
        }
        android.app.NotificationManager mNotificationManager = (android.app.NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
        mNotificationManager.cancel(0);
        Intent scanService = new Intent(this, ScanService.class);
        stopService(scanService);
        super.onDestroy();
    }

    class RemindTask extends TimerTask {
        private Integer counter = 0;

        public void resetCounter() {
            counter = 0;
        }
        public void run() {
            runOnUiThread(new Runnable() {
                @Override
                public void run() {
                    counter++;
                    if (mWebSocketClient != null) {
                        if (mWebSocketClient.isClosed()) {
                            connectWebSocket();
                        }
                    }
                    TextView rssi_msg = (TextView) findViewById(R.id.textOutput);
                    String currentText = rssi_msg.getText().toString();
                    if (currentText.contains("ago: ")) {
                        String[] currentTexts = currentText.split("ago: ");
                        currentText = currentTexts[1];
                    }
                    rssi_msg.setText(counter + " seconds ago: " + currentText);
                }
            });
        }
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        if (!permissionHandler()) { // permission handler failed, API version isn't 28 or 29?
            return;
        }

        TextView rssi_msg = (TextView) findViewById(R.id.textOutput);
        rssi_msg.setText("Scanning info will appear here.");
        TextView mainOutput = (TextView) findViewById(R.id.textOutput2);
        mainOutput.setText("Process info will appear here."); // TODO use this.
        // check to see if there are preferences // TODO what is this?
        SharedPreferences sharedPref = MainActivity.this.getPreferences(Context.MODE_PRIVATE);
        EditText familyNameEdit = (EditText) findViewById(R.id.familyName);
        familyNameEdit.setText(sharedPref.getString("familyName", ""));
        EditText deviceNameEdit = (EditText) findViewById(R.id.deviceName);
        deviceNameEdit.setText(sharedPref.getString("deviceName", ""));
        EditText serverAddressEdit = (EditText) findViewById(R.id.serverAddress);
        serverAddressEdit.setText(sharedPref.getString("serverAddress", ((EditText) findViewById(R.id.serverAddress)).getText().toString()));
        CheckBox checkBoxAllowGPS = (CheckBox) findViewById(R.id.allowGPS);
        checkBoxAllowGPS.setChecked(sharedPref.getBoolean("allowGPS",false));

        // TODO is this responsible for adding autocomplete suggestions to locationName?
        AutoCompleteTextView textView = (AutoCompleteTextView) findViewById(R.id.locationName);
        ArrayAdapter<String> adapter =
                new ArrayAdapter<String>(this, android.R.layout.simple_list_item_1, autocompleteLocations);
        textView.setAdapter(adapter);

        ToggleButton toggleButtonTracking = (ToggleButton) findViewById(R.id.toggleScanType);
        toggleButtonTracking.setOnCheckedChangeListener(new CompoundButton.OnCheckedChangeListener() {
            @Override
            public void onCheckedChanged(CompoundButton buttonView, boolean isChecked) {
                TextView rssi_msg = (TextView) findViewById(R.id.textOutput);
                rssi_msg.setText("not running");
                Log.d(TAG, "toggle set to false");
                if (alarms != null) alarms.cancel(recurringLl24);
                android.app.NotificationManager mNotificationManager = (android.app.NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
                mNotificationManager.cancel(0);
                if (timer != null) timer.cancel();

                CompoundButton scanButton = (CompoundButton) findViewById(R.id.toggleButton);
                scanButton.setChecked(false);
            }
        });

        ToggleButton toggleButton = (ToggleButton) findViewById(R.id.toggleButton);
        toggleButton.setOnCheckedChangeListener(new CompoundButton.OnCheckedChangeListener() {
            @Override
            public void onCheckedChanged(CompoundButton buttonView, boolean isChecked) {
                // Create scanService intent
                Intent scanService = new Intent(MainActivity.this, ScanService.class);
                if (isChecked) {
                    TextView rssi_msg = (TextView) findViewById(R.id.textOutput);
                    String familyName = ((EditText) findViewById(R.id.familyName)).getText().toString().toLowerCase();
                    if (familyName.equals("")) {
                        rssi_msg.setText("family name cannot be empty");
                        buttonView.toggle();
                        return;
                    }
                    String serverAddress = ((EditText) findViewById(R.id.serverAddress)).getText().toString().toLowerCase();
                    if (serverAddress.equals("")) {
                        rssi_msg.setText("server address cannot be empty");
                        buttonView.toggle();
                        return;
                    }
                    if (serverAddress.contains("http")!=true) {
                        rssi_msg.setText("must include http or https in server name");
                        buttonView.toggle();
                        return;
                    }
                    String deviceName = ((EditText) findViewById(R.id.deviceName)).getText().toString().toLowerCase();
                    if (deviceName.equals("")) {
                        rssi_msg.setText("device name cannot be empty");
                        buttonView.toggle();
                        return;
                    }
                    boolean allowGPS = ((CheckBox) findViewById(R.id.allowGPS)).isChecked();
                    Log.d(TAG,"allowGPS is checked: "+allowGPS);
                    String locationName = ((EditText) findViewById(R.id.locationName)).getText().toString().toLowerCase();
                    CompoundButton trackingButton = (CompoundButton) findViewById(R.id.toggleScanType);
                    if (trackingButton.isChecked() == false) {
                        locationName = "";
                    } else {
                        if (locationName.equals("")) {
                            rssi_msg.setText("location name cannot be empty when learning");
                            buttonView.toggle();
                            return;
                        }
                    }

                    SharedPreferences sharedPref = MainActivity.this.getPreferences(Context.MODE_PRIVATE);
                    SharedPreferences.Editor editor = sharedPref.edit();
                    editor.putString("familyName", familyName);
                    editor.putString("deviceName", deviceName);
                    editor.putString("serverAddress", serverAddress);
                    editor.putString("locationName", locationName);
                    editor.putBoolean("allowGPS",allowGPS);
                    editor.apply();

                    // TODO make "running" more descriptive to user,
                    //  we need to inform user that the app is working and is not frozen/broken,
                    //  while starting scanService/doing work
                    rssi_msg.setText("running");
                    // 24/7 alarm
                    //TODO Clean this up - how much of below is necessary?
                    ll24 = new Intent(MainActivity.this, AlarmReceiverLife.class);
                    Log.d(TAG, "setting familyName to [" + familyName + "]");
                    ll24.putExtra("familyName", familyName);
                    ll24.putExtra("deviceName", deviceName);
                    ll24.putExtra("serverAddress", serverAddress);
                    ll24.putExtra("locationName", locationName);
                    ll24.putExtra("allowGPS",allowGPS);
                    recurringLl24 = PendingIntent.getBroadcast(MainActivity.this, 0, ll24, PendingIntent.FLAG_CANCEL_CURRENT);
                    scanService.putExtra("familyName",familyName);
                    scanService.putExtra("deviceName",deviceName);
                    scanService.putExtra("locationName",locationName);
                    scanService.putExtra("serverAddress",serverAddress);
                    scanService.putExtra("allowGPS",allowGPS);
                    Log.d(TAG,"familyName: "+ familyName);
                    // Start scanService to begin scanning.
                    // scanService is started as a ForegroundService so we can scan when the app is not in focus, and while phone is asleep.
                    startForegroundService(scanService);
                    alarms = (AlarmManager) getSystemService(Context.ALARM_SERVICE);
                    alarms.setRepeating(AlarmManager.RTC_WAKEUP, SystemClock.currentThreadTimeMillis(), 31000, recurringLl24);
                    timer = new Timer();
                    oneSecondTimer = new RemindTask();
                    timer.scheduleAtFixedRate(oneSecondTimer, 1000, 1000);
                    connectWebSocket();

                    final TextView myClickableUrl = (TextView) findViewById(R.id.textInstructions);
                    myClickableUrl.setText("See your results in realtime: " + serverAddress + "/view/location/" + familyName + "/" + deviceName);
                    Linkify.addLinks(myClickableUrl, Linkify.WEB_URLS);
                } else {
                    stopService(scanService);
                    TextView rssi_msg = (TextView) findViewById(R.id.textOutput);
                    rssi_msg.setText("not running");
                    Log.d(TAG, "toggle set to false");
                    alarms.cancel(recurringLl24);
                    timer.cancel();
                }
            }
        });
    }

    // Handles all permission-related shenanigans, such as checking if we have permissions, requesting permissions, etc.
    // TODO optimize this/make it prettier.
    private boolean permissionHandler() {
        // SDK version of device
        int SDKversion = Build.VERSION.SDK_INT;
        // If we're running API 28 (Android 9), check for appropriate permissions.
        if (SDKversion == 28) {
            // Array containing each permission we need to check for. // TODO is there a way to automatically synchronize this and permissions listed in AndroidManifest.xml?
            String[] api28permissions = new String[]{Manifest.permission.ACCESS_COARSE_LOCATION, Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_WIFI_STATE, Manifest.permission.BLUETOOTH, Manifest.permission.BLUETOOTH_ADMIN, Manifest.permission.CHANGE_WIFI_STATE, Manifest.permission.FOREGROUND_SERVICE, Manifest.permission.INTERNET, Manifest.permission.WAKE_LOCK};
            // ArrayList to hold denied permissions.
            ArrayList<String> deniedPermissions = new ArrayList<>();

            // For each permission in permissions array...
            for (String permission : api28permissions) {
                // ...if permission is denied...
                if (ContextCompat.checkSelfPermission(this, permission) == PackageManager.PERMISSION_DENIED) {
                    // ...add denied permission to deniedPermission.
                    deniedPermissions.add(permission);
                }
            }

            // If any permissions are denied...
            if (!deniedPermissions.isEmpty()) {
                // ...request all of our denied permissions...
                ActivityCompat.requestPermissions(this, deniedPermissions.toArray(new String[0]), 1);
                // ...and return true.
            }
            return true;

        } else if (SDKversion == 29) { // Else, if we're running API 29 (Android 10), check for appropriate permissions.
            // Array containing each permission we need to check for. // TODO is there a way to automatically synchronize this and permissions listed in AndroidManifest.xml?
            String[] api29permissions = new String[]{Manifest.permission.ACCESS_BACKGROUND_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION, Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_WIFI_STATE, Manifest.permission.BLUETOOTH, Manifest.permission.BLUETOOTH_ADMIN, Manifest.permission.CHANGE_WIFI_STATE, Manifest.permission.FOREGROUND_SERVICE, Manifest.permission.INTERNET, Manifest.permission.WAKE_LOCK};
            // ArrayList to hold denied permissions.
            ArrayList<String> deniedPermissions = new ArrayList<>();

            // For each permission in permissions array...
            for (String permission : api29permissions) {
                // ...if permission is denied...
                if (ContextCompat.checkSelfPermission(this, permission) == PackageManager.PERMISSION_DENIED) {
                    // ...add denied permission to deniedPermission.
                    deniedPermissions.add(permission);
                }
            }

            // If any permissions are denied...
            if (!deniedPermissions.isEmpty()) {
                // ...request all of our denied permissions...
                ActivityCompat.requestPermissions(this, deniedPermissions.toArray(new String[0]), 1);
                // ...and return true.
            }
            return true;
        } else {
            return false; // Android version != 28 || 29
        }
    }

    private void connectWebSocket() {
        URI uri;
        try {
            String serverAddress = ((EditText) findViewById(R.id.serverAddress)).getText().toString();
            String familyName = ((EditText) findViewById(R.id.familyName)).getText().toString();
            String deviceName = ((EditText) findViewById(R.id.deviceName)).getText().toString();
            serverAddress = serverAddress.replace("http", "ws");
            uri = new URI(serverAddress + "/ws?family=" + familyName + "&device=" + deviceName);
            Log.d("Websocket", "connect to websocket at " + uri.toString());
        } catch (URISyntaxException e) {
            e.printStackTrace();
            return;
        }

        mWebSocketClient = new WebSocketClient(uri) {
            @Override
            public void onOpen(ServerHandshake serverHandshake) {
                Log.i("Websocket", "Opened");
                mWebSocketClient.send("Hello");
            }

            @Override
            public void onMessage(String s) {
                final String message = s;
                runOnUiThread(new Runnable() {
                    @Override
                    public void run() {
                        Log.d("Websocket", "message: " + message);
                        JSONObject json = null;
                        JSONObject fingerprint = null;
                        JSONObject sensors = null;
                        JSONObject bluetooth = null;
                        JSONObject wifi = null;
                        String deviceName = "";
                        String locationName = "";
                        String familyName = "";
                        try {
                            json = new JSONObject(message);
                        } catch (Exception e) {
                            Log.d("Websocket", "json error: " + e.toString());
                            return;
                        }
                        try {
                            fingerprint = new JSONObject(json.get("sensors").toString());
                            Log.d("Websocket", "fingerprint: " + fingerprint);
                        } catch (Exception e) {
                            Log.d("Websocket", "json error: " + e.toString());
                        }
                        try {
                            sensors = new JSONObject(fingerprint.get("s").toString());
                            deviceName = fingerprint.get("d").toString();
                            familyName = fingerprint.get("f").toString();
                            locationName = fingerprint.get("l").toString();
                            Log.d("Websocket", "sensors: " + sensors);
                        } catch (Exception e) {
                            Log.d("Websocket", "json error: " + e.toString());
                        }
                        try {
                            wifi = new JSONObject(sensors.get("wifi").toString());
                            Log.d("Websocket", "wifi: " + wifi);
                        } catch (Exception e) {
                            Log.d("Websocket", "json error: " + e.toString());
                        }
                        try {
                            bluetooth = new JSONObject(sensors.get("bluetooth").toString());
                            Log.d("Websocket", "bluetooth: " + bluetooth);
                        } catch (Exception e) {
                            Log.d("Websocket", "json error: " + e.toString());
                        }
                        Log.d("Websocket", bluetooth.toString());
                        Integer bluetoothPoints = bluetooth.length();
                        Integer wifiPoints = wifi.length();
                        Long secondsAgo = null;
                        try {
                            secondsAgo = fingerprint.getLong("t");
                        } catch (Exception e) {
                            Log.w("Websocket", e);
                        }

                        if ((System.currentTimeMillis() - secondsAgo)/1000 > 3) {
                            return;
                        }
                        SimpleDateFormat sdf = new SimpleDateFormat("MMM dd HH:mm:ss");
                        Date resultdate = new Date(secondsAgo);
//                        String message = sdf.format(resultdate) + ": " + bluetoothPoints.toString() + " bluetooth and " + wifiPoints.toString() + " wifi points inserted for " + familyName + "/" + deviceName;
                        String message = "1 second ago: added " + bluetoothPoints.toString() + " bluetooth and " + wifiPoints.toString() + " wifi points for " + familyName + "/" + deviceName;
                        oneSecondTimer.resetCounter();
                        if (locationName.equals("") == false) {
                            message += " at " + locationName;
                        }
                        TextView rssi_msg = (TextView) findViewById(R.id.textOutput);
                        Log.d("Websocket", message);
                        rssi_msg.setText(message);

                    }
                });
            }

            @Override
            public void onClose(int i, String s, boolean b) {
                Log.i("Websocket", "Closed " + s);
            }

            @Override
            public void onError(Exception e) {
                Log.i("Websocket", "Error " + e.getMessage());
                runOnUiThread(new Runnable() {
                    @Override
                    public void run() {
                        TextView rssi_msg = (TextView) findViewById(R.id.textOutput);
                        rssi_msg.setText("cannot connect to server, fingerprints will not be uploaded");
                    }
                });
            }
        };
        mWebSocketClient.connect();
    }




}
