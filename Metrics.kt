package com.perflab.core

import android.app.ActivityManager
import android.content.*
import android.os.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import java.io.File

data class MemSnap(val totalMb: Long, val availMb: Long, val swapUsedMb: Long, val low: Boolean) {
    val usedPct get() = 100f * (totalMb - availMb) / totalMb.coerceAtLeast(1)
}
data class CpuSnap(val cur: List<Int?>, val max: List<Int?>) {
    val readable get() = cur.any { it != null }
    /** Carga estimada = frequência atual / máxima. /proc/stat é bloqueado para apps desde o Android 8. */
    val loadPct: Float get() {
        val r = cur.indices.mapNotNull { i -> val c = cur[i]; val m = max[i]; if (c != null && m != null && m > 0) c.toFloat() / m else null }
        return if (r.isEmpty()) 0f else 100f * r.average().toFloat()
    }
}
data class BatSnap(val pct: Int, val tempC: Float, val charging: Boolean, val currentMa: Int?)
data class Snap(val mem: MemSnap, val cpu: CpuSnap, val bat: BatSnap, val thermalStatus: Int, val headroom: Float?, val zoneTempC: Float?)

class Sampler(private val c: Context) {
    private val am = c.getSystemService(ActivityManager::class.java)
    private val pm = c.getSystemService(PowerManager::class.java)
    private val bm = c.getSystemService(BatteryManager::class.java)
    private fun num(p: String) = runCatching { File(p).readText().trim().toLong() }.getOrNull()
    private fun kb(key: String, t: String): Long = Regex("$key:\\s+(\\d+)").find(t)?.groupValues?.get(1)?.toLong() ?: 0

    fun memory(): MemSnap {
        val i = ActivityManager.MemoryInfo(); am.getMemoryInfo(i)
        val t = runCatching { File("/proc/meminfo").readText() }.getOrDefault("")
        return MemSnap(i.totalMem shr 20, i.availMem shr 20, (kb("SwapTotal", t) - kb("SwapFree", t)) / 1024, i.lowMemory)
    }
    fun cpu(): CpuSnap {
        val n = Runtime.getRuntime().availableProcessors()
        fun mhz(i: Int, f: String) = num("/sys/devices/system/cpu/cpu$i/cpufreq/$f")?.let { (it / 1000).toInt() }
        return CpuSnap((0 until n).map { mhz(it, "scaling_cur_freq") }, (0 until n).map { mhz(it, "cpuinfo_max_freq") })
    }
    fun battery(): BatSnap {
        val i = c.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        val l = i?.getIntExtra(BatteryManager.EXTRA_LEVEL, 0) ?: 0
        val s = i?.getIntExtra(BatteryManager.EXTRA_SCALE, 100) ?: 100
        val cur = bm.getIntProperty(BatteryManager.BATTERY_PROPERTY_CURRENT_NOW).takeIf { it != Int.MIN_VALUE }?.let { it / 1000 }
        return BatSnap(100 * l / s, (i?.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, 0) ?: 0) / 10f,
            (i?.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0) ?: 0) != 0, cur)
    }
    /** Maior temperatura legível em /sys/class/thermal (normalmente bloqueado por SELinux -> null). */
    fun zoneTemp(): Float? = runCatching {
        File("/sys/class/thermal").listFiles { x -> x.name.startsWith("thermal_zone") }!!
            .mapNotNull { num("${it.path}/temp") }.maxOrNull()?.let { if (it > 1000) it / 1000f else it.toFloat() }
    }.getOrNull()

    fun read() = Snap(memory(), cpu(), battery(), pm.currentThermalStatus,
        pm.getThermalHeadroom(10).takeIf { !it.isNaN() }, zoneTemp())
    fun flow(ms: Long) = flow { while (true) { emit(read()); delay(ms) } }.flowOn(Dispatchers.IO)
}
