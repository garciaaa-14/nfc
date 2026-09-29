package com.marti.nfcunlocklab;

import android.app.Activity;
import android.nfc.NfcAdapter;
import android.nfc.Tag;
import android.nfc.tech.NfcA;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.Gravity;
import android.view.View;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.lang.reflect.Method;
import java.util.Arrays;
import java.util.Locale;

public class MainActivity extends Activity implements NfcAdapter.ReaderCallback {

    private static final byte[] EXPECTED_UID = hexToBytes("04B0B1BB4A5980");
    private static final int PAGE_STATIC_LOCK = 0x02;
    private static final int PAGE_DYNAMIC_LOCK = 0xE2;

    private NfcAdapter nfcAdapter;
    private final Handler ui = new Handler(Looper.getMainLooper());
    private TextView status;
    private TextView summary;
    private TextView logView;
    private Button autoButton;
    private Button inspectButton;
    private volatile boolean autoArmed = false;
    private volatile boolean busy = false;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        nfcAdapter = NfcAdapter.getDefaultAdapter(this);
        setContentView(buildUi());
        status.setText(nfcAdapter == null ? "Aquest mòbil no té NFC." :
                (nfcAdapter.isEnabled() ? "Prem AUTO UNLOCK v3 i apropa la targeta." : "Activa l'NFC del mòbil."));
    }

    private View buildUi() {
        int pad = dp(18);
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(pad, pad, pad, pad);

        TextView title = new TextView(this);
        title.setText("NFC Unlock Lab v3");
        title.setTextSize(28);
        title.setGravity(Gravity.START);
        root.addView(title);

        TextView subtitle = new TextView(this);
        subtitle.setText("Auto Unlock avançat: rutes NTAG, autenticació, probes Magic/USCUID, compatibility write i inspecció root del stack NFC.");
        subtitle.setTextSize(15);
        subtitle.setPadding(0, dp(6), 0, dp(14));
        root.addView(subtitle);

        status = new TextView(this);
        status.setTextSize(18);
        status.setPadding(dp(12), dp(12), dp(12), dp(12));
        root.addView(status);

        autoButton = new Button(this);
        autoButton.setText("AUTO UNLOCK v3 — APROPA LA TARGETA");
        autoButton.setOnClickListener(v -> armAuto());
        root.addView(autoButton);

        inspectButton = new Button(this);
        inspectButton.setText("INSPECCIONAR ROOT / NFC DEL MÒBIL");
        inspectButton.setOnClickListener(v -> runRootInspectionAsync());
        root.addView(inspectButton);

        Button clear = new Button(this);
        clear.setText("NETEJAR LOG");
        clear.setOnClickListener(v -> { logView.setText(""); summary.setText(""); });
        root.addView(clear);

        summary = new TextView(this);
        summary.setTextSize(16);
        summary.setTextIsSelectable(true);
        summary.setPadding(0, dp(14), 0, dp(8));
        root.addView(summary);

        TextView logTitle = new TextView(this);
        logTitle.setText("Log tècnic");
        logTitle.setTextSize(18);
        logTitle.setPadding(0, dp(12), 0, dp(6));
        root.addView(logTitle);

        logView = new TextView(this);
        logView.setTextSize(13);
        logView.setTextIsSelectable(true);
        logView.setPadding(dp(10), dp(10), dp(10), dp(10));
        root.addView(logView);

        ScrollView scroll = new ScrollView(this);
        scroll.addView(root);
        return scroll;
    }

    private void armAuto() {
        if (busy) return;
        autoArmed = true;
        logView.setText("");
        summary.setText("");
        status.setText("AUTO UNLOCK v3 armat. Apropa la targeta i no la moguis.");
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (nfcAdapter != null) {
            int flags = NfcAdapter.FLAG_READER_NFC_A |
                    NfcAdapter.FLAG_READER_SKIP_NDEF_CHECK |
                    NfcAdapter.FLAG_READER_NO_PLATFORM_SOUNDS;
            nfcAdapter.enableReaderMode(this, this, flags, null);
        }
    }

    @Override
    protected void onPause() {
        super.onPause();
        if (nfcAdapter != null) nfcAdapter.disableReaderMode(this);
    }

    @Override
    public void onTagDiscovered(Tag tag) {
        if (!autoArmed || busy) return;
        autoArmed = false;
        busy = true;
        setButtons(false);
        new Thread(() -> {
            try { runAutoUnlock(tag); }
            finally { busy = false; setButtons(true); }
        }).start();
    }

    private void runAutoUnlock(Tag tag) {
        appendLog("=== TARGETA DETECTADA ===");
        appendLog("UID: " + toHexColon(tag.getId()));
        if (!Arrays.equals(tag.getId(), EXPECTED_UID)) {
            setStatus("UID diferent. No s'ha escrit res.");
            appendLog("ATURAT: UID no autoritzat.");
            return;
        }

        setStatus("AUTO UNLOCK v3 en curs. No moguis la targeta.");

        byte[] version = freshTx(tag, new byte[]{0x60}, "GET_VERSION");
        byte[] p2 = readPage(tag, PAGE_STATIC_LOCK, "READ 02");
        byte[] pE2 = readPage(tag, PAGE_DYNAMIC_LOCK, "READ E2");
        appendLog("GET_VERSION: " + hex(version));
        appendLog("PAGE 02: " + hex(p2));
        appendLog("PAGE E2: " + hex(pE2));

        if (locksClear(p2, pE2)) {
            finishSuccess("La targeta ja està desbloquejada.", p2, pE2);
            return;
        }

        appendLog("\n=== FASE 1: PROBES SENSE AUTH ===");
        ProbeResult pre = probeMagic(tag, false);

        appendLog("\n=== FASE 2: A2 ESTÀNDARD ===");
        attemptA2(tag, false);
        if (checkSuccess(tag, "A2 estàndard")) return;

        appendLog("\n=== FASE 3: PWD_AUTH FFFFFFFF + A2 ===");
        attemptA2(tag, true);
        if (checkSuccess(tag, "PWD_AUTH + A2")) return;

        appendLog("\n=== FASE 4: PROBES MAGIC DESPRÉS DE PWD_AUTH ===");
        ProbeResult post = probeMagic(tag, true);

        appendLog("\n=== FASE 5: COMPATIBILITY WRITE A0 ===");
        attemptCompatibilityWrite(tag, false);
        if (checkSuccess(tag, "A0 compatibility write")) return;

        appendLog("\n=== FASE 6: PWD_AUTH + COMPATIBILITY WRITE A0 ===");
        attemptCompatibilityWrite(tag, true);
        if (checkSuccess(tag, "PWD_AUTH + A0 compatibility write")) return;

        appendLog("\n=== FASE 7: MAGIC WAKEUP EN BYTE COMPLET ===");
        tryByteMagic(tag, (byte)0x40, (byte)0x43, "40/43");
        if (checkSuccess(tag, "Magic 40/43 byte")) return;
        tryByteMagic(tag, (byte)0x20, (byte)0x23, "20/23");
        if (checkSuccess(tag, "Magic 20/23 byte")) return;

        appendLog("\n=== FASE 8: INSPECCIÓ ROOT DEL MÒBIL ===");
        RootReport rr = rootInspection();
        appendLog(rr.text);

        p2 = readPage(tag, PAGE_STATIC_LOCK, "FINAL 02");
        pE2 = readPage(tag, PAGE_DYNAMIC_LOCK, "FINAL E2");

        boolean anyMagic = pre.anyMagic || post.anyMagic;
        boolean anyUscuid = pre.uscuid || post.uscuid;
        StringBuilder result = new StringBuilder();
        result.append("Auto Unlock v3 completat. Els lock bytes continuen actius.\n\n")
                .append("02 = ").append(hex(p2)).append("\n")
                .append("E2 = ").append(hex(pE2)).append("\n\n")
                .append("Magic pages detectades: ").append(anyMagic ? "sí" : "no").append("\n")
                .append("USCUID config detectada: ").append(anyUscuid ? "sí" : "no").append("\n")
                .append("Root: ").append(rr.root ? "sí" : "no").append("\n")
                .append("/dev/pn553: ").append(rr.pn553 ? "sí" : "no").append("\n")
                .append("DTA/eines NFC detectades: ").append(rr.dtaOrTool ? "sí" : "no").append("\n\n");

        if (post.anyMagic) {
            result.append("Pista: les pàgines Magic només responen després d'autenticar. Això apunta a una variant Magic protegida. ");
        } else if (rr.root && rr.pn553) {
            result.append("La via que queda és el controlador NFC de baix nivell. L'app ha inspeccionat el sistema, però no envia paquets NCI arbitraris si no detecta una interfície coneguda, per evitar deixar l'NFC del mòbil inestable. ");
        }

        result.append("Si el xip exigeix 40(7)/20(7), Android NfcA no pot generar aquesta trama de 7 bits.");
        setSummary(result.toString());
        setStatus("AUTO UNLOCK v3 acabat: locks encara actius.");
    }

    private ProbeResult probeMagic(Tag tag, boolean authenticate) {
        ProbeResult out = new ProbeResult();
        NfcA nfca = NfcA.get(tag);
        if (nfca == null) return out;
        try {
            nfca.connect();
            nfca.setTimeout(1000);
            if (authenticate) {
                txInSession(nfca, new byte[]{0x1B,(byte)0xFF,(byte)0xFF,(byte)0xFF,(byte)0xFF}, "PWD_AUTH(session)");
            }
            byte[] f0 = txSafe(nfca, new byte[]{0x30,(byte)0xF0}, "30 F0");
            byte[] fa = txSafe(nfca, new byte[]{0x30,(byte)0xFA}, "30 FA");
            byte[] fc = txSafe(nfca, new byte[]{0x30,(byte)0xFC}, "30 FC");
            byte[] e050 = txSafe(nfca, new byte[]{(byte)0xE0,0x50}, "E0 50");
            byte[] e080 = txSafe(nfca, new byte[]{(byte)0xE0,(byte)0x80}, "E0 80");
            out.anyMagic = f0 != null || fa != null || fc != null;
            out.uscuid = looksLikeUscuid(e050) || looksLikeUscuid(e080);
            appendLog("Magic pages: " + (out.anyMagic ? "DETECTADES" : "no"));
            appendLog("USCUID: " + (out.uscuid ? "DETECTADA" : "no"));
        } catch (Exception e) {
            appendLog("Probe session error: " + e.getClass().getSimpleName() + ": " + e.getMessage());
        } finally {
            try { nfca.close(); } catch (Exception ignored) {}
        }
        return out;
    }

    private void attemptA2(Tag tag, boolean authenticate) {
        NfcA nfca = NfcA.get(tag);
        if (nfca == null) return;
        try {
            nfca.connect();
            nfca.setTimeout(1200);
            if (authenticate) txInSession(nfca, new byte[]{0x1B,(byte)0xFF,(byte)0xFF,(byte)0xFF,(byte)0xFF}, "PWD_AUTH(session)");
            byte[] p2 = first4(txInSession(nfca, new byte[]{0x30,0x02}, "pre 02"));
            byte[] pe2 = first4(txInSession(nfca, new byte[]{0x30,(byte)0xE2}, "pre E2"));
            if (p2 == null || pe2 == null) return;
            txInSession(nfca, new byte[]{(byte)0xA2,0x02,p2[0],p2[1],0x00,0x00}, "A2 02 clear locks");
            txInSession(nfca, new byte[]{(byte)0xA2,(byte)0xE2,0x00,0x00,0x00,pe2[3]}, "A2 E2 clear locks");
        } catch (Exception e) {
            appendLog("A2 error: " + e.getClass().getSimpleName() + ": " + e.getMessage());
        } finally {
            try { nfca.close(); } catch (Exception ignored) {}
        }
    }

    private void attemptCompatibilityWrite(Tag tag, boolean authenticate) {
        NfcA nfca = NfcA.get(tag);
        if (nfca == null) return;
        try {
            nfca.connect();
            nfca.setTimeout(1400);
            if (authenticate) txInSession(nfca, new byte[]{0x1B,(byte)0xFF,(byte)0xFF,(byte)0xFF,(byte)0xFF}, "PWD_AUTH(session)");

            byte[] r02 = txInSession(nfca, new byte[]{0x30,0x02}, "READ 02..05");
            byte[] rE2 = txInSession(nfca, new byte[]{0x30,(byte)0xE2}, "READ E2..E5");
            if (r02 == null || r02.length < 16 || rE2 == null || rE2.length < 16) return;

            byte[] d02 = Arrays.copyOf(r02, 16);
            d02[2] = 0x00; d02[3] = 0x00;
            byte[] dE2 = Arrays.copyOf(rE2, 16);
            dE2[0] = 0x00; dE2[1] = 0x00; dE2[2] = 0x00;

            byte[] a = txSafe(nfca, new byte[]{(byte)0xA0,0x02}, "A0 02 stage1");
            if (isAck(a)) txSafe(nfca, d02, "A0 02 stage2 data16");
            else appendLog("A0 02 no ACK; no s'envien les 16 dades.");

            byte[] b = txSafe(nfca, new byte[]{(byte)0xA0,(byte)0xE2}, "A0 E2 stage1");
            if (isAck(b)) txSafe(nfca, dE2, "A0 E2 stage2 data16");
            else appendLog("A0 E2 no ACK; no s'envien les 16 dades.");
        } catch (Exception e) {
            appendLog("Compatibility write error: " + e.getClass().getSimpleName() + ": " + e.getMessage());
        } finally {
            try { nfca.close(); } catch (Exception ignored) {}
        }
    }

    private void tryByteMagic(Tag tag, byte first, byte second, String name) {
        NfcA nfca = NfcA.get(tag);
        if (nfca == null) return;
        try {
            nfca.connect();
            nfca.setTimeout(700);
            txSafe(nfca, new byte[]{first}, "MAGIC " + name + " step1");
            txSafe(nfca, new byte[]{second}, "MAGIC " + name + " step2");
            byte[] p2 = first4(txSafe(nfca, new byte[]{0x30,0x02}, "MAGIC " + name + " read02"));
            byte[] pe2 = first4(txSafe(nfca, new byte[]{0x30,(byte)0xE2}, "MAGIC " + name + " readE2"));
            if (p2 != null && pe2 != null) {
                txSafe(nfca, new byte[]{(byte)0xA2,0x02,p2[0],p2[1],0x00,0x00}, "MAGIC " + name + " write02");
                txSafe(nfca, new byte[]{(byte)0xA2,(byte)0xE2,0x00,0x00,0x00,pe2[3]}, "MAGIC " + name + " writeE2");
            }
        } catch (Exception e) {
            appendLog("MAGIC " + name + " session: " + e.getMessage());
        } finally {
            try { nfca.close(); } catch (Exception ignored) {}
        }
    }

    private boolean checkSuccess(Tag tag, String route) {
        byte[] p2 = readPage(tag, PAGE_STATIC_LOCK, "VERIFY " + route + " 02");
        byte[] pe2 = readPage(tag, PAGE_DYNAMIC_LOCK, "VERIFY " + route + " E2");
        if (locksClear(p2, pe2)) {
            finishSuccess("ÈXIT amb " + route + ".", p2, pe2);
            return true;
        }
        return false;
    }

    private byte[] txInSession(NfcA nfca, byte[] cmd, String label) throws Exception {
        appendLog(label + " TX: " + hex(cmd));
        byte[] r = nfca.transceive(cmd);
        appendLog(label + " RX: " + hex(r));
        return r;
    }

    private byte[] txSafe(NfcA nfca, byte[] cmd, String label) {
        try { return txInSession(nfca, cmd, label); }
        catch (Exception e) {
            appendLog(label + " ERROR: " + e.getClass().getSimpleName() + ": " + e.getMessage());
            return null;
        }
    }

    private byte[] freshTx(Tag tag, byte[] cmd, String label) {
        NfcA nfca = NfcA.get(tag);
        if (nfca == null) return null;
        try {
            nfca.connect();
            nfca.setTimeout(1000);
            return txSafe(nfca, cmd, label);
        } catch (Exception e) {
            appendLog(label + " session ERROR: " + e.getMessage());
            return null;
        } finally {
            try { nfca.close(); } catch (Exception ignored) {}
        }
    }

    private byte[] readPage(Tag tag, int page, String label) {
        return first4(freshTx(tag, new byte[]{0x30,(byte)page}, label));
    }

    private boolean locksClear(byte[] p2, byte[] pe2) {
        return p2 != null && pe2 != null && p2[2] == 0 && p2[3] == 0 && pe2[0] == 0 && pe2[1] == 0 && pe2[2] == 0;
    }

    private boolean isAck(byte[] r) {
        return r != null && r.length >= 1 && (r[0] & 0x0F) == 0x0A;
    }

    private boolean looksLikeUscuid(byte[] r) {
        return r != null && r.length >= 16 && ((r[0] & 0xFF) == 0x85 || ((r[0] & 0xFF) == 0x7A && (r[1] & 0xFF) == 0xFF));
    }

    private void runRootInspectionAsync() {
        if (busy) return;
        busy = true;
        setButtons(false);
        status.setText("Inspeccionant root i stack NFC...");
        new Thread(() -> {
            try {
                RootReport rr = rootInspection();
                appendLog("=== ROOT/NFC INSPECTION ===\n" + rr.text);
                setSummary("ROOT=" + (rr.root ? "YES" : "NO") + "\nPN553=" + (rr.pn553 ? "YES" : "NO") +
                        "\nDTA/eina NFC=" + (rr.dtaOrTool ? "YES" : "NO") +
                        "\n\nMira el log tècnic per veure els fitxers/eines detectats.");
                setStatus("Inspecció root acabada.");
            } finally {
                busy = false;
                setButtons(true);
            }
        }).start();
    }

    private RootReport rootInspection() {
        RootReport rr = new RootReport();
        String script =
                "echo '--- ID ---'; id; " +
                "echo '--- DEVICE ---'; getprop ro.product.manufacturer; getprop ro.product.model; getprop ro.product.device; getprop ro.build.version.release; " +
                "echo '--- PN553 ---'; ls -lZ /dev/pn553 2>&1; readlink -f /sys/class/misc/pn553/device/driver 2>&1; " +
                "echo '--- NFC SERVICES ---'; service list 2>/dev/null | grep -i nfc; " +
                "echo '--- CMD NFC ---'; cmd nfc help 2>&1 | head -80; " +
                "echo '--- DUMPSYS NFC ---'; dumpsys nfc 2>&1 | head -120; " +
                "echo '--- NFC PACKAGES ---'; pm list packages 2>/dev/null | grep -Ei 'nfc|dta'; " +
                "echo '--- NFC BINARIES ---'; find /vendor/bin /system/bin /system_ext/bin /product/bin -maxdepth 2 -type f \\( -iname '*nfc*' -o -iname '*dta*' \\) 2>/dev/null | head -100; " +
                "echo '--- NFC CONFIGS ---'; find /vendor/etc /system/etc /system_ext/etc /product/etc /odm/etc -maxdepth 3 -type f \\( -iname '*nfc*' -o -iname 'libnfc*' \\) 2>/dev/null | head -100; " +
                "echo '--- NXP/DTA KEYS ---'; for f in $(find /vendor/etc /system/etc /system_ext/etc /product/etc /odm/etc -maxdepth 3 -type f \\( -iname '*nfc*' -o -iname 'libnfc*' \\) 2>/dev/null | head -30); do echo FILE:$f; grep -Eai 'DTA|RAW|NXP|EXTNS|NCI|PN553' $f 2>/dev/null | head -25; done; ";
        String out = runSu(script, 18000);
        rr.text = trim(out, 18000);
        rr.root = out.contains("uid=0");
        rr.pn553 = out.contains("/dev/pn553") && !out.contains("No such file or directory");
        String lower = out.toLowerCase(Locale.US);
        rr.dtaOrTool = lower.contains("dta") || lower.contains("nfc_test") || lower.contains("nxpnfc") || lower.contains("raw frame");

        try {
            Class<?> c = Class.forName("android.nfc.dta.NfcDta");
            Method[] methods = c.getDeclaredMethods();
            rr.text += "\n--- HIDDEN DTA CLASS ---\nFOUND android.nfc.dta.NfcDta methods=" + methods.length + "\n";
            rr.dtaOrTool = true;
        } catch (Throwable t) {
            rr.text += "\n--- HIDDEN DTA CLASS ---\nNOT FOUND/blocked: " + t.getClass().getSimpleName() + "\n";
        }
        return rr;
    }

    private String runSu(String command, int maxChars) {
        StringBuilder out = new StringBuilder();
        try {
            Process p = Runtime.getRuntime().exec(new String[]{"su", "-c", command});
            BufferedReader br = new BufferedReader(new InputStreamReader(p.getInputStream()));
            BufferedReader er = new BufferedReader(new InputStreamReader(p.getErrorStream()));
            String line;
            while ((line = br.readLine()) != null && out.length() < maxChars) out.append(line).append('\n');
            while ((line = er.readLine()) != null && out.length() < maxChars) out.append("ERR: ").append(line).append('\n');
            p.waitFor();
        } catch (Exception e) {
            out.append("SU ERROR: ").append(e.getClass().getSimpleName()).append(": ").append(e.getMessage()).append('\n');
        }
        return out.toString();
    }

    private void finishSuccess(String message, byte[] p2, byte[] pe2) {
        appendLog("SUCCESS: " + message);
        setSummary(message + "\n\n02 = " + hex(p2) + "\nE2 = " + hex(pe2) + "\n\nComprova ara amb NFC Tools que sigui Writable: Yes.");
        setStatus("ÈXIT: lock bytes a zero.");
    }

    private static byte[] first4(byte[] in) {
        return in != null && in.length >= 4 ? Arrays.copyOfRange(in,0,4) : null;
    }

    private static String hex(byte[] data) {
        if (data == null) return "<no response>";
        StringBuilder sb = new StringBuilder();
        for (int i=0;i<data.length;i++) {
            if (i>0) sb.append(' ');
            sb.append(String.format(Locale.US,"%02X",data[i] & 0xFF));
        }
        return sb.toString();
    }

    private static String toHexColon(byte[] data) {
        StringBuilder sb = new StringBuilder();
        for (int i=0;i<data.length;i++) {
            if (i>0) sb.append(':');
            sb.append(String.format(Locale.US,"%02X",data[i] & 0xFF));
        }
        return sb.toString();
    }

    private static byte[] hexToBytes(String s) {
        String clean = s.replaceAll("[^0-9A-Fa-f]", "");
        byte[] out = new byte[clean.length()/2];
        for (int i=0;i<out.length;i++) out[i] = (byte)Integer.parseInt(clean.substring(i*2,i*2+2),16);
        return out;
    }

    private static String trim(String s, int n) {
        return s == null ? "" : (s.length() <= n ? s : s.substring(0,n) + "\n...[truncated]");
    }

    private void appendLog(String s) { ui.post(() -> logView.append(s + "\n")); }
    private void setStatus(String s) { ui.post(() -> status.setText(s)); }
    private void setSummary(String s) { ui.post(() -> summary.setText(s)); }
    private void setButtons(boolean enabled) { ui.post(() -> { autoButton.setEnabled(enabled); inspectButton.setEnabled(enabled); }); }
    private int dp(int v) { return (int)(v * getResources().getDisplayMetrics().density + 0.5f); }

    private static class ProbeResult {
        boolean anyMagic;
        boolean uscuid;
    }

    private static class RootReport {
        boolean root;
        boolean pn553;
        boolean dtaOrTool;
        String text = "";
    }
}
