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

    fun usbStart(vid: Int, pid: Int): String {
        usbStop()
        val d = find(vid, pid) ?: return JSONObject().put("error", "الجهاز غير موجود").toString()
        val m = um()
        if (!m.hasPermission(d)) return JSONObject().put("error", "لا يوجد إذن USB").toString()
        val c = m.openDevice(d) ?: return JSONObject().put("error", "تعذر فتح الجهاز").toString()
        conn = c
        curDev = d
        running = true
        val descs = JSONArray()
        for (i in 0 until d.interfaceCount) {
            val f = d.getInterface(i)
            if (f.interfaceClass != UsbConstants.USB_CLASS_HID) continue
            if (!c.claimInterface(f, true)) continue
            val buf = ByteArray(4096)
            val n = c.controlTransfer(0x81, 0x06, 0x2200, f.id, buf, buf.size, 1000)
            if (n > 0) descs.put(JSONObject().put("iface", f.id).put("hex", hex(buf, n)))
            for (e in 0 until f.endpointCount) {
                val ep = f.getEndpoint(e)
                if (ep.direction != UsbConstants.USB_DIR_IN || ep.type != UsbConstants.USB_ENDPOINT_XFER_INT) continue
                val tag = "${f.id}:${ep.address}"
                val t = Thread {
                    val b = ByteArray(maxOf(ep.maxPacketSize, 64))
                    while (running) {
                        val k = try { c.bulkTransfer(ep, b, b.size, 200) } catch (_: Exception) { -1 }
                        if (k < 0) try { Thread.sleep(5) } catch (_: Exception) {}
                        if (k > 0) synchronized(usbRep) { if (usbRep.size < 8000) usbRep.add("$tag|" + hex(b, k)) }
                    }
                }
                t.isDaemon = true
                t.start()
                threads.add(t)
            }
        }
        rawOn = threads.size > 0
        blocked = 0
        return JSONObject().put("descs", descs).put("threads", threads.size).toString()
    }

    fun usbPoll(): String {
        synchronized(usbRep) {
            val j = JSONArray(usbRep)
            usbRep.clear()
            return j.toString()
        }
    }

    fun usbStop() {
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
