package com.example.phonemirror

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.launch
import java.util.UUID

class StreamService : Service() {

    private val screenFrames = MutableSharedFlow<ByteArray>(extraBufferCapacity = 4)
    private val cameraFrames = MutableSharedFlow<ByteArray>(extraBufferCapacity = 4)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val token: String = UUID.randomUUID().toString().take(8)

    private var screen: ScreenCapturer? = null
    private var camera: CameraCapturer? = null
    private var mic: MicCapturer? = null
    private var web: WebServer? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            stopSelf()
            return START_NOT_STICKY
        }

        startForeground(NOTIFICATION_ID, buildNotification())

        val server = WebServer(token, screenFrames, cameraFrames, SoundPlayer()) {
            stopSelf()
        }
        web = server
        server.start()

        camera = CameraCapturer(this) { jpeg -> cameraFrames.tryEmit(jpeg) }
        camera?.start()

        mic = MicCapturer { pcm -> scope.launch { server.sendPcm(pcm) } }
        mic?.start()

        @Suppress("DEPRECATION")
        val data = intent?.getParcelableExtra<Intent>(EXTRA_DATA)
        val resultCode = intent?.getIntExtra(EXTRA_RESULT_CODE, -1) ?: -1
        if (data != null && resultCode != -1) {
            val manager = getSystemService(MediaProjectionManager::class.java)
            val projection = manager.getMediaProjection(resultCode, data)
            screen = ScreenCapturer(this, projection) { jpeg -> screenFrames.tryEmit(jpeg) }
            screen?.start()
        }

        return START_NOT_STICKY
    }

    override fun onDestroy() {
        screen?.stop()
        camera?.stop()
        mic?.stop()
        web?.stop()
        scope.cancel()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun buildNotification(): Notification {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID, "PhoneMirror LAN", NotificationManager.IMPORTANCE_LOW
            )
            getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
        }

        val stopIntent = Intent(this, StreamService::class.java).setAction(ACTION_STOP)
        val stopPending = PendingIntent.getService(
            this, 1, stopIntent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_menu_view)
            .setContentTitle("PhoneMirror LAN активен")
            .setContentText("Токен: $token — откройте http://<ip>:8080/ на ПК")
            .setOngoing(true)
            .addAction(android.R.drawable.ic_delete, "Остановить", stopPending)
            .build()
    }

    companion object {
        private const val CHANNEL_ID = "phone_mirror"
        private const val NOTIFICATION_ID = 1001
        private const val ACTION_STOP = "com.example.phonemirror.STOP"
        const val EXTRA_RESULT_CODE = "rc"
        const val EXTRA_DATA = "data"
    }
}
