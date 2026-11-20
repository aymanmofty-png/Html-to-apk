package com.sabourti.app

import android.annotation.SuppressLint
import android.app.AlertDialog
import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.content.pm.ActivityInfo
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.SystemClock
import android.provider.Settings
import android.view.InputDevice
import android.view.MotionEvent
import android.view.PointerIcon
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.webkit.JsPromptResult
import android.webkit.JsResult
import android.webkit.PermissionRequest
import android.webkit.RenderProcessGoneDetail
import android.webkit.ValueCallback
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.EditText
import androidx.activity.ComponentActivity
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.webkit.WebViewAssetLoader

/**
 * النشاط الوحيد: يحمل واجهة التطبيق (www) داخل WebView آمن، ويوفّر كل الطبقة الأصلية:
 * الأذونات، الشاشة الكاملة، إخفاء المؤشر، قطع الإشعارات أثناء الاستعمال، بقاء الغرفة في الخلفية.
 */
class MainActivity : ComponentActivity() {

    companion object {
        const val HOST = "appassets.androidplatform.net"
        const val BASE = "https://$HOST/www/index.html"
    }

    lateinit var web: WebView
    private var fileCb: ValueCallback<Array<Uri>>? = null
    private var pendingReq: PermissionRequest? = null
    private var oldFilter = -1
    private var askedDnd = false
    private var lastHide = 0L
    private var overlayAdded = false
    val diag by lazy { Diag(this) }
    private var lockDepth = 0
    private var savedOri = ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
    private var restoreOnResume = false

    /** يثبّت الشاشة عمودية مؤقتاً أثناء أي واجهة نظام (حوار/منتقي/إذن/إعدادات). */
    fun lockPortrait() {
        if (lockDepth == 0) savedOri = requestedOrientation
        lockDepth++
        requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
    }

    fun unlockPortrait() {
        if (lockDepth == 0) return
        lockDepth--
        if (lockDepth == 0) requestedOrientation = savedOri
    }

    fun setOri(v: Int) {
        if (lockDepth > 0) savedOri = v else requestedOrientation = v
    }

    fun startPortrait(i: Intent) {
        lockPortrait()
        restoreOnResume = true
        try {
            startActivity(i)
        } catch (e: Exception) {
            restoreOnResume = false
            unlockPortrait()
            throw e
        }
    }

