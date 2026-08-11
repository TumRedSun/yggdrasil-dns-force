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

## Что делает модификация

Добавлен **локальный DNS-прокси** внутри приложения Yggdrasil:

1. При включении VPN запускается UDP DNS-прокси, слушающий на
   Yggdrasil-адресе телефона (порт 53).
2. Этот же адрес регистрируется как системный DNS через
   `VpnService.Builder.addDnsServer()`.
3. Android-резолвер отправляет DNS-запросы на локальный адрес → прокси
   получает их (без входа в TUN, т.к. адрес локальный).
4. Прокси пересылает запрос через Yggdrasil-туннель на ваш upstream DNS
   (по умолчанию — dnsmasq на домашнем сервере).
5. Ответ возвращается обратно в резолвер.

Дополнительно:
- Убран вызов `allowBypass()` — теперь приложения не могут обходить VPN.
- Добавлен UI-переключатель "Force all apps to use Yggdrasil DNS" в разделе DNS.
- Добавлено поле "Upstream DNS server" — туда вписывается Yggdrasil-IP вашего
  dnsmasq (по умолчанию уже подставлен `20b:14e7:9d48:1c5f:490:56b1:6e5a:6ee9`).

## Изменённые файлы

| Файл | Что изменено |
|------|--------------|
| `app/src/main/java/eu/neilalexander/yggdrasil/PacketTunnelProvider.kt` | Добавлен класс `DnsProxy`, убран `allowBypass()`, добавлена логика force-DNS |
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
VPN-DNS. Рекомендуется выставить Private DNS в **"Выкл"** или
**"Автоматически"** (в режиме "Автоматически" Android попробует DoT на
порт 853, не получит ответа от нашего локального прокси и откатится на
обычный DNS на порт 53, который прокси обработает).

## Если что-то не работает

### Проверьте логи

```bash
adb logcat -s PacketTunnelProvider
```

Должны увидеть:
- `DnsProxy listening on /200:xxxx:xxxx:xxxx:xxxx:xxxx:xxxx:xxxx:53`
- `DnsProxy forwarder ready, upstream=/20b:14e7:9d48:1c5f:490:56b1:6e5a:6ee9`
- При DNS-запросах: `DnsProxy query forward failed: ...` (если upstream недоступен)

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
