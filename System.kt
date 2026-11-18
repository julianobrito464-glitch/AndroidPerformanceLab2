package com.perflab.core

import android.app.*
import android.app.usage.*
import android.content.*
import android.content.pm.ApplicationInfo
import android.os.*
import android.os.storage.StorageManager
import android.provider.Settings
import java.io.File

class Prefs(c: Context) {
    private val p = c.getSharedPreferences("perflab", Context.MODE_PRIVATE)
    fun b(k: String, d: Boolean) = p.getBoolean(k, d)
    fun setB(k: String, v: Boolean) = p.edit().putBoolean(k, v).apply()
    fun i(k: String, d: Int) = p.getInt(k, d)
    fun setI(k: String, v: Int) = p.edit().putInt(k, v).apply()
}

object UsageRepo {
    fun hasAccess(c: Context) = c.getSystemService(AppOpsManager::class.java)
        .unsafeCheckOpNoThrow(AppOpsManager.OPSTR_GET_USAGE_STATS, Process.myUid(), c.packageName) == AppOpsManager.MODE_ALLOWED
    fun recent(c: Context, minutes: Int = 60): List<Pair<String, Long>> {
        val now = System.currentTimeMillis(); val from = now - minutes * 60_000L
        return c.getSystemService(UsageStatsManager::class.java).queryUsageStats(UsageStatsManager.INTERVAL_DAILY, from, now)
            .filter { it.lastTimeUsed > from && it.packageName != c.packageName }
            .sortedByDescending { it.lastTimeUsed }.map { it.packageName to it.lastTimeUsed }
    }
    fun foreground(c: Context): String? {
        val now = System.currentTimeMillis()
        val ev = c.getSystemService(UsageStatsManager::class.java).queryEvents(now - 15_000, now)
        val e = UsageEvents.Event(); var last: String? = null
        while (ev.hasNextEvent()) { ev.getNextEvent(e); if (e.eventType == UsageEvents.Event.ACTIVITY_RESUMED) last = e.packageName }
        return last
    }
}

object Dnd {
    fun has(c: Context) = c.getSystemService(NotificationManager::class.java).isNotificationPolicyAccessGranted
    fun current(c: Context) = c.getSystemService(NotificationManager::class.java).currentInterruptionFilter
    fun setFilter(c: Context, f: Int) { if (has(c)) c.getSystemService(NotificationManager::class.java).setInterruptionFilter(f) }
}

object Games {
    fun label(c: Context, p: String) = runCatching {
        c.packageManager.getApplicationLabel(c.packageManager.getApplicationInfo(p, 0)).toString() }.getOrDefault(p)
    fun isGame(c: Context, p: String) = runCatching {
        c.packageManager.getApplicationInfo(p, 0).category == ApplicationInfo.CATEGORY_GAME }.getOrDefault(false)
    fun list(c: Context) = c.packageManager.getInstalledApplications(0)
        .filter { it.category == ApplicationInfo.CATEGORY_GAME && c.packageManager.getLaunchIntentForPackage(it.packageName) != null }
        .map { it.packageName to label(c, it.packageName) }.sortedBy { it.second }
}

/**
 * Aplica perfis com APIs oficiais. Antes da primeira alteração guarda o estado REAL do usuário (filtro DND e
 * economia de energia). Ao voltar ao perfil cotidiano ou ao desligar o serviço, restaura esse estado,
 * mas só se a configuração ainda for a que o app aplicou (se o usuário mudou à mão, respeita a escolha dele).
 */
object Engine {
    const val SECURE = "android.permission.WRITE_SECURE_SETTINGS"
    private fun curLp(c: Context) = Settings.Global.getInt(c.contentResolver, "low_power", 0)
    fun canLp(c: Context) = c.checkSelfPermission(SECURE) == android.content.pm.PackageManager.PERMISSION_GRANTED
    private fun putLp(c: Context, v: Int) = canLp(c) &&
        runCatching { Settings.Global.putInt(c.contentResolver, "low_power", v) }.getOrDefault(false)
    private fun savedDnd(p: Prefs) = p.i("snap_dnd", 1).takeIf { it in 1..4 } ?: NotificationManager.INTERRUPTION_FILTER_ALL