    override fun onRequestPermissionsResult(requestCode: Int, perms: Array<String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, perms, grantResults)
        if (requestCode == 77) unlockPortrait()
    }

    private val chooser = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { r ->
        unlockPortrait()
        fileCb?.onReceiveValue(WebChromeClient.FileChooserParams.parseResult(r.resultCode, r.data))
        fileCb = null
    }

    private val permissions = registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        unlockPortrait()
        val req = pendingReq
        pendingReq = null
        if (req != null) {
            val ok = req.resources.filter { have(it) }
            if (ok.isEmpty()) req.deny() else req.grant(ok.toTypedArray())
        }
    }

    private fun permFor(res: String): String? = when (res) {
        PermissionRequest.RESOURCE_VIDEO_CAPTURE -> android.Manifest.permission.CAMERA
        PermissionRequest.RESOURCE_AUDIO_CAPTURE -> android.Manifest.permission.RECORD_AUDIO
        else -> null
    }

    private fun have(res: String): Boolean {
        val p = permFor(res) ?: return false
        return checkSelfPermission(p) == PackageManager.PERMISSION_GRANTED
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        try {
            if (Build.VERSION.SDK_INT >= 28) {
                val lp = window.attributes
                lp.layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
                window.attributes = lp
            }
        } catch (_: Exception) {
        }
        try {
            val rl = window.attributes
            rl.rotationAnimation = WindowManager.LayoutParams.ROTATION_ANIMATION_JUMPCUT
            window.attributes = rl
        } catch (_: Exception) {
        }

        web = WebView(this)
        setContentView(web)
        setup()

        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() = onBack()
        })

        if (Build.VERSION.SDK_INT >= 33 &&
            checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            requestPermissions(arrayOf(android.Manifest.permission.POST_NOTIFICATIONS), 77)
        }

        immersive()
        hidePointer()
        load(intent)
    }

    @SuppressLint("SetJavaScriptEnabled")
    private fun setup() {
        val loader = WebViewAssetLoader.Builder()
            .setDomain(HOST)
            .addPathHandler("/", WebViewAssetLoader.AssetsPathHandler(this))
            .build()

        web.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            databaseEnabled = true
            mediaPlaybackRequiresUserGesture = false
            javaScriptCanOpenWindowsAutomatically = true
            allowFileAccess = false
            allowContentAccess = false
            setSupportZoom(false)
            builtInZoomControls = false
            displayZoomControls = false
            textZoom = 100
            useWideViewPort = true
            loadWithOverviewMode = true
            cacheMode = WebSettings.LOAD_DEFAULT
            mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW
        }
        web.overScrollMode = View.OVER_SCROLL_NEVER
        web.addJavascriptInterface(NativeBridge(this), "NativeBridge")

        web.webViewClient = object : WebViewClient() {
            override fun shouldInterceptRequest(view: WebView, request: WebResourceRequest): WebResourceResponse? =
                loader.shouldInterceptRequest(request.url)

            override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
                if (!request.isForMainFrame || request.url.host == HOST) return false
                try {
                    startActivity(Intent(Intent.ACTION_VIEW, request.url))
                } catch (_: Exception) {
                }
                return true
            }

            override fun onRenderProcessGone(view: WebView, detail: RenderProcessGoneDetail): Boolean {
                runOnUiThread { recreate() }
                return true
            }
        }

        web.webChromeClient = object : WebChromeClient() {
            override fun onPermissionRequest(req: PermissionRequest) {
                runOnUiThread {
                    val h = req.origin.host ?: ""
                    if (h != HOST && h != "daily.co" && !h.endsWith(".daily.co")) {
                        req.deny()
                        return@runOnUiThread
                    }
                    val wanted = req.resources.filter { permFor(it) != null }
                    val need = wanted.mapNotNull { permFor(it) }
                        .filter { checkSelfPermission(it) != PackageManager.PERMISSION_GRANTED }
                    if (wanted.isEmpty()) {
                        req.deny()
                    } else if (need.isEmpty()) {
                        req.grant(wanted.toTypedArray())
                    } else {
                        pendingReq?.deny()
                        pendingReq = req
                        lockPortrait()
                        permissions.launch(need.toTypedArray())
                    }
                }
            }

            override fun onShowFileChooser(
                w: WebView, cb: ValueCallback<Array<Uri>>, p: FileChooserParams
            ): Boolean {
                fileCb?.onReceiveValue(null)
                fileCb = cb
                lockPortrait()
                return try {
                    chooser.launch(p.createIntent())
                    true
                } catch (e: Exception) {
                    unlockPortrait()
                    fileCb = null
                    cb.onReceiveValue(null)
                    false
                }
            }

            override fun onJsAlert(v: WebView, url: String, msg: String, r: JsResult): Boolean {
                AlertDialog.Builder(this@MainActivity).setMessage(msg)
                    .setPositiveButton("حسناً") { _, _ -> r.confirm() }
                    .setOnCancelListener { r.cancel() }.also { it.setOnDismissListener { unlockPortrait() } }.also { lockPortrait() }.show()
                return true
            }

            override fun onJsConfirm(v: WebView, url: String, msg: String, r: JsResult): Boolean {
                AlertDialog.Builder(this@MainActivity).setMessage(msg)
                    .setPositiveButton("موافق") { _, _ -> r.confirm() }
                    .setNegativeButton("إلغاء") { _, _ -> r.cancel() }
                    .setOnCancelListener { r.cancel() }.also { it.setOnDismissListener { unlockPortrait() } }.also { lockPortrait() }.show()
                return true
            }

            override fun onJsPrompt(
                v: WebView, url: String, msg: String, def: String?, r: JsPromptResult
            ): Boolean {
                val et = EditText(this@MainActivity)
                et.setText(def ?: "")
                AlertDialog.Builder(this@MainActivity).setMessage(msg).setView(et)
                    .setPositiveButton("موافق") { _, _ -> r.confirm(et.text.toString()) }
                    .setNegativeButton("إلغاء") { _, _ -> r.cancel() }
                    .setOnCancelListener { r.cancel() }.also { it.setOnDismissListener { unlockPortrait() } }.also { lockPortrait() }.show()
                return true
            }
        }
    }

    private fun load(i: Intent?) {
        val j = i?.data?.getQueryParameter("join")?.filter { it.isDigit() }
        web.loadUrl(if (!j.isNullOrEmpty()) "$BASE?join=$j" else BASE)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        val j = intent.data?.getQueryParameter("join")?.filter { it.isDigit() }
        if (!j.isNullOrEmpty()) {
            web.evaluateJavascript("window.__room&&window.__room.joinCode('$j')", null)
        }
    }

    private fun onBack() {
        web.evaluateJavascript("(function(){try{return window.__back?(window.__back()?1:0):0}catch(e){return 0}})()") { r ->
            if (r != "1") {
                if (web.canGoBack()) web.goBack() else moveTaskToBack(true)
            }
        }
    }

    override fun onResume() {
        super.onResume()
        if (restoreOnResume) {
            restoreOnResume = false
            unlockPortrait()
        }
        web.onResume()
        web.resumeTimers()
        immersive()
        hidePointer()
        silence()
    }

    override fun onPause() {
        restoreSound()
        super.onPause()
        if (RoomService.running) keepWeb()
    }

    override fun onStop() {
        super.onStop()
        if (RoomService.running) keepWeb() else {
            web.onPause()
            web.pauseTimers()
        }
    }

    override fun onDestroy() {
        restoreSound()
        super.onDestroy()
    }

    /** يبقي صفحة الويب حيّة في الخلفية ما دامت الغرفة مفتوحة (تعمل مع الخدمة الأمامية). */
    private fun keepWeb() {
        try {
            web.onResume()
            web.resumeTimers()
        } catch (_: Exception) {
        }
    }

    private fun silence() {
        try {
            val nm = getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager ?: return
            if (nm.isNotificationPolicyAccessGranted) {
                if (oldFilter < 0) oldFilter = nm.currentInterruptionFilter
                nm.setInterruptionFilter(NotificationManager.INTERRUPTION_FILTER_ALARMS)
            } else if (!askedDnd) {
                askedDnd = true
                startActivity(Intent(Settings.ACTION_NOTIFICATION_POLICY_ACCESS_SETTINGS))
            }
        } catch (_: Exception) {
        }
    }

    private fun restoreSound() {
        try {
            val nm = getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager
            if (nm != null && oldFilter >= 0 && nm.isNotificationPolicyAccessGranted) {
                nm.setInterruptionFilter(oldFilter)
            }
            oldFilter = -1
        } catch (_: Exception) {
        }
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) {
            immersive()
            hidePointer()
        }
    }

    /** أثناء القراءة الخام: أحداث القلم/الفأرة من أندرويد تُسقط (اللوح يُقرأ مباشرة). لمس الإصبع لا يتأثر. */
    private fun isPenOrMouse(ev: MotionEvent): Boolean {
        val t = ev.getToolType(0)
        if (t == MotionEvent.TOOL_TYPE_STYLUS || t == MotionEvent.TOOL_TYPE_ERASER || t == MotionEvent.TOOL_TYPE_MOUSE) return true
        val s = ev.source
        return (s and InputDevice.SOURCE_MOUSE) == InputDevice.SOURCE_MOUSE || (s and InputDevice.SOURCE_STYLUS) == InputDevice.SOURCE_STYLUS
    }

    override fun dispatchGenericMotionEvent(ev: MotionEvent): Boolean {
        if (diag.rawOn && !diag.evOn && isPenOrMouse(ev)) {
            diag.blocked++
            return true
        }
        val r = super.dispatchGenericMotionEvent(ev)
        diag.onMotion(ev, "gen")
        val n = SystemClock.uptimeMillis()
        if (n - lastHide > 60) {
            lastHide = n
            hidePointer()
        }
        return r
    }

    private var injDown = 0L

    /** يحقن لمسة إصبع حقيقية في الواجهة نيابةً عن قلم اللوح (لتعمل مختارات الملفات وغيرها التي تشترط لمسة حقيقية). */
    fun injectTouch(t: String, x: Float, y: Float) {
        runOnUiThread {
            try {
                val now = SystemClock.uptimeMillis()
                val action = when (t) {
                    "d" -> { injDown = now; MotionEvent.ACTION_DOWN }
                    "m" -> MotionEvent.ACTION_MOVE
                    else -> MotionEvent.ACTION_UP
                }
                val pp = MotionEvent.PointerProperties().apply { id = 0; toolType = MotionEvent.TOOL_TYPE_FINGER }
                val pc = MotionEvent.PointerCoords().apply { this.x = x; this.y = y; pressure = 1f; size = 1f }
                val ev = MotionEvent.obtain(injDown, now, action, 1, arrayOf(pp), arrayOf(pc), 0, 0, 1f, 1f, 0, 0, InputDevice.SOURCE_TOUCHSCREEN, 0)
                web.dispatchTouchEvent(ev)
                ev.recycle()
            } catch (_: Exception) {}
        }
    }

    override fun dispatchTouchEvent(ev: MotionEvent): Boolean {
        if (diag.rawOn && !diag.evOn && isPenOrMouse(ev)) {
            diag.blocked++
            return true
        }
        val r = super.dispatchTouchEvent(ev)
        diag.onMotion(ev, "touch")
        val n = SystemClock.uptimeMillis()
        if (n - lastHide > 150) {
            lastHide = n
            hidePointer()
        }
        return r
    }

    private fun immersive() {
        try {
            val c = WindowCompat.getInsetsController(window, window.decorView)
            c.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            c.hide(WindowInsetsCompat.Type.systemBars())
        } catch (_: Exception) {
        }
    }

    private fun addOverlay() {
        try {
            if (overlayAdded || Build.VERSION.SDK_INT < 24) return
            val ov = View(this)
            ov.isClickable = false
            ov.isFocusable = false
            ov.pointerIcon = PointerIcon.getSystemIcon(this, PointerIcon.TYPE_NULL)
            addContentView(ov, ViewGroup.LayoutParams(-1, -1))
            overlayAdded = true
        } catch (_: Exception) {
        }
    }

    /** يخفي مؤشر الماوس/القلم (Wacom) حتى لا يظهر فوق السبورة. */
    private fun hidePointer() {
        addOverlay()
        try {
            if (Build.VERSION.SDK_INT >= 24) {
                val nul = PointerIcon.getSystemIcon(this, PointerIcon.TYPE_NULL)
                web.pointerIcon = nul
                window.decorView.pointerIcon = nul
            }
        } catch (_: Exception) {
        }
    }
}
