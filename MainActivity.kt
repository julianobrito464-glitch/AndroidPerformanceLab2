package com.perflab

import android.Manifest
import android.content.Intent
import android.app.Application
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.*
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.*
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import com.perflab.core.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch

class VM(app: Application) : AndroidViewModel(app) {
    val history = MutableStateFlow<List<Snap>>(emptyList())
    init { viewModelScope.launch { Sampler(app).flow(1000).collect { history.value = (history.value + it).takeLast(90) } } }
}

class MainActivity : ComponentActivity() {
    override fun onCreate(s: Bundle?) {
        super.onCreate(s); enableEdgeToEdge()
        requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 1)
        // Recuperação: se a automação está ligada, garante o serviço; se está desligada, desfaz alterações pendentes (ex.: processo morto).
        if (Prefs(this).b("auto_on", false)) ContextCompat.startForegroundService(this, Intent(this, AutomationService::class.java))
        else Engine.restore(this)
        setContent {
            MaterialTheme(colorScheme = darkColorScheme(primary = Color(0xFF3E91FF), background = Color(0xFF0B0D12),
                surface = Color(0xFF171A21), surfaceVariant = Color(0xFF1F232C), onBackground = Color.White, onSurface = Color.White)) {
                Surface(color = MaterialTheme.colorScheme.background) { Root() }
            }
        }
    }
}

val tabs = listOf("Painel", "RAM", "CPU", "Jogos", "Debloat", "Disco", "Auto", "DeX")

@Composable fun Root(vm: VM = viewModel()) {
    var tab by remember { mutableIntStateOf(0) }
    val wide = LocalConfiguration.current.screenWidthDp >= 840   // monitor externo / DeX / tablet
    val fr = remember { FocusRequester() }
    LaunchedEffect(Unit) { fr.requestFocus() }
    val keys = listOf(Key.One, Key.Two, Key.Three, Key.Four, Key.Five, Key.Six, Key.Seven, Key.Eight)
    val content: @Composable () -> Unit = {
        when (tab) {
            0 -> Dashboard(vm); 1 -> RamScreen(vm); 2 -> CpuScreen(vm); 3 -> GamingScreen(); 4 -> DebloatScreen()
            5 -> StorageScreen(); 6 -> AutomationScreen(); else -> DexScreen()
        }
    }
    Box(Modifier.fillMaxSize().systemBarsPadding().focusRequester(fr).focusable().onPreviewKeyEvent { e ->
        val i = keys.indexOf(e.key)
        if (e.type == KeyEventType.KeyDown && e.isCtrlPressed && i >= 0) { tab = i; true } else false }) {
        if (wide) Row {
            NavigationRail(containerColor = MaterialTheme.colorScheme.surface) {
                tabs.forEachIndexed { i, t -> NavigationRailItem(selected = tab == i, onClick = { tab = i },
                    icon = { Text("^${i + 1}") }, label = { Text(t) }) }
            }
            Box(Modifier.weight(1f)) { content() }
        } else Column {
            ScrollableTabRow(selectedTabIndex = tab, containerColor = MaterialTheme.colorScheme.background, edgePadding = 8.dp) {
                tabs.forEachIndexed { i, t -> Tab(selected = tab == i, onClick = { tab = i }, text = { Text(t) }) }
            }
            Box(Modifier.weight(1f)) { content() }
        }
    }
}
