package com.sabourti.app

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.hardware.usb.UsbConstants
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbDeviceConnection
import android.hardware.usb.UsbManager
import android.os.Build
import android.os.SystemClock
import android.view.InputDevice
import android.view.MotionEvent
import androidx.core.content.ContextCompat
import org.json.JSONArray
import org.json.JSONObject

/** تشخيص لوح الرسم: ما يبلّغه أندرويد، وقراءة HID الخام عبر USB، والتقاط المؤشر. */
class Diag(private val a: MainActivity) {
    private val action = "com.sabourti.board.USB_PERM"

    @Volatile
    var evOn = false

    /** القراءة الخام شغّالة: تُسقط الواجهة أحداث القلم/الفأرة القادمة من أندرويد. */
    @Volatile
    var rawOn = false

    @Volatile
    var blocked = 0

    @Volatile
    var reads = 0

    @Volatile
    var fails = 0

    @Volatile
    var lastStopAt = 0L

    @Volatile
    var claimInfo = ""
    private val evs = ArrayList<String>()
    private val usbRep = ArrayList<String>()
    private val threads = ArrayList<Thread>()

    @Volatile
    private var running = false
    private var conn: UsbDeviceConnection? = null
    private var curDev: UsbDevice? = null

    @Volatile
    var permState = ""
    private var recv: BroadcastReceiver? = null

    private fun hex(b: ByteArray, n: Int): String {
        val sb = StringBuilder(n * 2)
        for (i in 0 until n) sb.append(String.format("%02x", b[i].toInt() and 0xff))
        return sb.toString()
    }

    /** يُستدعى من النشاط لكل حدث لمس/حركة عامة أثناء التشخيص. */
    fun onMotion(ev: MotionEvent, tag: String) {
        if (!evOn) return
        synchronized(evs) {
            if (evs.size > 6000) return
            val n = ev.historySize
            for (h in 0..n) {
                val x: Float
                val y: Float
                val p: Float
                val t: Long
                if (h < n) {
                    x = ev.getHistoricalX(h); y = ev.getHistoricalY(h); p = ev.getHistoricalPressure(h); t = ev.getHistoricalEventTime(h)
                } else {
                    x = ev.x; y = ev.y; p = ev.pressure; t = ev.eventTime
                }
                evs.add(
                    "$tag,${ev.actionMasked},${ev.source},${ev.deviceId},${ev.getToolType(0)},$x,$y,$p," +
                        "${ev.getAxisValue(MotionEvent.AXIS_DISTANCE)},${ev.getAxisValue(MotionEvent.AXIS_TILT)},${ev.getAxisValue(MotionEvent.AXIS_ORIENTATION)},${ev.buttonState},$t"
                )
            }
        }
    }

    fun evPoll(): String {
        synchronized(evs) {
            val j = JSONArray(evs)
            evs.clear()
            return j.toString()
        }
    }

    fun devices(): String {
        val arr = JSONArray()
        for (id in InputDevice.getDeviceIds()) {
            val d = InputDevice.getDevice(id) ?: continue
            val o = JSONObject()
            o.put("id", id).put("name", d.name).put("vendor", d.vendorId).put("product", d.productId)
                .put("sources", d.sources).put("descriptor", d.descriptor)
            val rs = JSONArray()
            for (r in d.motionRanges) {
                rs.put(
                    JSONObject().put("axis", MotionEvent.axisToString(r.axis)).put("src", r.source)
                        .put("min", r.min.toDouble()).put("max", r.max.toDouble()).put("res", r.resolution.toDouble())
                        .put("flat", r.flat.toDouble()).put("fuzz", r.fuzz.toDouble())
                )
            }
            o.put("ranges", rs)
            arr.put(o)
        }
        return arr.toString()
    }

    private fun um() = a.getSystemService(Context.USB_SERVICE) as UsbManager

    fun usbList(): String {
        val m = um()
        val arr = JSONArray()
        for (d in m.deviceList.values) {
            val o = JSONObject()
            o.put("name", d.deviceName).put("vid", d.vendorId).put("pid", d.productId).put("cls", d.deviceClass)
                .put("perm", m.hasPermission(d))
            try { o.put("product", d.productName ?: "").put("manu", d.manufacturerName ?: "") } catch (_: Exception) {}
            val ifs = JSONArray()
            for (i in 0 until d.interfaceCount) {
                val f = d.getInterface(i)
                val eps = JSONArray()
                for (e in 0 until f.endpointCount) {
                    val ep = f.getEndpoint(e)
                    eps.put(JSONObject().put("addr", ep.address).put("type", ep.type).put("dir", ep.direction).put("max", ep.maxPacketSize).put("int", ep.interval))
                }
                ifs.put(JSONObject().put("id", f.id).put("cls", f.interfaceClass).put("sub", f.interfaceSubclass).put("proto", f.interfaceProtocol).put("eps", eps))
            }
            o.put("ifs", ifs)
            arr.put(o)
        }
        return arr.toString()
    }

