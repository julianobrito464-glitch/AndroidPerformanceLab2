package com.perflab

import android.app.UiModeManager
import android.content.*
import android.content.res.Configuration
import android.net.Uri
import android.os.*
import android.provider.Settings
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.perflab.core.*
import kotlinx.coroutines.*

@Composable fun Page(body: @Composable ColumnScope.() -> Unit) = Column(
    Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp), Arrangement.spacedBy(12.dp), content = body)
@Composable fun Panel(title: String, body: @Composable ColumnScope.() -> Unit) = Card(
    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface), shape = RoundedCornerShape(24.dp)) {
    Column(Modifier.padding(16.dp).fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) { Text(title, fontWeight = FontWeight.SemiBold); body() } }
@Composable fun Meter(f: Float) = LinearProgressIndicator(progress = { f.coerceIn(0f, 1f) }, modifier = Modifier.fillMaxWidth().height(8.dp).clip(RoundedCornerShape(4.dp)))
@Composable fun Note(t: String) = Text(t, style = MaterialTheme.typography.bodySmall, color = Color.Gray)
@Composable fun Chart(title: String, v: List<Float>, unit: String, max: Float, color: Color) =
    Panel("$title  ${v.lastOrNull()?.let { "%.0f".format(it) } ?: "–"}$unit") {
        Canvas(Modifier.fillMaxWidth().height(80.dp)) {
            if (v.size > 1) {
                val p = Path()
                v.forEachIndexed { i, y -> val x = size.width * i / (v.size - 1); val yy = size.height * (1 - (y / max).coerceIn(0f, 1f))
                    if (i == 0) p.moveTo(x, yy) else p.lineTo(x, yy) }
                drawPath(p, color, style = Stroke(4f))
            } else drawLine(color, Offset(0f, size.height), Offset(size.width, size.height))
        }
    }
@Composable fun resumeTick(): Int {
    var t by remember { mutableIntStateOf(0) }; val o = LocalLifecycleOwner.current.lifecycle
    DisposableEffect(o) { val ob = LifecycleEventObserver { _, e -> if (e == Lifecycle.Event.ON_RESUME) t++ }; o.addObserver(ob); onDispose { o.removeObserver(ob) } }
    return t
}
@Composable fun PermRow(ok: Boolean, label: String, onClick: () -> Unit) = Row(Modifier.fillMaxWidth(), Arrangement.SpaceBetween) {
    Text(if (ok) "✓ $label" else "✗ $label", Modifier.weight(1f)); if (!ok) TextButton(onClick) { Text("Conceder") } }
@Composable fun CheckRow(label: String, key: String, def: Boolean) {
    val p = Prefs(LocalContext.current); var v by remember { mutableStateOf(p.b(key, def)) }
    Row(Modifier.fillMaxWidth(), Arrangement.SpaceBetween) { Text(label, Modifier.weight(1f)); Switch(v, { v = it; p.setB(key, it) }) } }
fun Context.go(a: String, pkg: Boolean = false) = startActivity(Intent(a).apply { if (pkg) data = Uri.parse("package:$packageName"); addFlags(Intent.FLAG_ACTIVITY_NEW_TASK) })
fun mb(b: Long) = "%.1f MB".format(b / 1048576.0)

@Composable fun Dashboard(vm: VM) {
    val h by vm.history.collectAsState()
    Page {
        Chart("CPU (freq. relativa)", h.map { it.cpu.loadPct }, "%", 100f, Color(0xFF3E91FF))
        Chart("RAM em uso", h.map { it.mem.usedPct }, "%", 100f, Color(0xFF7C4DFF))
        Chart("Temperatura da bateria", h.map { it.bat.tempC }, "°C", 60f, Color(0xFFFF7043))
        Chart("Bateria", h.map { it.bat.pct.toFloat() }, "%", 100f, Color(0xFF4CAF50))
        if (h.lastOrNull()?.cpu?.readable == false) Note("Frequências da CPU bloqueadas pelo SELinux neste aparelho; o gráfico de CPU fica vazio (veja a aba CPU).")
    }
}

