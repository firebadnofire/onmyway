package org.archuser.onmyway.platform

import android.app.Notification
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ServiceInfo
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import kotlinx.coroutines.*
import org.archuser.onmyway.MainActivity
import org.archuser.onmyway.OnMyWayApplication
import org.archuser.onmyway.R
import org.archuser.onmyway.domain.LocationObservation
import org.archuser.onmyway.domain.TriggerObservation

class LocationMonitoringService : Service(), LocationListener {
    private val coordinator get() = (application as OnMyWayApplication).coordinator
    private val owner = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate +
        CoroutineExceptionHandler { _, error ->
            coordinator.reportFailure("service", error)
            stopSelf()
        })
    private lateinit var locationManager: LocationManager
    private var locationRequest: LocationRequestSpec? = null
    private var promoted = false
    private var receiverRegistered = false
    private val providerChanges = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) { startMonitoring() }
    }

    override fun onCreate() {
        super.onCreate()
        locationManager = getSystemService(LocationManager::class.java)
        try {
            val issue = PermissionStatusManager(this).monitoringIssue()
            if (issue != null) throw SecurityException(issue)
            ServiceCompat.startForeground(this, FOREGROUND_ID, monitoringNotification(null), ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION)
            promoted = true
            ContextCompat.registerReceiver(this, providerChanges, IntentFilter(LocationManager.MODE_CHANGED_ACTION), ContextCompat.RECEIVER_NOT_EXPORTED)
            receiverRegistered = true
        } catch (error: RuntimeException) {
            coordinator.reportFailure("startup", error)
            stopSelf()
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (!promoted) return START_NOT_STICKY
        startMonitoring()
        return START_STICKY
    }

    private fun startMonitoring() {
        coordinator.startMonitoring(owner, ::configureLocation, { count ->
            getSystemService(NotificationManager::class.java).notify(FOREGROUND_ID, monitoringNotification(count))
        }, {
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
        })
    }

    private fun configureLocation(request: LocationRequestSpec?) {
        if (request == locationRequest) return
        try {
            locationManager.removeUpdates(this)
            locationRequest = null
            if (request != null) {
                locationManager.requestLocationUpdates(LocationManager.GPS_PROVIDER, request.intervalMillis, request.distanceMeters, this)
                locationRequest = request
            }
            coordinator.reportFailure("location", null)
        } catch (error: SecurityException) {
            coordinator.reportFailure("location", error)
        } catch (error: RuntimeException) {
            coordinator.reportFailure("location", error)
        }
    }

    override fun onLocationChanged(location: Location) {
        coordinator.accept(TriggerObservation.Location(LocationObservation(
            location.latitude, location.longitude,
            location.altitude.takeIf { location.hasAltitude() },
            location.accuracy.takeIf { location.hasAccuracy() },
            location.verticalAccuracyMeters.takeIf { location.hasVerticalAccuracy() },
            location.time,
        )))
    }

    override fun onDestroy() {
        owner.cancel()
        coordinator.stopped()
        try {
            locationManager.removeUpdates(this)
            if (receiverRegistered) unregisterReceiver(providerChanges)
        } catch (error: RuntimeException) { coordinator.reportFailure("cleanup", error) }
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun monitoringNotification(count: Int?): Notification {
        val pendingIntent = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE)
        return NotificationCompat.Builder(this, NotificationDispatcher.MONITORING_CHANNEL)
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setContentTitle(getString(R.string.app_name))
            .setContentText(if (count == null) "Starting reminder monitoring" else "Monitoring $count reminders")
            .setContentIntent(pendingIntent)
            .setOngoing(true)
            .setSilent(true)
            .setOnlyAlertOnce(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
    }

    companion object { const val FOREGROUND_ID = 1001 }
}

class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED) return
        val pending = goAsync()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
        scope.launch {
            try {
                (context.applicationContext as OnMyWayApplication).coordinator.refresh()
            } catch (error: Exception) {
                (context.applicationContext as OnMyWayApplication).coordinator.reportFailure("boot", error)
            } finally { pending.finish(); scope.cancel() }
        }
    }
}