    private fun find(vid: Int, pid: Int): UsbDevice? =
        um().deviceList.values.firstOrNull { it.vendorId == vid && it.productId == pid }

    fun usbAsk(vid: Int, pid: Int): String {
        val d = find(vid, pid) ?: return "no-device"
        val m = um()
        if (m.hasPermission(d)) { permState = "granted"; return "granted" }
        if (recv == null) {
            recv = object : BroadcastReceiver() {
                override fun onReceive(c: Context, i: Intent) {
                    permState = if (i.getBooleanExtra(UsbManager.EXTRA_PERMISSION_GRANTED, false)) "granted" else "denied"
                }
            }
            ContextCompat.registerReceiver(a, recv!!, IntentFilter(action), ContextCompat.RECEIVER_NOT_EXPORTED)
        }
        permState = "wait"
        val fl = if (Build.VERSION.SDK_INT >= 31) PendingIntent.FLAG_MUTABLE else 0
        val pi = PendingIntent.getBroadcast(a, 0, Intent(action).setPackage(a.packageName), fl)
        m.requestPermission(d, pi)
        return "wait"
    }

    // ===== فتح الجهاز وقراءته (مشترك بين المحرك القديم والجديد) =====
    private val ffs = ArrayList<java.util.concurrent.atomic.AtomicInteger>()

    @Volatile
    var lastDescs = JSONArray()
    private val engLock = Any()

    /** يفتح الجهاز ويبدأ خيوط القراءة. يعيد نص الخطأ أو null عند النجاح. يُستدعى داخل engLock. */
    private fun openConn(d: UsbDevice): String? {
        reads = 0
        fails = 0
        claimInfo = ""
        ffs.clear()
        val m = um()
        if (!m.hasPermission(d)) return "لا يوجد إذن USB"
        val c = m.openDevice(d) ?: return "تعذر فتح الجهاز"
        conn = c
        curDev = d
        running = true
        val descs = JSONArray()
        for (i in 0 until d.interfaceCount) {
            val f = d.getInterface(i)
            if (f.interfaceClass != UsbConstants.USB_CLASS_HID) continue
            val okc = c.claimInterface(f, true)
            claimInfo += "if${f.id}:claim=$okc "
            if (!okc) continue
            val buf = ByteArray(4096)
            val n = c.controlTransfer(0x81, 0x06, 0x2200, f.id, buf, buf.size, 1000)
            if (n > 0) descs.put(JSONObject().put("iface", f.id).put("hex", hex(buf, n)))
            for (e in 0 until f.endpointCount) {
                val ep = f.getEndpoint(e)
                if (ep.direction != UsbConstants.USB_DIR_IN || ep.type != UsbConstants.USB_ENDPOINT_XFER_INT) continue
                val tag = "${f.id}:${ep.address}"
                val ff = java.util.concurrent.atomic.AtomicInteger(0)
                ffs.add(ff)
                val t = Thread {
                    val b = ByteArray(maxOf(ep.maxPacketSize, 64))
                    while (running) {
                        val t0 = SystemClock.uptimeMillis()
                        val k = try { c.bulkTransfer(ep, b, b.size, 200) } catch (_: Exception) { -1 }
                        val dt = SystemClock.uptimeMillis() - t0
                        if (k < 0) {
                            fails++
                            // انتهاء المهلة العادي يستغرق ~200ms؛ الفشل الفوري يعني اتصالاً ميتاً
                            if (dt < 60) ff.incrementAndGet() else ff.set(0)
                            try { Thread.sleep(5) } catch (_: Exception) {}
                        } else {
                            ff.set(0)
                            if (k > 0) {
                                reads++
                                synchronized(usbRep) { if (usbRep.size < 8000) usbRep.add("$tag|" + hex(b, k)) }
                            }
                        }
                    }
                }
                t.isDaemon = true
                t.start()
                threads.add(t)
            }
        }
        lastDescs = descs
        rawOn = threads.size > 0
        blocked = 0
        return null
    }

    /** المحرك القديم: يُستدعى من الواجهة مباشرةً. */
    fun usbStart(vid: Int, pid: Int): String {
        synchronized(engLock) { return usbStartLocked(vid, pid) }
    }

