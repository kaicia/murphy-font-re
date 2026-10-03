package com.kaicia.txt2epub

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat

/**
 * EPUB을 쓰는 동안 앱이 꺼지지 않게 붙잡아 둔다.
 *
 * 3000화짜리는 쓰는 데 몇 분이 걸린다. 그 사이 다른 앱으로 넘어가거나 화면이 꺼지면
 * 안드로이드가 앱을 정리해 버리고, 그러면 반쯤 쓴 EPUB만 남는다. 실제로 그랬다.
 * 알림을 띄운 '포그라운드 서비스'가 떠 있으면 시스템이 앱을 함부로 끄지 않는다.
 *
 * 일 자체는 ViewModel이 한다. 이 서비스는 떠 있는 것만으로 제 역할을 한다.
 */
class ExportService : Service() {

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val nm = getSystemService(NotificationManager::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            nm?.createNotificationChannel(
                NotificationChannel(CHANNEL, "EPUB 만들기", NotificationManager.IMPORTANCE_LOW)
            )
        }
        val n = NotificationCompat.Builder(this, CHANNEL)
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setContentTitle("EPUB을 만드는 중")
            .setContentText("끝나면 알림이 사라집니다.")
            .setOngoing(true)
            .setSilent(true)
            .build()
        val type = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q)
            ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC else 0
        runCatching { ServiceCompat.startForeground(this, ID, n, type) }
            .onFailure { stopSelf() }
        return START_NOT_STICKY
    }

    companion object {
        private const val CHANNEL = "export"
        private const val ID = 1

        fun start(ctx: Context) {
            // 띄우지 못해도 변환은 그대로 한다. 붙잡아 두는 힘만 약해질 뿐이다.
            runCatching { ContextCompat.startForegroundService(ctx, Intent(ctx, ExportService::class.java)) }
        }

        fun stop(ctx: Context) {
            runCatching { ctx.stopService(Intent(ctx, ExportService::class.java)) }
        }
    }
}
