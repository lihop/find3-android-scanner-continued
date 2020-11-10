package net.vmetric.find3.find3app;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.os.PowerManager;
import android.util.Log;

/**
 * Created by zacks on 3/2/2018.
 */
// TODO is AlarmReceiverLife necessary anymore?
public class AlarmReceiverLife extends BroadcastReceiver {
    private static PowerManager.WakeLock wakeLock;
    private static final String TAG = "AlarmReceiverLife";

    @Override
    public void onReceive(Context context, Intent intent) {
        Log.v(TAG, "Recurring alarm");
        // Full wake lock deprecated in API level 17, thus partial wake lock
        // TODO Wakelock currently functions, as scanning continues when app is not in foreground. (at least, when plugged in)
        //  However, need to review wakelock here (and elsewhere?) to verify they're setup properly/efficiently
        //  i.e., that the wakelock isn't just an infinite loop of "acquire, release, acquire, release"
        //  I'm fairly certain that AlarmReceiverLife literally just acquires wakelock, then immediately releases it.
        //  It may not be required to have wakelock here, if a wakelock is required at all. I'm too tired right now to think through it and test further.
        PowerManager pm = (PowerManager) context.getSystemService(Context.POWER_SERVICE);
        wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "FIND3:PartialWakeLockTag");
        wakeLock.acquire();
        Log.d(TAG,"Releasing wakelock");
        if (wakeLock != null) wakeLock.release();
        wakeLock = null;
    }


}
