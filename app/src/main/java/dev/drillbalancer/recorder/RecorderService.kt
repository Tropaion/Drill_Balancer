package dev.drillbalancer.recorder

import android.Manifest
import android.annotation.SuppressLint
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import dev.drillbalancer.DrillBalancerApp
import dev.drillbalancer.MainActivity
import dev.drillbalancer.R
import dev.drillbalancer.ui.formatMinSec
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

/** What the recording notification says, and its button. */
data class RecorderNotice(val title: String, val text: String?, val action: String)

/** The notification for [s] (elapsed whole seconds, so it changes at most once a second). */
fun recorderNotice(s: RecordingState): RecorderNotice = when (s.phase) {
    RecordPhase.RECORDING -> RecorderNotice("Recording ${formatMinSec(s.live.elapsedS.toLong(), padMinutes = true)}", s.ssid?.let { "On $it" }, "Stop")
    RecordPhase.STOPPING -> RecorderNotice("Stopping the take…", null, "Finish")
    RecordPhase.READY -> RecorderNotice(
        "Recorder ready",
        if (s.takes == 1) "1 take this session" else "${s.takes} takes this session",
        "Finish",
    )
    else -> RecorderNotice("Connecting to the recorder…", s.ssid, "Finish")
}

/**
 * Keeps the process in the foreground while a recording session runs (type connectedDevice):
 * the stream must keep flowing with the screen off. It owns nothing: it follows the app's
 * RecordingController and stops itself when the session ends. Its button stops the take
 * (or finishes the session).
 */
class RecorderService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var follow: Job? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val controller = (application as DrillBalancerApp).recorder
        if (intent?.action == ACTION_STOP) {
            if (controller.state.value.phase == RecordPhase.RECORDING) controller.stopTake() else controller.close()
        }
        val notice = recorderNotice(controller.state.value)
        try {
            // ServiceCompat leaves the type out below API 29.
            @SuppressLint("InlinedApi")
            ServiceCompat.startForeground(this, NOTIFICATION_ID, build(notice), ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE)
        } catch (e: RuntimeException) {
            // Not allowed from the background (API 31+), or the type's permission is missing.
            Log.w(TAG, "no foreground service: $e")
            stopSelf()
            return START_NOT_STICKY
        }
        if (follow == null) {
            follow = scope.launch {
                controller.state.map { if (it.phase.active) recorderNotice(it) else null }.distinctUntilChanged().collect { n ->
                    if (n == null) {
                        ServiceCompat.stopForeground(this@RecorderService, ServiceCompat.STOP_FOREGROUND_REMOVE)
                        stopSelf()
                    } else {
                        show(n)
                    }
                }
            }
        }
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }

    private fun show(n: RecorderNotice) {
        val allowed = Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED
        if (allowed) NotificationManagerCompat.from(this).notify(NOTIFICATION_ID, build(n))
    }

    private fun build(n: RecorderNotice) = NotificationCompat.Builder(this, CHANNEL)
        .setSmallIcon(R.drawable.ic_stat_recorder)
        .setContentTitle(n.title)
        .setContentText(n.text)
        .setOngoing(true)
        .setOnlyAlertOnce(true)
        .setSilent(true)
        .setCategory(NotificationCompat.CATEGORY_SERVICE)
        .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
        .setContentIntent(
            PendingIntent.getActivity(
                this, 0, Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
            ),
        )
        .addAction(
            0, n.action,
            PendingIntent.getService(
                this, 1, Intent(this, RecorderService::class.java).setAction(ACTION_STOP),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
            ),
        )
        .build()

    companion object {
        private const val TAG = "RecorderService"
        private const val CHANNEL = "recorder"
        private const val NOTIFICATION_ID = 1
        private const val ACTION_STOP = "dev.drillbalancer.recorder.STOP"

        /** Starts the service whenever [controller] has a session (call once, at app scope). */
        fun follow(context: Context, controller: RecordingController, scope: CoroutineScope) {
            val app = context.applicationContext
            createChannel(app)
            scope.launch(Dispatchers.Main) {
                controller.phase.map { it.active }.distinctUntilChanged().collect { active ->
                    if (!active) return@collect
                    try {
                        ContextCompat.startForegroundService(app, Intent(app, RecorderService::class.java))
                    } catch (e: RuntimeException) {
                        Log.w(TAG, "could not start the recorder service: $e")
                    }
                }
            }
        }

        private fun createChannel(context: Context) {
            val nm = context.getSystemService(NotificationManager::class.java) ?: return
            nm.createNotificationChannel(
                NotificationChannel(CHANNEL, "Recording", NotificationManager.IMPORTANCE_LOW).apply {
                    description = "Shown while a training-data recording session runs"
                },
            )
        }
    }
}
