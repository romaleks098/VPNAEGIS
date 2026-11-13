<div align="center">

# 🛡️ AegisBox VPN

**Android VPN-клиент на Kotlin и Jetpack Compose (Material 3) с ядром `sing-box` (`libbox.aar`)**  
*Адаптирован для стабильной работы Google Gemini, YouTube 4K, Telegram и прямого доступа к российским сервисам*

[![Build & Release APK](https://github.com/romaleks098/VPNAEGIS/actions/workflows/build-apk.yml/badge.svg)](https://github.com/romaleks098/VPNAEGIS/actions/workflows/build-apk.yml)
![Android](https://img.shields.io/badge/Android-8.0%2B%20(minSdk%2026)-3DDC84?logo=android&logoColor=white)
![Architecture](https://img.shields.io/badge/ABI-arm64--v8a%20%7C%2016KB%20Page-00E5FF)
![Core](https://img.shields.io/badge/Core-sing--box%20%2B%20AmneziaWG-8B5CF6)

👉 **[Скачать готовый `.apk` в разделе Releases](https://github.com/romaleks098/VPNAEGIS/releases/latest)** 👈

</div>

---

## ⚡ Ключевые возможности

- **🤖 Google Gemini без ограничений региона:** умная каскадная цепочка **`VLESS Reality → Cloudflare WARP Detour`** скрывает трафик от ТСПУ/DPI и выводит запросы через чистый IP-адрес Cloudflare WARP.
- **📺 YouTube 1080p/4K и Telegram:** авто-балансировщик **`urltest`** (проверка `gstatic.com/generate_204` каждые 180 сек, таймаут 3000 мс) с мгновенным переключением на самую быструю ноду.
- **🏦 Российские сервисы напрямую:** поприложенческий **Белый список (Per-App Whitelist)** через `VpnService.Builder.addAllowedApplication()` + прямой DNS `77.88.8.8` для `.ru`, `.рф`, Госуслуг и банков.
- **🔒 Нулевые утечки IPv6 и DNS:** TUN-интерфейс `172.19.0.1/30` со строгим **`ipv4_only`**, виртуальный пул **`FakeIP 198.18.0.0/15`** и удалённый DoH (`https://1.1.1.1/dns-query`) строго через активный прокси-туннель.
- **🔄 Автосбор бесплатных нод каждые 3 часа:** фоновый `WorkManager` + `OkHttp` парсит Base64, Clash YAML и URI-ссылки (`vless://`, `hy2://`, `trojan://`, `vmess://`, `ss://`), фильтрует по странам (**US, DE, NL, FI**) и приоритезирует обфускацию (`Reality`, `Hysteria2`, `WS+TLS`, `gRPC`).
- **🌀 Генератор Cloudflare WARP + AmneziaWG:** встроенная регистрация бесплатного профиля WARP с обфускацией заголовков пакетов (`Jc`, `Jmin`, `Jmax`, `S1`, `S2`, `H1–H4`).
- **📱 Системная интеграция Android:** плитка быстрых настроек (`TileService`), постоянное уведомление с живой скоростью и кнопкой **«Сменить ноду»**, быстрый переход к очистке кэша Google/Gemini.

---

## 📥 Как скачать и установить APK

1. Перейдите в блок **Releases** справа на странице репозитория (или нажмите **[Latest Release](https://github.com/romaleks098/VPNAEGIS/releases/latest)**).
2. Скачайте файл **`AegisBox-VPN-v2.6.0-RU-release.apk`**.
3. Откройте файл на Android-смартфоне (Android 8.0+, `arm64-v8a`) и разрешите установку из этого источника.

> **Примечание для сборки с нативным ядром `libbox.aar`:** поместите официальный файл `libbox.aar` (из релизов `SagerNet/sing-box`) в папку `app/libs/libbox.aar` — workflow GitHub Actions автоматически включит его в релизный APK.

---

## 🗂 Структура проекта

| Модуль | Назначение |
| :--- | :--- |
| `app/src/main/java/com/example/vpn/MyVpnService.kt` | Системный `VpnService`, TUN `172.19.0.1/30`, Per-App Whitelist, Foreground-уведомление |
| `app/src/main/java/com/example/vpn/SingBoxConfigBuilder.kt` | Генератор JSON-конфига `sing-box` (`FakeIP`, `ipv4_only`, `urltest`, `VLESS -> WARP`) |
| `app/src/main/java/com/example/vpn/LibboxBridge.kt` | Мост интеграции с нативным `io.nekohasekai.libbox.Libbox` (`libbox.aar`) |
| `app/src/main/java/com/example/vpn/VpnTileService.kt` | Плитка быстрых настроек в шторке Android (`TileService`) |
| `app/src/main/java/com/example/data/FreeServersRepository.kt` | Загрузчик подписок GitHub, парсер URI/Base64/YAML, генератор WARP + AmneziaWG |
| `app/src/main/java/com/example/data/ServerSyncWorker.kt` | Фоновая синхронизация и тестирование нод каждые 3 часа (`WorkManager`) |
| `app/src/main/java/com/example/ui/screens/` | Экраны Jetpack Compose: `MainScreen.kt`, `AppsScreen.kt`, `SourcesScreen.kt` |
| `.github/workflows/build-apk.yml` | CI/CD сборка подписанного `release.apk` и публикация в **GitHub Releases** |
