# Yggdrasil Android — модифицированная сборка для принудительного системного DNS

## Зачем это нужно

Стандартное приложение Yggdrasil для Android (зелёный листок) умеет добавлять
DNS-серверы через `VpnService.Builder.addDnsServer()`, но на Android 13 это
**не работает для не-Chrome приложений** (Element, SchildiChat, Termux и т.д.).

Причина: Android 13 имеет функцию "Private DNS" (DoT), которая перехватывает
DNS-запросы на уровне резолвера **до** того, как VpnService сможет их
перенаправить. Опция "Обхитрить браузеры на основе Chrome" в Yggdrasil
работает только потому, что перехватывает DoH-трафик самого Chrome — на
остальные приложения она не влияет.

## Что делает модификация (DNS Hijacking)

Используется **перехват DNS на уровне VPN-пакетов** (DNS hijacking) —
подход, который применяют приложения Intra (Google/Jigsaw) и Blokada:

1. Регистрируется **фейковый DNS-сервер** с IP-адресом `200::53` (внутри
   диапазона Yggdrasil `0200::/7`, но не является реальным узлом).
2. Этот IP указывается как DNS-сервер VPN через `addDnsServer("200::53")`.
3. Системный резолвер Android отправляет DNS-запросы на `200::53:53`.
   Эти пакеты попадают в TUN (т.к. `200::53` входит в маршрут `200::/7`).
4. **Поток-читатель** (reader thread) перехватывает каждый IPv6/UDP-пакет,
   направленный на `200::53:53`, **до** передачи в Yggdrasil.
5. DNS-запрос извлекается и пересылается через `DatagramSocket` на upstream
   DNS-сервер (dnsmasq на домашнем сервере, доступный через Yggdrasil).
6. Полученный ответ упаковывается в новый IPv6/UDP-пакет
   (source=`200::53:53`, dest=IP клиента) и записывается обратно в TUN.
   Ядро Android доставляет его системному резолверу.

### Почему именно перехват пакетов, а не локальный прокси на порту 53?

Первая версия модификации пыталась запустить локальный UDP DNS-прокси на
порту 53 (привязка к Yggdrasil-IP телефона). Это **не работает**, потому что
порт 53 — привилегированный порт в Android, и непривилегированные приложения
(включая VpnService) получают `EACCES (Permission denied)` при попытке
привязки (`bind`).

Перехват на уровне пакетов **не требует привязки к порту 53** — перехват
происходит в потоке-читателе, который просто читает сырые IP-пакеты из TUN.

### Почему это обходит Private DNS (DoT)?

Private DNS пытается Upgrade'нуть DNS до DoT (порт 853). Но наш фейковый
DNS-сервер `200::53` не отвечает на порту 853 — DoT-попытка завершается
таймаутом, и Android откатывается на обычный DNS (порт 53), который мы
перехватываем. Для мгновенного ответа рекомендуется выставить Private DNS
в "Выкл", но даже в "Автоматически" режиме всё работает (с небольшой
задержкой на таймаут DoT).

Дополнительно:
- Убран вызов `allowBypass()` — теперь приложения не могут обходить VPN.
- Добавлен UI-переключатель "Force all apps to use Yggdrasil DNS" в разделе DNS.
- Добавлено поле "Upstream DNS server" — туда вписывается Yggdrasil-IP вашего
  dnsmasq (по умолчанию уже подставлен `20b:14e7:9d48:1c5f:490:56b1:6e5a:6ee9`).

## Изменённые файлы

| Файл | Что изменено |
|------|--------------|
| `app/src/main/java/eu/neilalexander/yggdrasil/PacketTunnelProvider.kt` | Добавлен класс `DnsHijacker` (перехват DNS-пакетов), убран `allowBypass()`, добавлен `writePacketToTun()` (thread-safe запись в TUN), изменён `reader()` для перехвата |
| `app/src/main/java/eu/neilalexander/yggdrasil/DnsActivity.kt` | Добавлены UI-обработчики для новых настроек |
| `app/src/main/res/layout/activity_dns.xml` | Добавлена секция "Force system DNS" с тумблером и полем upstream |
| `app/src/main/res/values/strings.xml` | Добавлены английские строки |
| `app/src/main/res/values-ru/strings.xml` | Добавлены русские строки |
| `libs/yggdrasil-go/contrib/mobile/build` | Добавлен флаг `-androidapi 21` для совместимости с NDK r23+ |