    private fun usbStartLocked(vid: Int, pid: Int): String {
        wantVid = 0
        wantPid = 0
        if (conn != null) usbStopLocked()
        // بعد الإيقاف يعيد أندرويد ربط تعريفه بالجهاز؛ نمهله لحظة قبل أخذه ثانيةً
        val since = SystemClock.uptimeMillis() - lastStopAt
        if (since < 1500) return JSONObject().put("error", "wait").put("ms", 1500 - since).toString()
        val d = find(vid, pid) ?: return JSONObject().put("error", "الجهاز غير موجود").toString()
        val err = openConn(d)
        if (err != null) return JSONObject().put("error", err).toString()
        return JSONObject().put("descs", lastDescs).put("threads", threads.size).toString()
    }

    fun usbPoll(): String {
        synchronized(usbRep) {
            val j = JSONArray(usbRep)
            usbRep.clear()
            return j.toString()
        }
    }

    fun usbStats(): String = JSONObject().put("reads", reads).put("fails", fails).put("threads", threads.size).put("claim", claimInfo).put("rawOn", rawOn).toString()

    fun usbStop() {
        synchronized(engLock) { usbStopLocked() }
    }

    private fun usbStopLocked() {
        if (conn != null) lastStopAt = SystemClock.uptimeMillis()
        running = false
        rawOn = false
        threads.forEach { try { it.join(300) } catch (_: Exception) {} }
        threads.clear()
        try {
            val d = curDev
            val c = conn
            if (c != null && d != null) for (i in 0 until d.interfaceCount) try { c.releaseInterface(d.getInterface(i)) } catch (_: Exception) {}
            c?.close()
        } catch (_: Exception) {}
        conn = null
        curDev = null
    }

    // ===== المحرك الجديد: آلة حالات تعمل وحدها =====
    // idle (لا مراقبة) · none (الجهاز غائب) · wait (مهلة بعد الإغلاق) · noperm (بانتظار الإذن)
    // reading (يقرأ) · dead (أُغلق وسيُعاد) · gaveup (توقفت المحاولة)
    @Volatile var wantVid = 0
    @Volatile var wantPid = 0
    @Volatile var engState = "idle"
    @Volatile var sess = 0
    @Volatile var engWhy = ""
    @Volatile var engDenied = false
    private var engThread: Thread? = null
    private val engLogs = ArrayList<String>()
    private var curName = ""
    private var failN = 0
    private var askedName = ""
    private var askedAt = 0L
    private var deniedName = ""
    private var gaveName = ""
    private var openAt = 0L
    private var lastBeat = 0L
    private var beatFail = 0
    private var hbOk = true

    private fun elog(s: String) {
        engWhy = s
        synchronized(engLogs) { if (engLogs.size < 200) engLogs.add(s) }
    }

    private fun closeConn(why: String) {
        if (why.isNotEmpty()) elog("إغلاق الاتصال: $why")
        running = false
        rawOn = false
        threads.forEach { try { it.join(300) } catch (_: Exception) {} }
        threads.clear()
        try { conn?.close() } catch (_: Exception) {}
        conn = null
        curDev = null
        lastStopAt = SystemClock.uptimeMillis()
    }

    private fun giveUp(name: String) {
        engState = "gaveup"
        gaveName = name
        elog("توقفت المحاولة بعد فشل متكرر — أعد وصل الكابل")
    }

    private fun engTick() {
        synchronized(engLock) { engTickLocked() }
    }

