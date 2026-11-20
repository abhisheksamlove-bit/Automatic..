name: Build APK
on: [workflow_dispatch, push]
jobs:
  build:
    runs-on: ubuntu-latest
    steps:
      - uses: actions/checkout@v4
      - name: Project files banao
        run: |
          mkdir -p $(dirname settings.gradle.kts)
          cat > settings.gradle.kts <<'EOF'
          pluginManagement {
              repositories { google(); mavenCentral(); gradlePluginPortal() }
              plugins {
                  id("com.android.application") version "8.5.2"
                  id("org.jetbrains.kotlin.android") version "1.9.24"
              }
          }
          dependencyResolutionManagement { repositories { google(); mavenCentral() } }
          rootProject.name = "AutoPlayer"
          include(":app")
          EOF
          mkdir -p $(dirname gradle.properties)
          cat > gradle.properties <<'EOF'
          android.useAndroidX=true
          EOF
          mkdir -p $(dirname app/build.gradle.kts)
          cat > app/build.gradle.kts <<'EOF'
          plugins { id("com.android.application"); id("org.jetbrains.kotlin.android") }
          android {
              namespace = "com.example.autoplayer"
              compileSdk = 34
              defaultConfig { applicationId = "com.example.autoplayer"; minSdk = 30; targetSdk = 34; versionCode = 1; versionName = "1.0" }
              compileOptions { sourceCompatibility = JavaVersion.VERSION_17; targetCompatibility = JavaVersion.VERSION_17 }
              kotlinOptions { jvmTarget = "17" }
          }
          EOF
          mkdir -p $(dirname app/src/main/AndroidManifest.xml)
          cat > app/src/main/AndroidManifest.xml <<'EOF'
          <?xml version="1.0" encoding="utf-8"?>
          <manifest xmlns:android="http://schemas.android.com/apk/res/android">
              <uses-permission android:name="android.permission.INTERNET"/>
              <queries>
                  <intent><action android:name="android.intent.action.MAIN"/><category android:name="android.intent.category.LAUNCHER"/></intent>
              </queries>
              <application android:label="Auto Player" android:theme="@android:style/Theme.Material.Light.NoActionBar">
                  <activity android:name=".MainActivity" android:exported="true">
                      <intent-filter>
                          <action android:name="android.intent.action.MAIN"/>
                          <category android:name="android.intent.category.LAUNCHER"/>
                      </intent-filter>
                  </activity>
                  <service android:name=".AutoPlayService" android:exported="false"
                      android:label="Auto Player" android:permission="android.permission.BIND_ACCESSIBILITY_SERVICE">
                      <intent-filter><action android:name="android.accessibilityservice.AccessibilityService"/></intent-filter>
                      <meta-data android:name="android.accessibilityservice" android:resource="@xml/accessibility_config"/>
                  </service>
              </application>
          </manifest>
          EOF
          mkdir -p $(dirname app/src/main/res/xml/accessibility_config.xml)
          cat > app/src/main/res/xml/accessibility_config.xml <<'EOF'
          <?xml version="1.0" encoding="utf-8"?>
          <accessibility-service xmlns:android="http://schemas.android.com/apk/res/android"
              android:accessibilityEventTypes="typeWindowStateChanged"
              android:accessibilityFeedbackType="feedbackGeneric"
              android:canPerformGestures="true"
              android:canTakeScreenshot="true"
              android:description="Game ke liye screen dekhta hai aur tap karta hai"/>
          EOF
          mkdir -p $(dirname app/src/main/java/com/example/autoplayer/AutoPlayService.kt)
          cat > app/src/main/java/com/example/autoplayer/AutoPlayService.kt <<'EOF'
          package com.example.autoplayer

          import android.accessibilityservice.AccessibilityService
          import android.accessibilityservice.GestureDescription
          import android.graphics.Bitmap
          import android.graphics.Path
          import android.util.Base64
          import android.view.Display
          import android.view.accessibility.AccessibilityEvent
          import org.json.JSONArray
          import org.json.JSONObject
          import java.io.ByteArrayOutputStream
          import java.net.HttpURLConnection
          import java.net.URL
          import java.util.concurrent.CountDownLatch
          import java.util.concurrent.Executors
          import java.util.concurrent.TimeUnit

          class AutoPlayService : AccessibilityService() {
              companion object {
                  var instance: AutoPlayService? = null
                  @Volatile var running = false
                  var apiKey = ""
                  var status = "Ruka hua"
                  const val MODEL = "claude-sonnet-5-5"
                  const val MAX_STEPS = 500
              }

              override fun onServiceConnected() { instance = this }
              override fun onAccessibilityEvent(e: AccessibilityEvent?) {}
              override fun onInterrupt() { running = false }
              override fun onDestroy() { running = false; instance = null; super.onDestroy() }

              fun startPlaying() {
                  if (running) return
                  running = true
                  Thread {
                      var steps = 0
                      val history = ArrayList<String>()
                      while (running && steps < MAX_STEPS) {
                          try {
                              val shot = screenshot() ?: run { Thread.sleep(1000); null } ?: continue
                              val scale = 720f / shot.width
                              val small = Bitmap.createScaledBitmap(shot, 720, (shot.height * scale).toInt(), true)
                              val cmd = askClaude(small, history)
                              status = "Step ${steps + 1}: ${cmd.optString("action")}"
                              history.add(cmd.toString()); if (history.size > 6) history.removeAt(0)
                              when (cmd.optString("action")) {
                                  "tap" -> tap(cmd.getDouble("x") / scale, cmd.getDouble("y") / scale)
                                  "swipe" -> swipe(cmd.getDouble("x1") / scale, cmd.getDouble("y1") / scale,
                                                   cmd.getDouble("x2") / scale, cmd.getDouble("y2") / scale)
                                  "wait" -> Thread.sleep(1500)
                                  "done" -> { running = false; status = "Game poora ho gaya" }
                              }
                              steps++
                              Thread.sleep(700)
                          } catch (t: Throwable) {
                              status = "Error: ${t.message}"; Thread.sleep(2000)
                          }
                      }
                      if (status.startsWith("Step")) status = "Ruka hua"
                      running = false
                  }.start()
              }

              private fun screenshot(): Bitmap? {
                  var result: Bitmap? = null
                  val latch = CountDownLatch(1)
                  takeScreenshot(Display.DEFAULT_DISPLAY, Executors.newSingleThreadExecutor(),
                      object : TakeScreenshotCallback {
                          override fun onSuccess(s: ScreenshotResult) {
                              result = Bitmap.wrapHardwareBuffer(s.hardwareBuffer, s.colorSpace)
                                  ?.copy(Bitmap.Config.ARGB_8888, false)
                              s.hardwareBuffer.close(); latch.countDown()
                          }
                          override fun onFailure(code: Int) { latch.countDown() }
                      })
                  latch.await(5, TimeUnit.SECONDS)
                  return result
              }

              private fun askClaude(bmp: Bitmap, history: List<String>): JSONObject {
                  val out = ByteArrayOutputStream()
                  bmp.compress(Bitmap.CompressFormat.JPEG, 70, out)
                  val b64 = Base64.encodeToString(out.toByteArray(), Base64.NO_WRAP)
                  val prompt = "Ye ek offline mobile game ki screen hai (${bmp.width}x${bmp.height} px). " +
                      "Tum game khud khel rahe ho aur use jeetna/complete karna hai. Menu, 'play', 'next level', " +
                      "'continue' jaise buttons bhi tap karo. Sirf ek JSON do, aur kuch nahi: " +
                      "{\"action\":\"tap\",\"x\":..,\"y\":..} ya {\"action\":\"swipe\",\"x1\":..,\"y1\":..,\"x2\":..,\"y2\":..} " +
                      "ya {\"action\":\"wait\"} ya {\"action\":\"done\"} (jab saare levels khatam hon). " +
                      "Pichle actions: ${history.joinToString()}. Agar same action se kuch nahi badla, kuch alag try karo."
                  val body = JSONObject()
                      .put("model", MODEL).put("max_tokens", 200)
                      .put("messages", JSONArray().put(JSONObject().put("role", "user").put("content", JSONArray()
                          .put(JSONObject().put("type", "image").put("source", JSONObject()
                              .put("type", "base64").put("media_type", "image/jpeg").put("data", b64)))
                          .put(JSONObject().put("type", "text").put("text", prompt)))))
                  val c = URL("https://api.anthropic.com/v1/messages").openConnection() as HttpURLConnection
                  c.requestMethod = "POST"; c.doOutput = true; c.connectTimeout = 15000; c.readTimeout = 60000
                  c.setRequestProperty("content-type", "application/json")
                  c.setRequestProperty("x-api-key", apiKey)
                  c.setRequestProperty("anthropic-version", "2023-06-01")
                  c.outputStream.use { it.write(body.toString().toByteArray()) }
                  val resp = (if (c.responseCode in 200..299) c.inputStream else c.errorStream).bufferedReader().readText()
                  if (c.responseCode !in 200..299) throw RuntimeException("API ${c.responseCode}")
                  val text = JSONObject(resp).getJSONArray("content").getJSONObject(0).getString("text")
                  return JSONObject(text.substring(text.indexOf('{'), text.lastIndexOf('}') + 1))
              }

              private fun tap(x: Double, y: Double) {
                  val p = Path().apply { moveTo(x.toFloat(), y.toFloat()) }
                  dispatchGesture(GestureDescription.Builder()
                      .addStroke(GestureDescription.StrokeDescription(p, 0, 60)).build(), null, null)
              }

              private fun swipe(x1: Double, y1: Double, x2: Double, y2: Double) {
                  val p = Path().apply { moveTo(x1.toFloat(), y1.toFloat()); lineTo(x2.toFloat(), y2.toFloat()) }
                  dispatchGesture(GestureDescription.Builder()
                      .addStroke(GestureDescription.StrokeDescription(p, 0, 300)).build(), null, null)
              }
          }
          EOF
          mkdir -p $(dirname app/src/main/java/com/example/autoplayer/MainActivity.kt)
          cat > app/src/main/java/com/example/autoplayer/MainActivity.kt <<'EOF'
          package com.example.autoplayer

          import android.app.Activity
          import android.content.Intent
          import android.content.pm.PackageManager
          import android.os.Bundle
          import android.os.Handler
          import android.os.Looper
          import android.provider.Settings
          import android.widget.*

          class MainActivity : Activity() {
              private lateinit var statusView: TextView
              private val ui = Handler(Looper.getMainLooper())

              override fun onCreate(b: Bundle?) {
                  super.onCreate(b)
                  val prefs = getSharedPreferences("p", MODE_PRIVATE)
                  val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(32, 64, 32, 32) }

                  val key = EditText(this).apply { hint = "Anthropic API key"; setText(prefs.getString("key", "")) }
                  val a11y = Button(this).apply {
                      text = "1. Accessibility ON karo"
                      setOnClickListener { startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)) }
                  }
                  val stop = Button(this).apply { text = "STOP"; setOnClickListener { AutoPlayService.running = false } }
                  statusView = TextView(this)

                  val pm = packageManager
                  val apps = pm.queryIntentActivities(
                      Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER), PackageManager.MATCH_ALL)
                      .filter { it.activityInfo.packageName != packageName }
                      .sortedBy { it.loadLabel(pm).toString().lowercase() }
                  val list = ListView(this).apply {
                      adapter = ArrayAdapter(context, android.R.layout.simple_list_item_1, apps.map { it.loadLabel(pm).toString() })
                      setOnItemClickListener { _, _, i, _ ->
                          val svc = AutoPlayService.instance
                          if (svc == null) { toast("Pehle Accessibility ON karo"); return@setOnItemClickListener }
                          if (key.text.isBlank()) { toast("API key daalo"); return@setOnItemClickListener }
                          prefs.edit().putString("key", key.text.toString()).apply()
                          AutoPlayService.apiKey = key.text.toString().trim()
                          startActivity(pm.getLaunchIntentForPackage(apps[i].activityInfo.packageName))
                          ui.postDelayed({ svc.startPlaying() }, 3000)
                      }
                  }
                  root.addView(TextView(this).apply { text = "Game chuno, baaki app khud khelega"; textSize = 18f })
                  root.addView(key); root.addView(a11y); root.addView(stop); root.addView(statusView)
                  root.addView(list, LinearLayout.LayoutParams(-1, 0, 1f))
                  setContentView(root)
                  tick()
              }

              private fun tick() {
                  statusView.text = "Status: " + AutoPlayService.status
                  ui.postDelayed({ tick() }, 1000)
              }
              private fun toast(s: String) = Toast.makeText(this, s, Toast.LENGTH_SHORT).show()
          }
          EOF
      - uses: actions/setup-java@v4
        with:
          distribution: temurin
          java-version: 17
      - uses: gradle/actions/setup-gradle@v4
        with:
          gradle-version: 8.7
      - run: gradle assembleDebug
      - uses: actions/upload-artifact@v4
        with:
          name: AutoPlayer-apk
          path: app/build/outputs/apk/debug/app-debug.apk