## Что уже собрано за вас

В архиве **уже лежит собранный `app/libs/yggdrasil.aar`** (17 МБ) с нативными
библиотеками для armeabi-v7a, arm64-v8a, x86, x86_64. Вам **НЕ НУЖНО**
устанавливать Go, gomobile, gobind или Android NDK. Нужен только Android SDK
(для Gradle) и JDK 11+.

## Как собрать APK

### Зависимости

- Android SDK (compileSdk 34, build-tools 34.0.0) — обычно ставится через
  Android Studio или `sdkmanager`
- JDK 11 или новее (17 рекомендуется)

### Сборка

```bash
cd yggdrasil-android

# Если JAVA_HOME не задан:
export JAVA_HOME=/usr/lib/jvm/java-11-openjdk    # или путь к JDK 17

# Собираем debug APK
./gradlew assembleDebug
```

Готовый APK: `app/build/outputs/apk/debug/app-debug.apk`

### Сборка release-варианта (с подписью)

В `app/build.gradle` уже настроен signingConfig `yggdrasil` с захардкоженным
паролем. Просто соберите:

```bash
./gradlew assembleYggdrasil
```

APK: `app/build/outputs/apk/yggdrasil/app-yggdrasil.apk`

### Если Gradle жалуется на отсутствие SDK

Задайте `ANDROID_HOME` или `ANDROID_SDK_ROOT`:

```bash
export ANDROID_HOME=$HOME/Android/Sdk
export ANDROID_SDK_ROOT=$ANDROID_HOME
./gradlew assembleDebug
```

Либо создайте файл `local.properties` в корне проекта со строкой:
```
sdk.dir=/home/ВАШ_ПОЛЬЗОВАТЕЛЬ/Android/Sdk
```

## Как использовать

1. Установите собранный APK на телефон.
2. Откройте приложение → тапните "DNS servers" на главном экране.
3. В новом разделе "Force system DNS":
   - Включите тумблер "Force all apps to use Yggdrasil DNS".
   - Тапните "Upstream DNS server" и убедитесь, что там указан
     `20b:14e7:9d48:1c5f:490:56b1:6e5a:6ee9` (или измените на свой).
4. Вернитесь на главный экран, **выключите и включите** Yggdrasil.
5. Откройте Element/SchildiChat/Termux — `matrix.tulen.chat` теперь должен
   резолвиться.

## Дополнительно: Android Private DNS

Даже с этой модификацией, если в настройках Android
(Настройки → Сеть и интернет → Private DNS) указан конкретный DoT-сервер
(например, `dns.adguard.com`), Android может использовать его вместо
VPN-DNS. Рекомендуется выставить Private DNS в **"Выкл"** для максимальной
скорости (DoT-попытка на `200::53:853` завершится таймаутом, но в режиме
"Автоматически" это добавляет задержку ~3-5 секунд при первом запросе).

## Если что-то не работает

### Проверьте логи

```bash
adb logcat -s PacketTunnelProvider
```

Должны увидеть:
- `DnsHijacker started, fake DNS IP=200::53, upstream=...`
- `DnsHijacker forwarder ready, upstream=...`
- `Force system DNS enabled (hijack mode), upstream=...`
- При DNS-запросах: `DNS query intercepted: srcPort=..., queryLen=...`
- При ответах: `DNS response injected: dstPort=..., respLen=...`
- При ошибке upstream: `DnsHijacker query forward failed: ...`

### Проверьте, что dnsmasq слушает на Yggdrasil-интерфейсе

На сервере:
```bash
ss -ulnp | grep :53
```
Должно быть видно `tun0` или Yggdrasil-IP в списке интерфейсов.

### Проверьте, что порт 53 открыт на Yggdrasil-интерфейсе сервера

```bash
# На сервере, проверить фаервол:
sudo ip6tables -L -n | grep 53
```

Если используется ufw или nftables — убедитесь, что UDP порт 53 открыт для
трафика из подсети `200::/7`.