@Composable fun RamScreen(vm: VM) {
    val c = LocalContext.current; val s = vm.history.collectAsState().value.lastOrNull(); val tick = resumeTick()
    val has = remember(tick) { UsageRepo.hasAccess(c) }
    val recent = remember(tick) { if (has) UsageRepo.recent(c, 60).take(15) else emptyList() }
    val pss = Debug.MemoryInfo().also { Debug.getMemoryInfo(it) }.totalPss / 1024
    Page {
        s?.let { Panel("Memória do sistema") {
            Text("${it.mem.totalMb - it.mem.availMb} MB usados de ${it.mem.totalMb} MB  •  livres ${it.mem.availMb} MB")
            Meter(it.mem.usedPct / 100f); Text("Swap/zRAM em uso: ${it.mem.swapUsedMb} MB"); if (it.mem.low) Text("⚠ Sistema reporta pouca memória")
        } }
        Panel("Este app") { Text("PSS do Performance Lab: $pss MB") }
        Panel("Apps usados na última hora") {
            PermRow(has, "Acesso de uso (lista de apps recentes)") { c.go(Settings.ACTION_USAGE_ACCESS_SETTINGS) }
            recent.forEach { Text("• ${Games.label(c, it.first)}") }
            Note("Desde o Android 14 o app não pode ler a RAM de outros processos. Para RAM por app: `adb shell dumpsys meminfo` (ADB/Shizuku).")
        }
    }
}

@Composable fun CpuScreen(vm: VM) {
    val s = vm.history.collectAsState().value.lastOrNull() ?: return
    Page {
        Panel("Núcleos: ${s.cpu.cur.size}") {
            if (!s.cpu.readable) Note("Leitura de /sys/.../cpufreq bloqueada pelo SELinux neste aparelho. Requer ADB/Shizuku (`cat /sys/devices/system/cpu/cpu*/cpufreq/scaling_cur_freq`).")
            s.cpu.cur.forEachIndexed { i, cur -> val m = s.cpu.max[i]
                Text("CPU$i: ${cur?.let { "$it MHz" } ?: "indisponível"}${m?.let { " / $it MHz" } ?: ""}")
                if (cur != null && m != null) Meter(cur.toFloat() / m) }
        }
        Panel("Térmico") {
            val st = listOf("Nenhum", "Leve", "Moderado", "Severo", "Crítico", "Emergência", "Desligamento")
            Text("Status do sistema: ${st.getOrElse(s.thermalStatus) { "?" }}")
            Text("Margem térmica (10s): ${s.headroom?.let { "%.2f (1.0 = limite de throttling)".format(it) } ?: "não suportada"}")
            Text("Bateria: ${s.bat.tempC} °C"); Text("Zona térmica: ${s.zoneTempC?.let { "%.1f °C".format(it) } ?: "não legível sem ADB/Shizuku"}")
            Note("A temperatura da CPU/SoC do Exynos 2200 não é exposta a apps comuns.")
        }
    }
}

@Composable fun GamingScreen() {
    val c = LocalContext.current; val tick = resumeTick(); var out by remember { mutableStateOf("") }
    val games = remember(tick) { Games.list(c) }
    Page {
        Panel("Perfil de jogo") {
            PermRow(Dnd.has(c), "Acesso ao Não perturbe") { c.go(Settings.ACTION_NOTIFICATION_POLICY_ACCESS_SETTINGS) }
            PermRow(UsageRepo.hasAccess(c), "Acesso de uso (encerrar apps em cache)") { c.go(Settings.ACTION_USAGE_ACCESS_SETTINGS) }
            CheckRow("Ativar Não perturbe", "game_dnd", true); CheckRow("Encerrar apps em cache", "game_kill", true)
            Note("Não existe API pública para forçar clock/GPU. O Android pode reabrir apps encerrados. O Game Booster da Samsung é separado.")
        }
        Panel("Jogos instalados") {
            if (games.isEmpty()) Note("Nenhum app com categoria 'jogo' encontrado.")
            games.forEach { (pkg, name) -> Row(Modifier.fillMaxWidth(), Arrangement.SpaceBetween) {
                Text(name, Modifier.weight(1f))
                Button({ out = Engine.apply(c, "game", pkg); c.packageManager.getLaunchIntentForPackage(pkg)?.let { c.startActivity(it) } }) { Text("Jogar") } } }
            if (out.isNotEmpty()) Text(out)
            TextButton({ out = Engine.restore(c) }) { Text("Restaurar configurações anteriores") }
        }
    }
}