    /** killBackgroundProcesses só encerra processos em cache; o Android pode reiniciá-los. Não é reversível. */
    fun killBg(c: Context, keep: Set<String>): Int {
        val am = c.getSystemService(ActivityManager::class.java)
        val home = c.packageManager.queryIntentActivities(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME), 0)
            .map { it.activityInfo.packageName }
        val t = UsageRepo.recent(c, 120).map { it.first }.distinct().filter { it !in keep && it !in home && !it.startsWith("com.android.systemui") }
        t.forEach { am.killBackgroundProcesses(it) }
        return t.size
    }

    @Synchronized fun apply(c: Context, id: String, game: String? = null): String {
        if (id == "daily") return restore(c)
        val p = Prefs(c); val log = mutableListOf<String>()
        if (!p.b("snap_valid", false)) {
            p.setI("snap_dnd", Dnd.current(c)); p.setI("snap_lp", curLp(c)); p.setI("app_dnd", -1); p.setI("app_lp", -1); p.setB("snap_valid", true)
        }
        val wantDnd = if (id == "game") p.b("game_dnd", true) else p.b("saver_dnd", false)
        val wantLp = id == "saver" && p.b("saver_lp", true)
        if (Dnd.has(c)) {
            val target = if (wantDnd) NotificationManager.INTERRUPTION_FILTER_PRIORITY else savedDnd(p)
            val now = Dnd.current(c)
            if (now != target && (wantDnd || now == p.i("app_dnd", -1))) { Dnd.setFilter(c, target); p.setI("app_dnd", target); log += if (wantDnd) "Não perturbe ativado" else "Não perturbe devolvido ao seu valor anterior" }
        } else if (wantDnd) log += "DND: permissão ausente"
        val lpTarget = if (wantLp) 1 else p.i("snap_lp", 0); val lpNow = curLp(c)
        if (lpNow != lpTarget && (wantLp || lpNow == p.i("app_lp", -1))) {
            if (putLp(c, lpTarget)) { p.setI("app_lp", lpTarget); log += if (wantLp) "Economia de energia ligada" else "Economia de energia devolvida ao valor anterior" }
            else if (wantLp) log += "Economia: requer WRITE_SECURE_SETTINGS via ADB"
        }
        if (id == "game" && p.b("game_kill", true)) log += "${killBg(c, setOfNotNull(game))} apps em cache encerrados (o sistema pode reabri-los)"
        return log.ifEmpty { listOf("Nada a alterar") }.joinToString("\n")
    }

    @Synchronized fun restore(c: Context): String {
        val p = Prefs(c); if (!p.b("snap_valid", false)) return "Nada a restaurar"
        val log = mutableListOf<String>()
        if (Dnd.has(c)) {
            val saved = savedDnd(p); val now = Dnd.current(c)
            if (now != saved) if (now == p.i("app_dnd", -1)) { Dnd.setFilter(c, saved); log += "Não perturbe restaurado" } else log += "Não perturbe mantido (você alterou)"
        }
        val lpSaved = p.i("snap_lp", 0); val lpNow = curLp(c)
        if (lpNow != lpSaved) if (lpNow == p.i("app_lp", -1) && putLp(c, lpSaved)) log += "Economia de energia restaurada" else log += "Economia de energia mantida"
        p.setB("snap_valid", false)
        return log.ifEmpty { listOf("Configurações já estavam como antes") }.joinToString("\n")
    }
}

