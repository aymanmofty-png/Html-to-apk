package com.sabourti.app

import android.content.ClipData
import android.content.ContentValues
import android.content.Intent
import android.content.pm.ActivityInfo
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.os.PowerManager
import android.provider.MediaStore
import android.provider.Settings
import android.util.Base64
import android.webkit.JavascriptInterface
import android.webkit.MimeTypeMap
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import org.json.JSONObject
import java.io.File

/** الجسر بين صفحة الويب والنظام. كل دالة ترجع JSON نصياً، والـ shim يحوّله إلى Promise. */
class NativeBridge(private val a: MainActivity) {

    private fun ok(vararg kv: Pair<String, Any?>): String {
        val o = JSONObject()
        kv.forEach { o.put(it.first, it.second) }
        return o.toString()
    }

    private fun err(e: Throwable): String =
        JSONObject().put("error", e.message ?: e.toString()).toString()

    private fun bytesOf(data: String): ByteArray =
        Base64.decode(data.substringAfter("base64,", data), Base64.DEFAULT)

    private fun safe(name: String): String =
        name.replace('/', '_').replace('\\', '_').ifEmpty { "file" }

    /** حفظ في Download/سبورتي (MediaStore) — يظهر في تطبيق الملفات والمعرض. */
    @JavascriptInterface
    fun saveFile(name: String, mime: String, data: String): String = try {
        val bytes = bytesOf(data)
        val n = safe(name)
        val where = "Download/سبورتي"
        try {
            if (Build.VERSION.SDK_INT >= 29) {
                val cr = a.contentResolver
                val cv = ContentValues()
                cv.put(MediaStore.MediaColumns.DISPLAY_NAME, n)
                cv.put(MediaStore.MediaColumns.MIME_TYPE, mime)
                cv.put(MediaStore.MediaColumns.RELATIVE_PATH, where)
                val u = cr.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, cv)
                    ?: throw IllegalStateException("no uri")
                cr.openOutputStream(u)!!.use { it.write(bytes) }
            } else {
                val dir = File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS), "سبورتي")
                dir.mkdirs()
                File(dir, n).writeBytes(bytes)
            }
            ok("path" to "$where/$n")
        } catch (e: Exception) {
            val dir = a.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS) ?: a.filesDir
            dir.mkdirs()
            File(dir, n).writeBytes(bytes)
            ok("path" to "${dir.absolutePath}/$n")
        }
    } catch (e: Exception) {
        err(e)
    }

    /** كتابة ملف مؤقت للمشاركة (CACHE)، أو حفظ دائم لأي مجلد آخر. */
    @JavascriptInterface
    fun writeFile(path: String, data: String, directory: String): String = try {
        if (directory.uppercase() != "CACHE") {
            val n = safe(path.substringAfterLast('/'))
            saveFile(n, MimeTypeMap.getSingleton().getMimeTypeFromExtension(n.substringAfterLast('.', "")) ?: "application/octet-stream", data)
        } else {
            val f = File(a.cacheDir, "shared/" + safe(path))
            f.parentFile?.mkdirs()
            f.writeBytes(bytesOf(data))
            val uri = FileProvider.getUriForFile(a, a.packageName + ".fp", f)
            ok("uri" to uri.toString())
        }
    } catch (e: Exception) {
        err(e)
    }

    @JavascriptInterface
    fun share(title: String, text: String, uri: String): String {
        a.runOnUiThread {
            try {
                val i = Intent(Intent.ACTION_SEND)
                if (uri.isNotEmpty()) {
                    val u = Uri.parse(uri)
                    val ext = uri.substringAfterLast('.', "").lowercase()
                    i.type = MimeTypeMap.getSingleton().getMimeTypeFromExtension(ext) ?: "*/*"
                    i.putExtra(Intent.EXTRA_STREAM, u)
                    i.clipData = ClipData.newRawUri("", u)
                    i.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                    if (text.isNotEmpty()) i.putExtra(Intent.EXTRA_TEXT, text)
                } else {
                    i.type = "text/plain"
                    i.putExtra(Intent.EXTRA_TEXT, text)
                }
                if (title.isNotEmpty()) i.putExtra(Intent.EXTRA_SUBJECT, title)
                val c = Intent.createChooser(i, title.ifEmpty { null })
                c.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                a.startPortrait(c)
            } catch (_: Exception) {
            }
        }
        return ok()
    }

    @JavascriptInterface
    fun orient(o: String): String {
        val v = when (o) {
            "portrait", "portrait-primary" -> ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
            "portrait-secondary" -> ActivityInfo.SCREEN_ORIENTATION_REVERSE_PORTRAIT
            "landscape" -> ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
            "landscape-primary" -> ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE
            "landscape-secondary" -> ActivityInfo.SCREEN_ORIENTATION_REVERSE_LANDSCAPE
            else -> ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
        }
        a.runOnUiThread { try { a.setOri(v) } catch (_: Exception) {} }
        return ok()
    }

    /** يبدأ الخدمة الأمامية التي تُبقي الغرفة حيّة في الخلفية. */
    @JavascriptInterface
    fun keepStart(text: String): String = try {
        a.runOnUiThread {
            try {
                if (Build.VERSION.SDK_INT >= 33 &&
                    a.checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
                ) {
                    a.lockPortrait()
                    a.requestPermissions(arrayOf(android.Manifest.permission.POST_NOTIFICATIONS), 77)
                }
            } catch (_: Exception) {
            }
        }
        val i = Intent(a, RoomService::class.java)
        i.putExtra("text", text.ifEmpty { "الغرفة مفتوحة" })
        ContextCompat.startForegroundService(a, i)
        ok("notif" to NotificationManagerCompat.from(a).areNotificationsEnabled(), "sdk" to Build.VERSION.SDK_INT)
    } catch (e: Exception) {
        err(e)
    }

    @JavascriptInterface
    fun keepStop(): String {
        try {
            a.startService(Intent(a, RoomService::class.java).setAction("STOP"))
        } catch (_: Exception) {
        }
        return ok()
    }

    @JavascriptInterface
    fun battery(ask: Boolean): String = try {
        val pm = a.getSystemService(android.content.Context.POWER_SERVICE) as PowerManager
        val ign = pm.isIgnoringBatteryOptimizations(a.packageName)
        if (!ign && ask) {
            a.runOnUiThread {
                try {
                    a.startPortrait(
                        Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:" + a.packageName))
                    )
                } catch (_: Exception) {
                }
            }
        }
        ok(
            "ignoring" to ign,
            "notif" to NotificationManagerCompat.from(a).areNotificationsEnabled(),
            "running" to RoomService.running
        )
    } catch (e: Exception) {
        err(e)
    }

    @JavascriptInterface
    fun notifSettings(): String {
        a.runOnUiThread {
            try {
                val i = Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS)
                i.putExtra(Settings.EXTRA_APP_PACKAGE, a.packageName)
                a.startPortrait(i)
            } catch (_: Exception) {
            }
        }
        return ok()
    }

    // ===== تشخيص اللوح =====
    @JavascriptInterface fun diagDevices(): String = try { a.diag.devices() } catch (e: Exception) { err(e) }
    @JavascriptInterface fun diagEvents(on: Boolean): String { a.diag.evOn = on; return ok() }
    @JavascriptInterface fun diagEvPoll(): String = try { a.diag.evPoll() } catch (e: Exception) { "[]" }
    @JavascriptInterface fun usbList(): String = try { a.diag.usbList() } catch (e: Exception) { "[]" }
    @JavascriptInterface fun usbAsk(vid: Int, pid: Int): String = try { a.diag.usbAsk(vid, pid) } catch (e: Exception) { "error:" + (e.message ?: "") }
    @JavascriptInterface fun usbState(): String = a.diag.permState
    @JavascriptInterface fun usbStart(vid: Int, pid: Int): String = try { a.diag.usbStart(vid, pid) } catch (e: Exception) { err(e) }
    @JavascriptInterface fun rawBlocked(): String = try { a.diag.blocked.toString() } catch (e: Exception) { "" }
    @JavascriptInterface fun usbPoll(): String = try { a.diag.usbPoll() } catch (e: Exception) { "[]" }
    @JavascriptInterface fun usbStop(): String { try { a.diag.usbStop() } catch (_: Exception) {}; return ok() }
    @JavascriptInterface fun capStart(): Boolean = try { a.diag.capStart() } catch (e: Exception) { false }
    @JavascriptInterface fun capStop(): String { try { a.diag.capStop() } catch (_: Exception) {}; return ok() }

    /** إعدادات شاومي: 'app' تفاصيل التطبيق، 'auto' التشغيل التلقائي، 'perm' النوافذ المنبثقة/الأذونات. */
    @JavascriptInterface
    fun miSettings(kind: String): String {
        a.runOnUiThread {
            try {
                val app = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:" + a.packageName))
                val i = when (kind) {
                    "auto" -> Intent().setClassName("com.miui.securitycenter", "com.miui.permcenter.autostart.AutoStartManagementActivity")
                    "perm" -> Intent("miui.intent.action.APP_PERM_EDITOR").setClassName("com.miui.securitycenter", "com.miui.permcenter.permissions.PermissionsEditorActivity").putExtra("extra_pkgname", a.packageName)
                    else -> app
                }
                try { a.startPortrait(i) } catch (_: Exception) { a.startPortrait(app) }
            } catch (_: Exception) {
            }
        }
        return ok()
    }
}