@Composable fun DebloatScreen() {
    val c = LocalContext.current; val clip = LocalClipboardManager.current; val items = remember { Debloat.installed(c) }
    Page {
        Note("Lista curada. Apps desconhecidos não são listados de propósito. Desativar exige ADB ou Shizuku; o app apenas copia o comando exato.")
        if (items.isEmpty()) Text("Nenhum app da lista curada encontrado.")
        items.forEach { (b, en) -> Panel("${b.name}  •  risco ${b.risk}  •  ${if (en) "ativo" else "desativado"}") {
            Text(b.desc); Note(b.pkg)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton({ c.startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:${b.pkg}")).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }) { Text("Info") }
                OutlinedButton({ clip.setText(AnnotatedString(if (en) Debloat.disableCmd(b.pkg) else Debloat.restoreCmd(b.pkg))) }) { Text(if (en) "Copiar desativar" else "Copiar reativar") } } } }
    }
}

@Composable fun StorageScreen() {
    val c = LocalContext.current; val tick = resumeTick(); val scope = rememberCoroutineScope()
    var junk by remember { mutableStateOf<List<JunkFile>?>(null) }
    var scanning by remember { mutableStateOf(false) }; var confirm by remember { mutableStateOf(false) }; var msg by remember { mutableStateOf("") }
    val sel = remember { mutableStateListOf<String>() }
    val (free, total) = Junk.free(); val has = remember(tick) { UsageRepo.hasAccess(c) }
    val caches = remember(tick) { if (has) Junk.cacheSizes(c) else emptyList() }
    Page {
        Panel("Armazenamento") { Text("Livre ${mb(free)} de ${mb(total)}"); Meter(1f - free.toFloat() / total) }
        Panel("Arquivos temporários (.tmp, .log, miniaturas)") {
            PermRow(Junk.allowed(), "Acesso a todos os arquivos") { c.go(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION, true) }
            Button({ scanning = true; msg = ""; sel.clear(); scope.launch { junk = withContext(Dispatchers.IO) { Junk.scan() }; scanning = false } },
                enabled = Junk.allowed() && !scanning) { Text(if (scanning) "Escaneando…" else "Escanear") }
            Note("Nada é selecionado automaticamente. Ignora a pasta Android, links e arquivos alterados na última hora.")
            junk?.let { j ->
                val shown = j.take(200); val chosen = shown.filter { it.file.path in sel }; val sum = chosen.sumOf { it.size }
                Text("${j.size} encontrados • ${chosen.size} selecionados (${mb(sum)})")
                Row { TextButton({ sel.clear(); sel.addAll(shown.map { it.file.path }) }) { Text("Selecionar tudo") }; TextButton({ sel.clear() }) { Text("Limpar seleção") } }
                shown.forEach { f -> Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(f.file.path in sel, { on -> if (on) sel.add(f.file.path) else sel.remove(f.file.path) })
                    Column { Text("${f.kind} • ${mb(f.size)}"); Note(f.file.path) } } }
                if (j.size > 200) Note("Mostrando os 200 maiores; apague e escaneie de novo para ver o resto.")
                Button({ confirm = true }, enabled = chosen.isNotEmpty()) { Text("Apagar selecionados…") }
                if (confirm) AlertDialog(onDismissRequest = { confirm = false },
                    title = { Text("Apagar ${chosen.size} arquivos?") },
                    text = { Text("${mb(sum)} serão apagados permanentemente, sem lixeira. Isso não pode ser desfeito.") },
                    confirmButton = { TextButton({ confirm = false; val del = chosen
                        scope.launch { val (n, b) = withContext(Dispatchers.IO) { Junk.delete(del) }
                            msg = "$n apagados, ${mb(b)} liberados"; sel.clear(); junk = withContext(Dispatchers.IO) { Junk.scan() } } }) { Text("Apagar") } },
                    dismissButton = { TextButton({ confirm = false }) { Text("Cancelar") } })
            }
            if (msg.isNotEmpty()) Text(msg)
        }
        Panel("Maiores caches") {
            PermRow(has, "Acesso de uso") { c.go(Settings.ACTION_USAGE_ACCESS_SETTINGS) }
            caches.forEach { Text("${it.first}: ${mb(it.second)}") }
            Note("O Android não permite limpar o cache de outros apps sem ADB/Shizuku/root. Use Configurações > Apps > Armazenamento.")
            OutlinedButton({ Junk.clearOwnCache(c) }) { Text("Limpar cache deste app") }
        }
    }
}