data class Bloat(val pkg: String, val name: String, val desc: String, val risk: String)
data class BloatItem(val b: Bloat, val enabled: Boolean)
object Debloat {
    // Nomes de pacote variam entre versões do One UI/região: só aparecem os que existem no aparelho.
    val kb = listOf(
        Bloat("com.samsung.android.bixby.agent", "Bixby Voice", "Assistente de voz da Samsung.", "Baixo"),
        Bloat("com.samsung.android.visionintelligence", "Bixby Vision", "Câmera com reconhecimento de objetos/texto.", "Baixo"),
        Bloat("com.samsung.android.app.spage", "Samsung Free", "Feed de notícias/jogos à esquerda da tela inicial.", "Baixo"),
        Bloat("com.samsung.android.arzone", "AR Zone", "Emojis AR e ferramentas de realidade aumentada.", "Baixo"),
        Bloat("com.samsung.android.tvplus", "Samsung TV Plus", "Canais de TV gratuitos com anúncios.", "Baixo"),
        Bloat("com.samsung.android.app.tips", "Dicas", "Tutoriais do One UI.", "Baixo"),
        Bloat("com.facebook.appmanager", "Facebook App Manager", "Serviço de parceiro do Facebook pré-instalado.", "Baixo"),
        Bloat("com.facebook.services", "Facebook Services", "Atualizações/serviços do app Facebook.", "Baixo"),
        Bloat("com.facebook.system", "Facebook Installer", "Instalador do Facebook pré-instalado.", "Baixo"),
        Bloat("com.samsung.android.game.gamehome", "Game Launcher", "Lançador de jogos. Desativar remove Game Booster/Game Tools.", "Médio"),
        Bloat("com.samsung.android.stickercenter", "Sticker Center", "Loja de stickers/emojis.", "Baixo"))
    fun installed(c: Context) = kb.mapNotNull { b -> runCatching {
        BloatItem(b, c.packageManager.getApplicationInfo(b.pkg, 0).enabled) }.getOrNull() }
    fun disableCmd(p: String) = "adb shell pm disable-user --user 0 $p"
    fun restoreCmd(p: String) = "adb shell pm enable $p"
}

data class JunkFile(val file: File, val size: Long, val kind: String)
object Junk {
    private val root get() = Environment.getExternalStorageDirectory()
    private fun kind(f: File): String? = when {
        f.parentFile?.name == ".thumbnails" -> "miniatura"
        f.extension.lowercase() in setOf("tmp", "temp") -> "temporário"
        f.extension.lowercase() == "log" -> "log"
        else -> null
    }
    fun allowed() = Environment.isExternalStorageManager()
    /** Ignora Android/, links simbólicos e arquivos modificados na última hora (podem estar em uso). */
    fun scan(): List<JunkFile> {
        val now = System.currentTimeMillis(); val r = root
        return r.walkTopDown().onEnter { !(it.name == "Android" && it.parentFile == r) && !java.nio.file.Files.isSymbolicLink(it.toPath()) }
            .filter { it.isFile && now - it.lastModified() > 3_600_000 }
            .mapNotNull { f -> kind(f)?.let { JunkFile(f, f.length(), it) } }
            .sortedByDescending { it.size }.toList()
    }
    /** Revalida cada arquivo antes de apagar. Retorna (quantidade, bytes liberados). */
    fun delete(files: List<JunkFile>): Pair<Int, Long> {
        var n = 0; var b = 0L; val rp = root.canonicalPath
        for (j in files) runCatching {
            val f = j.file; val cp = f.canonicalPath
            if (cp.startsWith("$rp/") && !cp.startsWith("$rp/Android/") && f.isFile && kind(f) != null && !java.nio.file.Files.isSymbolicLink(f.toPath())) {
                val s = f.length(); if (f.delete()) { n++; b += s }
            }
        }
        return n to b
    }
    fun clearOwnCache(c: Context) { c.cacheDir.deleteRecursively(); c.externalCacheDir?.deleteRecursively() }
    /** Tamanho de cache por app (exige acesso de uso). Limpar cache de OUTROS apps não é possível sem Shizuku/ADB/root. */
    fun cacheSizes(c: Context): List<Pair<String, Long>> {
        val s = c.getSystemService(StorageStatsManager::class.java); val u = Process.myUserHandle()
        return c.packageManager.getInstalledApplications(0).mapNotNull { a -> runCatching {
            c.packageManager.getApplicationLabel(a).toString() to s.queryStatsForPackage(StorageManager.UUID_DEFAULT, a.packageName, u).cacheBytes
        }.getOrNull() }.sortedByDescending { it.second }.take(15)
    }
    fun free(): Pair<Long, Long> { val f = android.os.StatFs(Environment.getDataDirectory().path); return f.availableBytes to f.totalBytes }
}