    private fun engTickLocked() {
        val vid = wantVid
        val pid = wantPid
        if (vid == 0) {
            if (conn != null) closeConn("إيقاف المراقبة")
            engState = "idle"
            return
        }
        val now = SystemClock.uptimeMillis()
        val d = find(vid, pid)
        if (d == null) {
            if (conn != null) closeConn("فُصل اللوح")
            if (engState != "none") elog("اللوح غير موجود")
            engState = "none"
            failN = 0
            gaveName = ""
            return
        }
        if (conn != null) {
            var why = ""
            if (d.deviceName != curName) why = "تغيّر اسم الجهاز ($curName ← ${d.deviceName})"
            else if (ffs.isNotEmpty() && ffs.all { it.get() >= 20 }) why = "فشل القراءة الفوري المتكرر"
            else if (hbOk && now - lastBeat >= 3000) {
                lastBeat = now
                val r = try { conn!!.controlTransfer(0x80, 0x00, 0, 0, ByteArray(2), 2, 300) } catch (_: Exception) { -1 }
                if (r < 0) { beatFail++; if (beatFail >= 2) why = "لا ردّ من اللوح على فحص النبض" } else beatFail = 0
            }
            if (why.isEmpty()) {
                engState = "reading"
                if (now - openAt > 5000) failN = 0
                return
            }
            if (now - openAt < 5000) failN++ else failN = 0
            closeConn(why)
            if (failN >= 3) giveUp(d.deviceName) else engState = "dead"
            return
        }
        if (engState == "gaveup" && d.deviceName == gaveName) return
        if (now - lastStopAt < 1500) { engState = "wait"; return }
        val m = um()
        if (!m.hasPermission(d)) {
            engState = "noperm"
            if (permState == "denied" && askedName == d.deviceName && deniedName != d.deviceName) {
                deniedName = d.deviceName
                elog("رُفض إذن USB — اضغط «اطلب إذن USB» في الإعدادات")
            }
            engDenied = deniedName == d.deviceName
            if (engDenied) return
            if (!a.hasWindowFocus()) return
            if (askedName == d.deviceName && now - askedAt < 25000) return
            askedName = d.deviceName
            askedAt = now
            elog("طلب إذن USB من النظام")
            usbAsk(vid, pid)
            return
        }
        engDenied = false
        val err = openConn(d)
        if (err != null) {
            failN++
            elog("تعذر الفتح: $err")
            closeConn("")
            if (failN >= 3) giveUp(d.deviceName) else engState = "dead"
            return
        }
        // فحص النبض الأساسي: إن لم يستجب الجهاز له أصلاً نعطّله في هذه الجلسة
        beatFail = 0
        lastBeat = now
        hbOk = try { conn!!.controlTransfer(0x80, 0x00, 0, 0, ByteArray(2), 2, 300) >= 0 } catch (_: Exception) { false }
        sess++
        curName = d.deviceName
        openAt = now
        engState = "reading"
        elog("بدأت القراءة (جلسة $sess، ${threads.size} قناة، $claimInfo، نبض=${if (hbOk) "نعم" else "لا"})")
    }

    private fun engEnsure() {
        if (engThread != null) return
        val t = Thread {
            while (true) {
                try { engTick() } catch (e: Exception) { elog("خطأ داخلي: ${e.message}") }
                try { Thread.sleep(400) } catch (_: Exception) {}
            }
        }
        t.isDaemon = true
        t.start()
        engThread = t
    }

    fun usbWatch(vid: Int, pid: Int): String {
        synchronized(engLock) {
            if (vid == 0) {
                wantVid = 0
                wantPid = 0
                if (conn != null) closeConn("إيقاف المراقبة")
                engState = "idle"
                return engState
            }
            if (vid == wantVid && pid == wantPid) return engState
            wantVid = vid
            wantPid = pid
            failN = 0
            deniedName = ""
            gaveName = ""
            askedName = ""
            engState = "none"
            elog("مراقبة الجهاز $vid:$pid")
        }
        engEnsure()
        return engState
    }

    /** إعادة فتح فورية: يصفّر العدّادات ويغلق الاتصال الحالي ليُفتح من جديد بعد المهلة. */
    fun usbKick(): String {
        synchronized(engLock) {
            failN = 0
            deniedName = ""
            gaveName = ""
            askedName = ""
            engDenied = false
            if (conn != null) closeConn("إعادة فتح مطلوبة")
        }
        return "ok"
    }

    fun usbStatus(): String {
        val d = if (wantVid != 0) find(wantVid, wantPid) else null
        return JSONObject().put("state", engState).put("sess", sess).put("name", curName).put("why", engWhy)
            .put("reads", reads).put("fails", fails).put("threads", threads.size).put("claim", claimInfo)
            .put("perm", d?.let { um().hasPermission(it) } ?: false).put("denied", engDenied).put("failN", failN).toString()
    }

    fun engDescs(): String = lastDescs.toString()

    fun engLog(): String {
        synchronized(engLogs) {
            val j = JSONArray(engLogs)
            engLogs.clear()
            return j.toString()
        }
    }

    fun capStart(): Boolean {
        if (Build.VERSION.SDK_INT < 26) return false
        a.runOnUiThread {
            try {
                a.web.requestFocus()
                a.web.setOnCapturedPointerListener { _, ev -> onMotion(ev, "cap"); true }
                a.web.requestPointerCapture()
            } catch (_: Exception) {}
        }
        return true
    }

    fun capStop() {
        if (Build.VERSION.SDK_INT < 26) return
        a.runOnUiThread {
            try { a.web.releasePointerCapture(); a.web.setOnCapturedPointerListener(null) } catch (_: Exception) {}
        }
    }
}
