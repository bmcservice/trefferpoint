package de.bmcservice.xdvharness;

import android.app.Activity;
import android.os.Bundle;
import android.util.Log;

import com.tutk.IOTC.AVAPIs;
import com.tutk.IOTC.IOTCAPIs;

import java.io.File;
import java.io.FileOutputStream;
import java.io.PrintWriter;

/**
 * Eigenstaendiger TUTK/IOTC-LAN-Harness fuer die XDV-Pro / "4K WIFI"-Cam.
 *
 * Sequenz (kanonisch, aus TutkKalay Sample_AVAPIs_Client):
 *   IOTC_Initialize2(0) -> avInitialize(3) -> IOTC_Connect_ByUID(uid)
 *   -> avClientStart(sid,"admin",pw,..)  [pw-Bruteforce]
 *   -> avSendIOCtrl(IOTYPE_USER_IPCAM_START=0x01FF)
 *   -> avRecvFrameData()-Loop -> H.264 in Datei
 *
 * Output (kein Permission noetig): getExternalFilesDir ->
 *   /sdcard/Android/data/de.bmcservice.xdvharness/files/
 *     harness.log      (Schritt-fuer-Schritt-Protokoll, endet mit HARNESS_DONE)
 *     xdv_live.h264     (roher Video-Bitstream, falls Frames kamen)
 */
public class MainActivity extends Activity {

    static final String TAG = "XDVH";
    static final int IOTYPE_USER_IPCAM_START = 0x01FF;
    static final int AV_ER_DATA_NOREADY = -20012;

    PrintWriter lw;
    File outDir;

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        outDir = getExternalFilesDir(null);
        new Thread(this::run, "xdv-harness").start();
    }

    void log(String s) {
        Log.i(TAG, s);
        try { if (lw != null) { lw.println(System.currentTimeMillis() + " " + s); lw.flush(); } } catch (Throwable t) {}
    }

    void run() {
        try {
            outDir.mkdirs();
            lw = new PrintWriter(new FileOutputStream(new File(outDir, "harness.log"), false));
        } catch (Throwable t) {
            Log.e(TAG, "logfile", t);
        }

        String uid = "A5H9X6ZEXT86GJKL111A";
        String accs = "admin";
        String[] pws = { "", "888888", "8888", "6666", "123456", "admin",
                         "12345678", "000000", "123456789", "admin888888" };
        try {
            if (getIntent() != null) {
                String u = getIntent().getStringExtra("uid");
                if (u != null && u.length() > 0) uid = u;
                String p = getIntent().getStringExtra("pws");
                if (p != null && p.length() > 0) pws = p.split(",", -1);
                String a = getIntent().getStringExtra("acc");
                if (a != null && a.length() > 0) accs = a;
            }
        } catch (Throwable t) {}

        log("START uid=" + uid + " acc=" + accs + " pwsN=" + pws.length);

        // 1) Libs laden (Reihenfolge: IOTCAPIs vor AVAPIs)
        try { System.loadLibrary("IOTCAPIs"); log("loadLibrary IOTCAPIs ok"); }
        catch (Throwable t) { log("loadLibrary IOTCAPIs FAIL " + t); }
        try { System.loadLibrary("AVAPIs"); log("loadLibrary AVAPIs ok"); }
        catch (Throwable t) { log("loadLibrary AVAPIs FAIL " + t); }

        int sid = -999, avx = -999;
        String okPw = null;
        try {
            int ri = IOTCAPIs.IOTC_Initialize2(0);
            log("IOTC_Initialize2(0) = " + ri);
            int ra = AVAPIs.avInitialize(3);
            log("avInitialize(3) = " + ra);

            for (int attempt = 1; attempt <= 6 && sid < 0; attempt++) {
                sid = IOTCAPIs.IOTC_Connect_ByUID(uid);
                log("IOTC_Connect_ByUID try" + attempt + " = " + sid);
                if (sid < 0) Thread.sleep(1500);
            }
            if (sid < 0) { log("CONNECT FAILED sid=" + sid); finishLog(); return; }

            for (String pw : pws) {
                long[] servType = new long[1];
                int v = AVAPIs.avClientStart(sid, accs, pw, 8, servType, 0);
                log("avClientStart acc=" + accs + " pw='" + pw + "' = " + v + " servType=" + servType[0]);
                if (v >= 0) { avx = v; okPw = pw; break; }
            }
            if (avx < 0) { log("ALL PW FAILED (sid ok=" + sid + ")"); finishLog(); return; }
            log("AV CLIENT OK avIndex=" + avx + " pw='" + okPw + "'");

            int rc = AVAPIs.avSendIOCtrl(avx, IOTYPE_USER_IPCAM_START, new byte[8], 8);
            log("avSendIOCtrl(START 0x1FF) = " + rc);

            byte[] fb = new byte[786432];
            byte[] fi = new byte[24];
            int[] fno = new int[1];
            FileOutputStream h264 = new FileOutputStream(new File(outDir, "xdv_live.h264"), false);
            long tEnd = System.currentTimeMillis() + 25000L;
            int frames = 0; long bytes = 0; int neg = 0;
            while (System.currentTimeMillis() < tEnd) {
                int r = AVAPIs.avRecvFrameData(avx, fb, fb.length, fi, fi.length, fno);
                if (r > 0) {
                    h264.write(fb, 0, r);
                    frames++; bytes += r;
                    if (frames == 1) {
                        StringBuilder sb = new StringBuilder();
                        for (int i = 0; i < 8; i++) sb.append(String.format("%02x ", fi[i]));
                        log("FIRST FRAME bytes=" + r + " frameInfo[0..7]=" + sb + " (0x4e=H264)");
                    }
                } else if (r == AV_ER_DATA_NOREADY) {
                    Thread.sleep(8);
                } else {
                    neg++;
                    log("avRecvFrameData err=" + r);
                    if (neg > 5) break;
                    Thread.sleep(50);
                }
            }
            h264.flush(); h264.close();
            log("RECV DONE frames=" + frames + " bytes=" + bytes);

            try { AVAPIs.avClientStop(avx); } catch (Throwable t) {}
            try { IOTCAPIs.IOTC_Session_Close(sid); } catch (Throwable t) {}
            try { AVAPIs.avDeInitialize(); } catch (Throwable t) {}
            try { IOTCAPIs.IOTC_DeInitialize(); } catch (Throwable t) {}
            log("SUMMARY sid=" + sid + " avIndex=" + avx + " pw='" + okPw
                + "' frames=" + frames + " bytes=" + bytes);
        } catch (Throwable t) {
            log("EXCEPTION " + t);
            StackTraceElement[] st = t.getStackTrace();
            for (int i = 0; i < Math.min(6, st.length); i++) log("  at " + st[i]);
        }
        finishLog();
    }

    void finishLog() {
        log("HARNESS_DONE");
        try { if (lw != null) lw.close(); } catch (Throwable t) {}
    }
}