@Composable fun AutomationScreen() {
    val c = LocalContext.current; val p = Prefs(c); val tick = resumeTick()
    var on by remember { mutableStateOf(p.b("auto_on", false)) }; var thr by remember { mutableFloatStateOf(p.i("saver_thr", 20).toFloat()) }
    Page {
        Panel("Automação") {
            Row(Modifier.fillMaxWidth(), Arrangement.SpaceBetween) { Text("Serviço ativo"); Switch(on, { on = it; p.setB("auto_on", it)
                val i = Intent(c, AutomationService::class.java); if (it) ContextCompat.startForegroundService(c, i) else c.stopService(i) }) }
            PermRow(UsageRepo.hasAccess(c), "Acesso de uso (detectar jogo em primeiro plano)") { c.go(Settings.ACTION_USAGE_ACCESS_SETTINGS) }
            PermRow(Dnd.has(c), "Acesso ao Não perturbe") { c.go(Settings.ACTION_NOTIFICATION_POLICY_ACCESS_SETTINGS) }
            PermRow(remember(tick) { c.checkSelfPermission(Engine.SECURE) == 0 }, "WRITE_SECURE_SETTINGS (ADB)") { }
            Note("adb shell pm grant com.perflab android.permission.WRITE_SECURE_SETTINGS")
        }
        Panel("Perfil Jogo (jogo em primeiro plano)") { CheckRow("Não perturbe", "game_dnd", true); CheckRow("Encerrar apps em cache", "game_kill", true) }
        Panel("Perfil Economia (bateria ≤ ${thr.toInt()}%)") {
            Slider(thr, { thr = it; p.setI("saver_thr", it.toInt()) }, valueRange = 5f..50f)
            CheckRow("Ligar economia de energia do Android", "saver_lp", true); CheckRow("Não perturbe", "saver_dnd", false) }
        Panel("Perfil Cotidiano") { Text("Desfaz apenas o que o app ativou (DND e economia).") }
    }
}

@Composable fun DexScreen() {
    val c = LocalContext.current
    val cfg = LocalConfiguration.current
    val sem = runCatching { Configuration::class.java.getField("semDesktopModeEnabled").getInt(cfg) == 1 }.getOrDefault(false)
    val ui = c.getSystemService(UiModeManager::class.java).currentModeType == Configuration.UI_MODE_TYPE_DESKTOP
    Page {
        Panel("Estado") { Text("Modo desktop/DeX: ${if (sem || ui) "ATIVO" else "inativo"}"); Text("Largura: ${cfg.screenWidthDp} dp  •  layout ${if (cfg.screenWidthDp >= 840) "painel lateral" else "abas compactas"}") }
        Panel("Teclado e mouse") { Text("Ctrl+1…Ctrl+8: alternar abas"); Text("Janela redimensionável (resizeableActivity=true); o layout se adapta ao tamanho."); Note("Conecte um monitor/DeX para ver o layout largo.") }
    }
}
