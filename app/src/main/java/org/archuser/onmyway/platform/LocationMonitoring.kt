package org.archuser.onmyway.platform

import android.Manifest
import android.app.Notification
import android.app.PendingIntent
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import org.archuser.onmyway.MainActivity
import org.archuser.onmyway.OnMyWayApplication
import org.archuser.onmyway.R
import org.archuser.onmyway.domain.LocationObservation
import org.archuser.onmyway.domain.TriggerObservation

class LocationMonitoringService : Service(), LocationListener {
    private lateinit var locationManager: LocationManager

    override fun onCreate() {
        super.onCreate()
        locationManager = getSystemService(LocationManager::class.java)
        startForeground(FOREGROUND_ID, monitoringNotification())
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        startUpdates(intent?.getBooleanExtra(EXTRA_HIGH_PRECISION, false) == true)
        return START_STICKY
    }

    private fun startUpdates(highPrecision: Boolean) {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
            stopSelf()
            return
        }
        try {
            locationManager.removeUpdates(this)
            locationManager.requestLocationUpdates(
                LocationManager.GPS_PROVIDER,
                if (highPrecision) 3_000L else 30_000L,
                if (highPrecision) 1f else 10f,
                this,
            )
        } catch (_: SecurityException) {
            stopSelf()
        }
    }

    override fun onLocationChanged(location: Location) {
        val verticalAccuracy = if (location.hasVerticalAccuracy()) location.verticalAccuracyMeters else null
        val observation = LocationObservation(
            location.latitude,
            location.longitude,
            location.altitude.takeIf { location.hasAltitude() },
            location.accuracy.takeIf { location.hasAccuracy() },
            verticalAccuracy,
            location.time,
        )
        (application as OnMyWayApplication).coordinator.accept(TriggerObservation.Location(observation))
    }

    override fun onDestroy() {
        locationManager.removeUpdates(this)
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun monitoringNotification(): Notification {
        val pendingIntent = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE,
        )
        return NotificationCompat.Builder(this, NotificationDispatcher.MONITORING_CHANNEL)
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setContentTitle(getString(R.string.app_name))
            .setContentText("Monitoring location-based reminders")
            .setContentIntent(pendingIntent)
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
    }

    companion object {
        const val FOREGROUND_ID = 1001
        const val EXTRA_HIGH_PRECISION = "high_precision"
    }
}

class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == Intent.ACTION_BOOT_COMPLETED) {
            // Creating the application restores the coordinator. It starts the location
            // foreground service only if an enabled location rule still requires it.
            (context.applicationContext as? OnMyWayApplication)?.coordinator?.refresh()
        }
    }
}
