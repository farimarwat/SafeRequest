# SafeRequest

SafeRequest is an Android library for calling your API (HTTP requests) **safely**.

Before every request it checks that the phone and your app are safe:

- no hacking tools (such as Frida) are attached to your app,
- nobody has changed or re-signed your app,
- the phone is not rooted (and, if you want, not an emulator).

Then it sends the request through its own secure (TLS) connection. Tools that try to read your
app's traffic (Charles, mitmproxy, fake certificates, proxies) cannot read it.

If something is not safe, the request is **not sent** and you get a `SecurityBlockedException`.

This repository has the **documentation** and a **sample app**. The library itself comes from
Maven Central.

---

## Contents

1. [Requirements](#1-requirements)
2. [Install](#2-install)
3. [Your first request](#3-your-first-request)
4. [Making requests](#4-making-requests)
5. [Reading the answer](#5-reading-the-answer)
6. [Handling errors](#6-handling-errors)
7. [Protection levels](#7-protection-levels)
8. [Signing keys (every app needs this)](#8-signing-keys-every-app-needs-this)
9. [Certificates and pinning](#9-certificates-and-pinning)
10. [Settings](#10-settings)
11. [Best practices](#11-best-practices)
12. [Common problems](#12-common-problems)
13. [What SafeRequest cannot do](#13-what-saferequest-cannot-do)
14. [Sample app](#14-sample-app)
15. [Licence](#15-licence)

---

## 1. Requirements

- Android 7.0 or newer (`minSdk 24`)
- Kotlin with coroutines (requests are `suspend` functions)
- Phones with arm64-v8a, armeabi-v7a or x86_64 processors (almost every phone)

## 2. Install

**Step 1.** Add the library to your app module's `build.gradle.kts`:

```kotlin
dependencies {
    implementation("io.github.farimarwat:saferequest:1.0.0")
}
```

`mavenCentral()` must be in your `settings.gradle.kts` repositories. New Android Studio projects
already have it.

**Step 2.** SafeRequest reads and writes JSON with kotlinx.serialization. Add its plugin to the
same file:

```kotlin
plugins {
    id("org.jetbrains.kotlin.plugin.serialization") version "<your Kotlin version>"
}
```

Use the same version as your Kotlin plugin (for example `2.2.0`).

**Step 3.** Sync Gradle. Done.

## 3. Your first request

```kotlin
import com.farimarwat.saferequest.ProtectionLevel
import com.farimarwat.saferequest.SafeRequest
import kotlinx.serialization.Serializable

// 1. Describe the JSON you get back
@Serializable
data class User(val id: Int, val name: String, val email: String)

// 2. Create the client (once, see "Best practices"). `context` is any Context of your app, for
//    example `applicationContext` in your Application class. Only the application Context is
//    kept, so passing an Activity never leaks it.
val api = SafeRequest(context, "https://api.example.com/") {
    protection = ProtectionLevel.MEDIUM
}

// 3. Call it from a coroutine, for example in a ViewModel
viewModelScope.launch {
    val users: List<User> = api.get("users").body()
}
```

That's it. `api.get("users")` calls `https://api.example.com/users`.

---

## 4. Making requests

### GET (read data)

```kotlin
val users: List<User> = api.get("users") {
    query("page", 1)        // adds ?page=1
    query("search", text)   // a null value is skipped
}.body()
```

### POST (send data)

Send any `@Serializable` object. It is sent as JSON.

```kotlin
@Serializable
data class NewUser(val name: String, val email: String)

val created: User = api.post("users", body = NewUser("Ann", "ann@example.com")).body()
```

### PUT, PATCH, DELETE

```kotlin
api.put("users/1", body = NewUser("Ann", "ann@new.com"))
api.patch("users/1", body = mapOf("name" to "Ann B."))
api.delete("users/1")
```

### Send a form (like an HTML form)

```kotlin
api.post("login", body = FormBody("user" to name, "pass" to password))
```

### Upload a file or photo

```kotlin
api.post("avatar", body = MultipartBody {
    field("caption", "Holiday")
    file("photo", uri)          // a File, a content:// Uri, an InputStream or bytes
}) {
    onUpload { sent, total ->
        progress = sent.toFloat() / total
    }
}
```

To send only the file, with nothing around it:

```kotlin
api.put("files/report.pdf", body = RawBody(file, "application/pdf"))
```

### Download progress

```kotlin
api.get("big-list") {
    onDownload { received, total ->
        // total is -1 if the server didn't say the size
    }
}
```

The progress functions run about 10 times a second, on the same thread you started the request
on (the main thread if you used `viewModelScope`). So you can update the UI from them.

### Headers

You can set headers in three places:

```kotlin
val api = SafeRequest(context, "https://api.example.com/") {
    // 1. Sent with every request
    header("Accept", "application/json")

    // 2. Made fresh for every request (good for a login token that changes)
    defaultHeaders { mapOf("Authorization" to "Bearer ${tokenStore.accessToken}") }
}

// 3. Only for this one request
api.get("me") { header("Accept", "text/plain") }
```

Rules:

- If the same header is set in more than one place, the one for the request wins (3 beats 2
  beats 1). Only one value is sent per name; upper or lower case doesn't matter.
- When you send a body, SafeRequest sets `Content-Type` for you. Don't set it yourself.
- Some headers are set by SafeRequest only (`Host`, `Content-Length`, ...). You can't set them.
- A header with a line break or a strange control character is refused with
  `IllegalArgumentException`, and nothing is sent.

---

## 5. Reading the answer

Every request returns the server's answer:

```kotlin
val answer = api.get("users")

answer.code             // 200, 404, 500, ...
answer.isSuccessful     // true for codes 200 to 299
answer.header("ETag")   // one header (upper or lower case doesn't matter)
answer.headers          // all headers
answer.text()           // the body as text
answer.bytes()          // the body as bytes
```

There are two ways to turn the body into your own class:

**`body<T>()`**: simple. If the code is not 2xx it throws `HttpException`.

```kotlin
val users: List<User> = api.get("users").body()
```

**`response<T>()`**: never throws for HTTP error codes. You check the code yourself.

```kotlin
val result = api.get("users/1").response<User>()
if (result.isSuccessful) {
    showUser(result.body)                 // your User
} else {
    showError(result.code, result.errorBody)  // the error text from the server
}
```

Use `body()` when an error code is unusual, and `response()` when error codes are normal for that
call (for example 404 "not found" or 422 "wrong input").

Gzip is unpacked for you. Answers are kept in memory, up to 10 MB by default
(see `maxResponseBytes`).

---

## 6. Handling errors

A request can fail in these ways:

| Exception | What it means | What to show the user |
|---|---|---|
| `SecurityBlockedException` | Not safe, so nothing was sent. `reason` is `DEVICE` (the phone or the app is not safe) or `NETWORK` (someone may be watching the network). | "For your safety, this app can't connect on this device." For `NETWORK`: "Please try another network." |
| `NetworkException` | No internet, timeout, server not found. | "No connection. Please try again." |
| `HttpException` | Only from `body()`: the server answered with an error code. Has `code` and `errorBody`. | Depends on the code. |
| `ParseException` | The answer doesn't match your class. | "Something went wrong." (and fix your class) |

All four are `SafeRequestException`, so one `catch` can handle them all:

```kotlin
viewModelScope.launch {
    try {
        val users: List<User> = api.get("users").body()
        state = UiState.Success(users)
    } catch (e: SecurityBlockedException) {
        state = UiState.Error("For your safety, this app can't connect on this device.")
    } catch (e: NetworkException) {
        state = UiState.Error("No connection. Please try again.")
    } catch (e: SafeRequestException) {
        state = UiState.Error("Something went wrong.")
    }
}
```

Why doesn't SafeRequest tell you *which* check fired? Because an attacker would read it too.
That is on purpose.

Wrong input from your own code (a bad header, a body that is too big, a wrong setting) throws
`IllegalArgumentException`. That is a bug in your code to fix, not a user problem.

---

## 7. Protection levels

> **`LOW` is not a weak level.** Everything that can read, change or record your requests is
> blocked at *every* level, `LOW` included, and can never be switched off: hacking and hooking
> tools (Frida in all its forms, Xposed / LSPosed and similar, even when they hide or attach late),
> a debugger attached to your app, a changed or re-signed copy of your app, and tools that try to
> read the connection (interception proxies, fake or installed certificates). So with `LOW` your
> requests are fully protected. `MEDIUM` and `HIGH` don't make the connection safer; they add rules
> about *which phones* your app may run on (rooted phones, emulators, unlocked bootloaders).

| Level | What it checks |
|---|---|
| `LOW` (default) | Everything that can read or change your requests (see above), plus clearly rooted phones (su, Magisk). |
| `MEDIUM` | Everything in `LOW`, plus root that tries to hide itself, and root manager apps (Magisk, KernelSU, APatch, ...). With [`strongRootCheck`](#strongrootcheck-optional-stronger-root-check), also hidden KernelSU and Magisk. |
| `HIGH` | Everything in `MEDIUM`, plus emulators and phones with an unlocked bootloader. |

**Which one should I use?**

- **`LOW`** for most apps. Your requests are fully protected, and honest users with rooted phones
  can still use your app.
- **`MEDIUM`** if your app must not run on rooted phones at all.
- **`HIGH`** for banking, payments and similar apps. (Remember: `HIGH` blocks emulators and
  phones with an unlocked bootloader, even when their owner is honest. Every root tool needs an
  unlocked bootloader first. To allow them: `ProtectionLevel.HIGH.minus(Check.DEVICE_STATE)`. Also,
  your own tests on an emulator are blocked too. See below.)

```kotlin
val api = SafeRequest(context, "https://api.example.com/") {
    protection = ProtectionLevel.HIGH
}
```

Good to know:

- SafeRequest uses only standard, well-known checks, so an honest, normal phone is not blocked.
- There is no "off" switch. You can remove one group, for example to test on an emulator:
  `ProtectionLevel.HIGH.minus(Check.EMULATOR)`. Do this in debug builds only. The hacking-tool and
  changed-app checks (`Check.HOOK`, `Check.TAMPER`) can never be removed.
- The level is set once per client. You can't change it per request.
- Certificate checks are always on, at every level.
- Once a danger is found, it stays until the app is fully closed (swiped away from Recents).
  A hacking tool or a changed app blocks **every** client in your app. Other dangers block only the
  clients whose level checks them (an emulator found by a `HIGH` client doesn't block a `LOW` one).

---

## 8. Signing keys (every app needs this)

### For release: `expectSignatures`

Tell SafeRequest which key your real app is signed with. Then, if someone changes your app and
signs it again with their own key, every request is blocked.

```kotlin
val api = SafeRequest(context, "https://api.example.com/") {
    expectSignatures("AB:12:...:9F")   // the SHA-256 of your signing key
}
```

**Where do I find it?**

- **App on Google Play:** Play Console > your app > Test and release > App integrity > App signing >
  **App signing key certificate** > SHA-256. Use this one, **not** the upload key, because Google
  signs your app again with it.
- **App not on Google Play:** run
  `keytool -list -v -keystore my-release.jks -alias my-alias` and copy the SHA256 line.

You can give more than one key (for example after changing keys). Without `expectSignatures` the
"re-signed app" check doesn't run, so always set it.

### For debug: `debugSignatures`

While you develop, Android Studio uses tools (the Run button's quick deploy, the debugger, the
profiler, Layout Inspector) that look just like hacking tools. So in a debug build **every request
is blocked with `DEVICE`**, unless you tell SafeRequest your debug key.

The easy way: let Gradle read your debug key when it builds. Copy this into your app's
`build.gradle.kts`:

```kotlin
import java.security.KeyStore
import java.security.MessageDigest

android {
    buildFeatures { buildConfig = true }
    buildTypes {
        debug {
            buildConfigField("String", "DEBUG_KEY_SHA256",
                "\"${debugKeySha256(signingConfigs.getByName("debug"))}\"")
        }
        release {
            buildConfigField("String", "DEBUG_KEY_SHA256", "\"\"")
        }
    }
}

fun debugKeySha256(config: com.android.build.api.dsl.ApkSigningConfig): String {
    val file = config.storeFile?.takeIf { it.isFile } ?: return ""
    val password = (config.storePassword ?: return "").toCharArray()
    val alias = config.keyAlias ?: return ""
    val certificate = KeyStore.getInstance(file, password).getCertificate(alias) ?: return ""
    return MessageDigest.getInstance("SHA-256").digest(certificate.encoded)
        .joinToString("") { "%02x".format(it) }
}
```

Then in your code:

```kotlin
val api = SafeRequest(context, "https://api.example.com/") {
    if (BuildConfig.DEBUG_KEY_SHA256.isNotEmpty()) {
        debugSignatures(BuildConfig.DEBUG_KEY_SHA256)
    }
}
```

Release builds are never relaxed, even with this code.

> **Debug build blocked with `DEVICE` on a normal phone?** This is almost always the reason:
> `debugSignatures` is missing or has the wrong key.

---

## 9. Certificates and pinning

SafeRequest has its own list of trusted certificate authorities. Certificates that the user (or
a tool) installed on the phone are **never** trusted. That is how it stops traffic-reading tools.

**Pinning** (optional, extra safety): only accept your server's own key.

```kotlin
val api = SafeRequest(context, "https://api.example.com/") {
    pin("api.example.com", "sha256/AAAA...=", "sha256/BBBB...=")
}
```

Always give **two pins**: the current key and a backup key. If your server's key changes and the
app knows only the old pin, every request fails until users update the app.

**Your own company certificate authority** (for an internal server):

```kotlin
val api = SafeRequest(context, "https://internal.example.com/") {
    trustedRoot(context.resources.openRawResource(R.raw.company_ca))
}
```

---

## 10. Settings

| Setting | Default | Meaning |
|---|---|---|
| `protection` | `ProtectionLevel.LOW` | See [Protection levels](#7-protection-levels). |
| `connectTimeout` | 10 s | Time to connect to the server. |
| `readTimeout` | 10 s | Longest wait for data from the server. |
| `writeTimeout` | 10 s | Longest wait while sending data. |
| `callTimeout` | 60 s | The whole request, start to end. |
| `maxResponseBytes` | 10 MB | The biggest answer accepted. |
| `maxRequestBytes` | 10 MB (at most 1 GB) | The biggest body you can send. |
| `converter` | `JsonConverter()` | How bodies are turned into JSON. Unknown JSON keys are ignored. You can write your own `Converter` (Gson, Moshi, ...). |
| `strongRootCheck` | `false` | An extra root check that also finds hidden KernelSU and Magisk (Android 10+, at `MEDIUM` and `HIGH`). **Needs one line in your `Application` class** (see below). |

```kotlin
import kotlin.time.Duration.Companion.seconds

val api = SafeRequest(context, "https://api.example.com/") {
    protection = ProtectionLevel.MEDIUM
    connectTimeout = 15.seconds
    callTimeout = 120.seconds
    maxRequestBytes = 50L * 1024 * 1024   // 50 MB uploads
}
```

### `strongRootCheck` (optional, stronger root check)

Android doesn't let a normal app look at the phone's security rules, where KernelSU and Magisk
leave marks. With `strongRootCheck = true`, SafeRequest starts a small hidden process of your app
(Android's "App Zygote"), which is allowed to look, and asks it once in the background. Requests
never wait for it.

**Required:** Android also runs your `Application.onCreate()` in that hidden process, where reading
files, databases or settings isn't allowed. Add this line right after `super.onCreate()`:

```kotlin
class MyApp : Application() {
    override fun onCreate() {
        super.onCreate()
        if (Process.isIsolated()) return   // SafeRequest's hidden process: do nothing here
        // ... your normal start-up code ...
    }
}
```

If you forget it, a debuggable build tells you with an `IllegalStateException` that shows this
line. Your users are never blocked because of it: if the hidden process fails, the check is
skipped. `minus(Check.ROOT)` removes this check too.

---

## 11. Best practices

**Set it up once**

1. **One client per server, kept for the whole app.** Create it once (in your `Application`
   class, a Hilt/Koin singleton, or a Kotlin `object`). Don't create a new one in every screen or
   every request; it keeps connections open to make requests faster. Call `close()` only if you
   really don't need it any more.
2. **Always set `expectSignatures`** in release, with the Google Play app signing key.
3. **Always set `debugSignatures`** in debug builds, as shown above. It saves you hours.
4. **Pick a level on purpose.** `LOW` already protects every request; choose `MEDIUM` or `HIGH` only if your app must refuse rooted phones or emulators.

**Making requests**

5. **Call from `viewModelScope` or `lifecycleScope`.** Then the request stops by itself when the
   screen is closed.
6. **Catch `SafeRequestException`** (or the four exceptions one by one) around every request. Never
   catch `Exception` or `Throwable`: that also catches coroutine cancellation and hides real bugs.
7. **Show simple messages** to users. Never show the exception text to them.
8. **Use `response<T>()`** when error codes are normal for a call (404, 422). Use `body<T>()`
   otherwise.
9. **Login tokens:** put them in `defaultHeaders { }`, so every request gets the newest token. To
   refresh a token after a 401, do it in your repository: refresh, then call again once.

**Uploads and downloads**

10. **Big uploads: use a `File` or a `Uri`**, not an `InputStream` (a stream is first read into
    memory).
11. **Long uploads: use WorkManager** or a foreground service. Android cuts the network when the
    screen is off for a while.
12. **Big downloads:** raise `maxResponseBytes` only as much as you need. The answer is kept in
    memory.

**Safety**

13. **Never put secrets in your app** (passwords, private API keys). Anyone can open an APK.
14. **Your server must check everything too.** SafeRequest protects the requests from your app,
    but anyone can still call your server directly with other tools.
15. **Pin with two keys** (current + backup), or don't pin at all.
16. **Don't print tokens or user data** to Logcat in your own code. SafeRequest itself never logs
    anything.

---

## 12. Common problems

| Problem | Why | Fix |
|---|---|---|
| Debug build: every request blocked with `DEVICE` | Android Studio's tools look like hacking tools. | Set `debugSignatures` ([section 8](#for-debug-debugsignatures)). |
| Release build blocked with `DEVICE` on your own phone | `expectSignatures` doesn't match the key the app is signed with. | On Google Play, use the **app signing** key, not the upload key. |
| Emulator blocked | `HIGH` blocks emulators. | In debug builds use `ProtectionLevel.HIGH.minus(Check.EMULATOR)`, or test on a real phone. |
| Your own test phone blocked at `HIGH` (not rooted) | Its bootloader is unlocked, and `HIGH` refuses unlocked phones. | Use a phone with a locked bootloader, or `ProtectionLevel.HIGH.minus(Check.DEVICE_STATE)` if your app should allow them. |
| Blocked with `NETWORK` | Someone may be reading the network, or the phone's date and time are wrong. | Fix the date and time; try another Wi-Fi or mobile data. |
| I can't see my requests in Charles / a proxy | Proxies are ignored on purpose. | Look at `code`, `headers` and `text()` in your own debug code. |
| `ParseException` | Your data class doesn't match the JSON. | Check field names and types; make fields that can be missing nullable or give them a default. |
| `IllegalArgumentException` | Wrong input in your code (bad header, body too big). | Read the message and fix the call. |
| Build error: `zygotePreloadName` conflict when merging manifests | Your app already uses its own App Zygote (rare). SafeRequest uses one for a root check on Android 10+. | Keep yours with `tools:replace="android:zygotePreloadName"` on your `<application>`. SafeRequest then skips that one root check; every other check still runs. |

---

## 13. What SafeRequest cannot do

Please read this, so you know what you get:

- It protects requests **from your app, on the phone**. It does not protect your server. Anyone
  can still call your server with other tools, so your server must check every request.
- No library on a phone can stop an expert with unlimited time. SafeRequest makes attacks slow,
  hard and costly.
- Very well hidden root can get past `LOW`, and a few root setups can get past every level.
- Apps that use their own hooking SDKs (for example ShadowHook) are blocked like Frida.
- HTTP/1.1 only. There are no cookies, cache, interceptors or WebSockets yet.

---

## 14. Sample app

The `app` folder is a small "Contacts" app. It lists users from a free public test API with
SafeRequest, using the library from Maven Central, with the debug and release key setup from
[section 8](#8-signing-keys-every-app-needs-this).

To run it:

1. Clone this repository and open it in Android Studio.
2. Press Run on a real phone (or an emulator; then use `LOW` or `MEDIUM`).
3. To try another level, change `USERS_LEVEL` in `MainActivity.kt`.

---

## 15. Licence

SafeRequest is licensed under the
[PolyForm Noncommercial License 1.0.0](https://polyformproject.org/licenses/noncommercial/1.0.0/).
Full terms: [LICENSE.md](LICENSE.md). SafeRequest includes BoringSSL and zlib under their own
licences: [THIRD_PARTY_NOTICES.md](THIRD_PARTY_NOTICES.md).

**Free for non-commercial use:** personal projects, learning, and non-profit organisations
(charities, schools, public research, government).

**Commercial use needs a commercial licence.** Commercial means any app or business that earns
money in any way: paid apps, in-app purchases, subscriptions, ads, sponsorships, or an app made for
a company or a client.

- You can **buy a commercial licence, one per app**. It is **valid for one year** from the date of
  purchase.
- **Renew it every year** to keep releasing new versions and updates of that app with SafeRequest.
- **If you don't renew:** the versions of your app you released while the licence was valid may stay
  in the stores and on your users' phones. A new release or update that contains SafeRequest needs a
  valid licence again.
- **Prices may increase or decrease** over time. A new price applies to new licences and renewals.
- To buy a licence: [farimarwat@gmail.com](mailto:farimarwat@gmail.com).

Using SafeRequest in a commercial app without a valid commercial licence infringes the author's
copyright. The author may file copyright infringement (DMCA) notices with Google Play and any other
store or host where such an app is found, which can lead to the app being removed and the developer
account being suspended under the store's own policies.
