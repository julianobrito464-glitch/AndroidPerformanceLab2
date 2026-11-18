package com.perflab

import android.app.*
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.IBinder
import com.perflab.core.*
import kotlinx.coroutines.*

class AutomationService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var job: Job? = null
    override fun onBind(i: Intent?): IBinder? = null
    override fun onStartCommand(i: Intent?, f: Int, s: Int): Int {
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel("auto", "Automação", NotificationManager.IMPORTANCE_LOW))
        startForeground(1, Notification.Builder(this, "auto").setContentTitle("Android Performance Lab")
            .setContentText("Automação de perfis ativa").setSmallIcon(android.R.drawable.ic_menu_manage).build(),
            ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        job?.cancel()   // evita laços duplicados se o serviço for iniciado de novo
        job = scope.launch {
            val sampler = Sampler(this@AutomationService); var cur = ""; var fg: String? = null
            while (isActive) {
                runCatching { UsageRepo.foreground(this@AutomationService)?.let { fg = it } }
                val bat = sampler.battery()
                val game = fg?.takeIf { Games.isGame(this@AutomationService, it) }
                val want = when {
                    game != null -> "game"
                    bat.pct <= Prefs(this@AutomationService).i("saver_thr", 20) && !bat.charging -> "saver"
                    else -> "daily"
                }
                if (want != cur) { cur = want; Engine.apply(this@AutomationService, want, game) }
                delay(3000)
            }
        }
        return START_STICKY
    }
    /** Ao desligar o serviço, devolve DND e economia de energia ao estado que o usuário tinha antes. */
    override fun onDestroy() { job?.cancel(); scope.cancel(); Engine.restore(this); super.onDestroy() }
}
