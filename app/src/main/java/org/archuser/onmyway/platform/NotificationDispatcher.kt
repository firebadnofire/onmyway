package org.archuser.onmyway.platform

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.media.AudioAttributes
import android.net.Uri
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import org.archuser.onmyway.MainActivity
import org.archuser.onmyway.R
import org.archuser.onmyway.data.AppSettingsStore
import org.archuser.onmyway.domain.NotificationEvent
import java.security.MessageDigest

class NotificationDispatcher(
    private val context: Context,
    private val settingsStore: AppSettingsStore = AppSettingsStore(context),
) {
    companion object {
        const val REMINDERS_CHANNEL = "travel_reminders"
        const val MONITORING_CHANNEL = "monitoring"
    }

    init {
        val manager = context.getSystemService(NotificationManager::class.java)
        manager.createNotificationChannels(
            listOf(
                NotificationChannel(REMINDERS_CHANNEL, context.getString(R.string.travel_reminders_channel), NotificationManager.IMPORTANCE_HIGH),
                NotificationChannel(MONITORING_CHANNEL, context.getString(R.string.monitoring_channel), NotificationManager.IMPORTANCE_LOW),
            ),
        )
    }

    fun canNotify(): Boolean = NotificationManagerCompat.from(context).areNotificationsEnabled() &&
        (Build.VERSION.SDK_INT < 33 || ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED)

    fun channelIssues(): List<Pair<String, String>> = context.getSystemService(NotificationManager::class.java)
        .notificationChannels.filter { it.id == REMINDERS_CHANNEL || it.id.startsWith("${REMINDERS_CHANNEL}_sound_") }
        .mapNotNull { channel ->
            val issue = when {
                channel.importance == NotificationManager.IMPORTANCE_NONE -> "Blocked"
                channel.importance < NotificationManager.IMPORTANCE_DEFAULT || channel.sound == null -> "Silent"
                channel.importance < NotificationManager.IMPORTANCE_HIGH -> "Pop-up banners disabled"
                else -> null
            }
            issue?.let { channel.id to "${channel.name}: $it" }
        }

    fun post(event: NotificationEvent): Boolean {
        if (!canNotify()) return false
        val channel = runCatching { reminderChannel(event) }.getOrDefault(REMINDERS_CHANNEL)
        if (context.getSystemService(NotificationManager::class.java).getNotificationChannel(channel)?.importance == NotificationManager.IMPORTANCE_NONE) return false
        val intent = Intent(context, MainActivity::class.java).putExtra("event_id", event.id)
        val pendingIntent = PendingIntent.getActivity(
            context,
            event.id.hashCode(),
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val notification = NotificationCompat.Builder(context, channel)
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setContentTitle(event.notificationTitle?.takeIf(String::isNotBlank) ?: context.getString(R.string.app_name))
            .setContentText(event.notificationBody)
            .setStyle(NotificationCompat.BigTextStyle().bigText(event.notificationBody))
            .setContentIntent(pendingIntent)
            .setAutoCancel(true)
            .setCategory(NotificationCompat.CATEGORY_REMINDER)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .build()
        return try {
            NotificationManagerCompat.from(context).notify("reminder:${event.id}", event.id.hashCode(), notification)
            true
        } catch (_: SecurityException) {
            false
        }
    }

    private fun reminderChannel(event: NotificationEvent): String {
        val settings = settingsStore.settings.value
        val sound = when {
            event.customSoundEnabled -> event.customSoundUri
            settings.customSoundEnabled -> settings.customSoundUri
            else -> null
        }?.let(Uri::parse)?.takeIf(::canRead)
        if (sound == null) return REMINDERS_CHANNEL

        val suffix = MessageDigest.getInstance("SHA-256")
            .digest(sound.toString().toByteArray())
            .take(8)
            .joinToString("") { "%02x".format(it.toInt() and 0xff) }
        val channelId = "${REMINDERS_CHANNEL}_sound_$suffix"
        val attributes = AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_NOTIFICATION).build()
        context.getSystemService(NotificationManager::class.java).createNotificationChannel(
            NotificationChannel(
                channelId,
                "${context.getString(R.string.travel_reminders_channel)} (custom sound)",
                NotificationManager.IMPORTANCE_HIGH,
            ).apply { setSound(sound, attributes) },
        )
        return channelId
    }

    private fun canRead(uri: Uri): Boolean = runCatching {
        context.contentResolver.openAssetFileDescriptor(uri, "r")?.use { it.length != 0L } == true
    }.getOrDefault(false)
}
