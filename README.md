# Android Performance Lab
Kotlin + Compose, módulos `:core` (coleta/ações) e `:app` (UI/serviço). minSdk 34, compile 36. Sem root.

## Compilar o APK debug (precisa de internet na 1ª vez)
Requisitos: JDK 17+, Android SDK 36 (Android Studio instala).
- **Android Studio:** File > Open > pasta do projeto > Sync > Build > Build APK(s).
- **Terminal:** `./gradlew assembleDebug` (Windows: `gradlew.bat assembleDebug`). Defina `ANDROID_HOME` e aceite as licenças (`sdkmanager --licenses`).
- **Sem Android Studio:** envie a pasta a um repositório GitHub; o workflow `.github/workflows/build-apk.yml` compila e publica `app-debug.apk` em Actions > Artifacts.
- Saída: `app/build/outputs/apk/debug/app-debug.apk` (assinado com a chave debug, instalável por `adb install`).
- `gradlew` baixa `gradle-wrapper.jar` oficial (Gradle 8.11.1) se ele não estiver na pasta. Alternativa: `gradle wrapper --gradle-version 8.11.1`.

## Restauração de configurações
Antes da primeira mudança o app guarda o estado real do Não perturbe e da economia de energia. Voltar ao perfil cotidiano, desligar o serviço, o botão Restaurar ou reabrir o app (se a automação estiver desligada) devolvem esse estado. Se você mudou a configuração manualmente enquanto o perfil estava ativo, o app respeita a sua mudança. Apps encerrados no Modo Jogo não podem ser "restaurados".

## O que cada recurso realmente faz
| Recurso | Sem nada extra | Precisa de ADB / Shizuku / root |
|---|---|---|
| RAM total/livre/swap, PSS do próprio app | Sim (ActivityManager, /proc/meminfo) | RAM de outros apps: `dumpsys meminfo` (ADB/Shizuku) |
| Apps recentes | Sim, com Acesso de uso | — |
| CPU frequência/núcleos | Só se o SELinux permitir ler cpufreq (muitos aparelhos bloqueiam; o app avisa) | Leitura garantida: ADB/Shizuku |
| Carga real da CPU | Não (/proc/stat bloqueado desde Android 8). O app mostra freq. atual/máx. e rotula assim | ADB/Shizuku (`top`, `/proc/stat`) |
| Temperatura | Bateria + status/margem térmica oficiais | Temperatura do SoC: ADB/Shizuku |
| Modo Jogo | DND (permissão), encerrar apps em cache (efeito limitado) | Forçar clock/GPU: não há API; Game Booster é da Samsung |
| Debloat | Lista, explicação, abre Info do app | Desativar: `adb shell pm disable-user --user 0 PKG` (copiado pelo app) |
| Storage | Escanear/apagar .tmp/.log/miniaturas (Acesso a todos os arquivos); limpar cache próprio | Limpar cache de outros apps: ADB/Shizuku/root |
| Automação | Detecta jogo/bateria e aplica DND | Economia de energia: `adb shell pm grant com.perflab android.permission.WRITE_SECURE_SETTINGS` |
| DeX | Layout adaptativo, atalhos Ctrl+1..8, detecção do modo | — |

Observações: `QUERY_ALL_PACKAGES` e `MANAGE_EXTERNAL_STORAGE` são restritas na Google Play; para instalação direta (sideload) funcionam normalmente.
Shizuku ainda NÃO está integrado: nenhuma função do app finge usá-lo.
