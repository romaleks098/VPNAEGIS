<div align="center">

# 🛡️ AegisBox VPN — v3.0.0 Liquid Glass

**Android VPN-клиент на Kotlin и Jetpack Compose с нативным ядром `sing-box` (`libbox`) и интерфейсом «Жидкое стекло»**  
*Адаптирован для стабильной работы Google Gemini, YouTube 4K, Telegram и прямого доступа к российским сервисам*

[![Build & Release APK](https://github.com/romaleks098/VPNAEGIS/actions/workflows/build-apk.yml/badge.svg)](https://github.com/romaleks098/VPNAEGIS/actions/workflows/build-apk.yml)
![Android](https://img.shields.io/badge/Android-8.0%2B%20(minSdk%2026)-3DDC84?logo=android&logoColor=white)
![Architecture](https://img.shields.io/badge/ABI-arm64--v8a%20%7C%2016KB%20Page-00E5FF)
![Core](https://img.shields.io/badge/Core-sing--box%201.12%2B%20(libbox)-8B5CF6)

👉 **[Скачать готовый `.apk` в разделе Releases](https://github.com/romaleks098/VPNAEGIS/releases/latest)** 👈

</div>

---

## ⚡ Что нового в v3.0.0 (Liquid Glass & Real Core)

- **🚀 Реальное ядро `sing-box` (`net.clever-vpn:libbox-android:2.1.5`):** нативный `PlatformInterface` (`openTun` с `detachFd()`, `protect(fd)`, монитор сети), `CommandServer` и `CommandClient` с живой скоростью, логами и `urlTest`.
- **🤖 Google Gemini без ограничений региона:** каскадная цепочка **`VLESS Reality → Cloudflare WARP`** в корневой секции `endpoints[]` с реальной регистрацией X25519 WARP API и блокировкой утечного QUIC (`UDP 443`).
- **🏦 Российские сервисы напрямую:** строгий **Белый список (Per-App Whitelist)** только через `addAllowedApplication()`, опция **«Системные зависимости (GMS / WebView)»** и прямой DNS `77.88.8.8` для `.ru`, `.рф`, Госуслуг и банков.
- **🔒 Защита от утечек IPv6 и DNS:** TUN `172.19.0.1/30`, строго **`ipv4_only`**, **`FakeIP 198.18.0.0/15`** и удалённый DoH (`https://1.1.1.1/dns-query`).
- **💎 Дизайн «Жидкое стекло» (Liquid Glass):** динамический фон `FluidMeshBackground`, аппаратное размытие `Haze`, анимированная Hero-сфера и парящий стеклянный док-бар.

---

## 📥 Как скачать и установить APK

1. Перейдите в блок **Releases** справа на странице репозитория (или нажмите **[Latest Release](https://github.com/romaleks098/VPNAEGIS/releases/latest)**).
2. Скачайте файл **`AegisBox-VPN-v3.0.0-LiquidGlass-release.apk`**.
3. Откройте файл на Android-смартфоне (Android 8.0+, `arm64-v8a`) и разрешите установку из этого источника.

---

## 🗂 Структура проекта

| Модуль | Назначение |
| :--- | :--- |
| `app/src/main/java/com/example/vpn/LibboxBridge.kt` | Интеграция с `io.nekohasekai.libbox.*` (`PlatformInterface`, `CommandServer`, `CommandClient`) |
| `app/src/main/java/com/example/vpn/MyVpnService.kt` | Foreground `VpnService` (`specialUse`), TUN, строгий Whitelist, потоковые логи |
| `app/src/main/java/com/example/vpn/SingBoxConfigBuilder.kt` | Генератор валидного JSON `sing-box` 1.12–1.14+ (`endpoints`, `FakeIP`, `ipv4_only`, `urltest`) |
| `app/src/main/java/com/example/vpn/X25519.kt` | Чистая реализация RFC 7748 Curve25519 для реальной регистрации Cloudflare WARP |
| `app/src/main/java/com/example/data/FreeServersRepository.kt` | Загрузка подписок, парсер URI/Base64/JSON/YAML, честный TCP RTT, WARP API |
| `app/src/main/java/com/example/ui/components/LiquidGlass.kt` | Компоненты Liquid Glass: `FluidMeshBackground`, `LiquidGlassCard`, `FloatingLiquidDock` |
| `app/src/main/java/com/example/ui/screens/` | Экраны Jetpack Compose: `MainScreen.kt`, `AppsScreen.kt`, `SourcesScreen.kt` |
| `.github/workflows/build-apk.yml` | CI/CD сборка подписанного `release.apk` и публикация в **GitHub Releases** |
