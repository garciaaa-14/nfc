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
    private volatile RootReport cachedRootReport;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        nfcAdapter = NfcAdapter.getDefaultAdapter(this);
        setContentView(buildUi());

        if (nfcAdapter == null) {
            status.setText("Aquest mòbil no té NFC.");
        } else if (!nfcAdapter.isEnabled()) {
            status.setText("Activa l'NFC del mòbil.");
        } else {
            status.setText("Prem AUTO UNLOCK v4. Primer inspeccionarà el mòbil i després et demanarà la targeta.");
        }
    }

    private View buildUi() {
        int pad = dp(18);
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(pad, pad, pad, pad);

        TextView title = new TextView(this);
        title.setText("NFC Unlock Lab v4");
        title.setTextSize(28);
        title.setGravity(Gravity.START);
        root.addView(title);

        TextView subtitle = new TextView(this);
        subtitle.setText("Auto Unlock: inspecció root/HAL primer, després rutes NTAG/Magic segures. Evita wakeups de 8 bits que provoquen TagLost.");
        subtitle.setTextSize(15);
        subtitle.setPadding(0, dp(6), 0, dp(14));
        root.addView(subtitle);

        status = new TextView(this);
        status.setTextSize(18);
        status.setPadding(dp(12), dp(12), dp(12), dp(12));
        root.addView(status);

        autoButton = new Button(this);
        autoButton.setText("AUTO UNLOCK v4");
        autoButton.setOnClickListener(v -> startAutoFlow());
        root.addView(autoButton);

        inspectButton = new Button(this);
        inspectButton.setText("INSPECCIONAR ROOT / NFC DEL MÒBIL");
        inspectButton.setOnClickListener(v -> runRootInspectionOnly());
        root.addView(inspectButton);

        Button clear = new Button(this);
        clear.setText("NETEJAR LOG");
        clear.setOnClickListener(v -> {
            logView.setText("");
            summary.setText("");
        });
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

    private void startAutoFlow() {
        if (busy) return;
        busy = true;
        autoArmed = false;
        setButtons(false);
        logView.setText("");
        summary.setText("");
        setStatus("Fase 0: inspeccionant root/HAL/NFC del mòbil...");

        new Thread(() -> {
            try {
                RootReport rr = rootInspection();
                cachedRootReport = rr;
                appendLog("=== FASE 0: ROOT/NFC INSPECTION ===\n" + rr.text);
                appendRootSummary(rr);

                autoArmed = true;
                setStatus("Inspecció acabada. Ara apropa la targeta i no la moguis.");
            } finally {
                busy = false;
                setButtons(true);
            }
        }).start();
    }

    private void runRootInspectionOnly() {
        if (busy) return;
        busy = true;
        setButtons(false);
        logView.setText("");
        summary.setText("");
        setStatus("Inspeccionant root/HAL/NFC...");

        new Thread(() -> {
            try {
                RootReport rr = rootInspection();
                cachedRootReport = rr;
                appendLog("=== ROOT/NFC INSPECTION ===\n" + rr.text);
                appendRootSummary(rr);
                setStatus("Inspecció acabada.");
            } finally {
                busy = false;
                setButtons(true);
            }
        }).start();
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
            try {
                runAutoUnlock(tag);
            } finally {
                busy = false;
                setButtons(true);
            }
        }).start();
    }

    private void runAutoUnlock(Tag tag) {
        appendLog("\n=== TARGETA DETECTADA ===");
        appendLog("UID: " + toHexColon(tag.getId()));

        if (!Arrays.equals(tag.getId(), EXPECTED_UID)) {
            appendLog("ATURAT: UID no autoritzat.");
            setStatus("UID diferent. No s'ha escrit res.");
            return;
        }

        setStatus("AUTO UNLOCK v4 en curs. No moguis la targeta.");

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

        appendLog("\n=== FASE 1: PROBES MAGIC SENSE AUTH ===");
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

        // Important: NO 40/20 as full bytes here. We already know that makes the tag drop.
        appendLog("\n=== FASE 7: DECISIÓ LOW-LEVEL ===");
        RootReport rr = cachedRootReport != null ? cachedRootReport : rootInspection();
        appendLowLevelDecision(rr);

        p2 = readPage(tag, PAGE_STATIC_LOCK, "FINAL 02");
        pE2 = readPage(tag, PAGE_DYNAMIC_LOCK, "FINAL E2");

        StringBuilder result = new StringBuilder();
        result.append("Auto Unlock v4 completat. Els lock bytes continuen actius.\n\n")
                .append("02 = ").append(hex(p2)).append("\n")
                .append("E2 = ").append(hex(pE2)).append("\n\n")
                .append("Magic pages detectades: ").append((pre.anyMagic || post.anyMagic) ? "sí" : "no").append("\n")
                .append("USCUID detectat: ").append((pre.uscuid || post.uscuid) ? "sí" : "no").append("\n")
                .append("Root: ").append(rr.root ? "sí" : "no").append("\n")
                .append("/dev/pn553: ").append(rr.pn553 ? "sí" : "no").append("\n")
                .append("pn553 open test: ").append(rr.pn553Open ? "sí" : "no").append("\n")
                .append("DTA tool/package: ").append(rr.dtaTool ? "sí" : "no").append("\n")
                .append("Raw/vendor symbols: ").append(rr.rawSymbols ? "sí" : "no").append("\n")
                .append("Hidden NfcDta class: ").append(rr.hiddenDtaClass ? "sí" : "no").append("\n\n");

        if (rr.lowLevelCandidate()) {
            result.append("Hi ha indicis d'una ruta low-level al stack NXP, però no hi ha una API pública que permeti transmetre 40(7)/20(7). La v4 no envia NCI arbitraris directament al controlador perquè això podria desincronitzar l'NFC del mòbil.\n\n");
        } else {
            result.append("No s'ha trobat cap eina/interfície low-level coneguda al sistema que permeti generar la trama de 7 bits necessària.\n\n");
        }

        result.append("La targeta continua intacta i llegible; les rutes que han escrit sempre s'han verificat després.");
        setSummary(result.toString());
        setStatus("AUTO UNLOCK v4 acabat: locks encara actius.");
    }

    private ProbeResult probeMagic(Tag tag, boolean authenticate) {
        ProbeResult out = new ProbeResult();
        NfcA nfca = NfcA.get(tag);
        if (nfca == null) return out;

        try {
            nfca.connect();
            nfca.setTimeout(1000);

            if (authenticate) {
                txInSession(nfca,
                        new byte[]{0x1B, (byte) 0xFF, (byte) 0xFF, (byte) 0xFF, (byte) 0xFF},
                        "PWD_AUTH(session)");
            }

            byte[] f0 = txSafe(nfca, new byte[]{0x30, (byte) 0xF0}, "30 F0");
            byte[] fa = txSafe(nfca, new byte[]{0x30, (byte) 0xFA}, "30 FA");
            byte[] fc = txSafe(nfca, new byte[]{0x30, (byte) 0xFC}, "30 FC");
            byte[] e050 = txSafe(nfca, new byte[]{(byte) 0xE0, 0x50}, "E0 50");
            byte[] e080 = txSafe(nfca, new byte[]{(byte) 0xE0, (byte) 0x80}, "E0 80");

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

            if (authenticate) {
                txInSession(nfca,
                        new byte[]{0x1B, (byte) 0xFF, (byte) 0xFF, (byte) 0xFF, (byte) 0xFF},
                        "PWD_AUTH(session)");
            }

            byte[] p2 = first4(txInSession(nfca, new byte[]{0x30, 0x02}, "pre 02"));
            byte[] pe2 = first4(txInSession(nfca, new byte[]{0x30, (byte) 0xE2}, "pre E2"));
            if (p2 == null || pe2 == null) return;

            txInSession(nfca,
                    new byte[]{(byte) 0xA2, 0x02, p2[0], p2[1], 0x00, 0x00},
                    "A2 02 clear locks");
            txInSession(nfca,
                    new byte[]{(byte) 0xA2, (byte) 0xE2, 0x00, 0x00, 0x00, pe2[3]},
                    "A2 E2 clear locks");
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

            if (authenticate) {
                txInSession(nfca,
                        new byte[]{0x1B, (byte) 0xFF, (byte) 0xFF, (byte) 0xFF, (byte) 0xFF},
                        "PWD_AUTH(session)");
            }

            byte[] r02 = txInSession(nfca, new byte[]{0x30, 0x02}, "READ 02..05");
            byte[] rE2 = txInSession(nfca, new byte[]{0x30, (byte) 0xE2}, "READ E2..E5");
            if (r02 == null || r02.length < 16 || rE2 == null || rE2.length < 16) return;

            byte[] d02 = Arrays.copyOf(r02, 16);
            d02[2] = 0x00;
            d02[3] = 0x00;

            byte[] dE2 = Arrays.copyOf(rE2, 16);
            dE2[0] = 0x00;
            dE2[1] = 0x00;
            dE2[2] = 0x00;

            byte[] a = txSafe(nfca, new byte[]{(byte) 0xA0, 0x02}, "A0 02 stage1");
            if (isAck(a)) {
                txSafe(nfca, d02, "A0 02 stage2 data16");
            } else {
                appendLog("A0 02 no ACK; no s'envien les 16 dades.");
            }

            byte[] b = txSafe(nfca, new byte[]{(byte) 0xA0, (byte) 0xE2}, "A0 E2 stage1");
            if (isAck(b)) {
                txSafe(nfca, dE2, "A0 E2 stage2 data16");
            } else {
                appendLog("A0 E2 no ACK; no s'envien les 16 dades.");
            }
        } catch (Exception e) {
            appendLog("Compatibility write error: " + e.getClass().getSimpleName() + ": " + e.getMessage());
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
        try {
            return txInSession(nfca, cmd, label);
        } catch (Exception e) {
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
        return first4(freshTx(tag, new byte[]{0x30, (byte) page}, label));
    }

    private boolean locksClear(byte[] p2, byte[] pe2) {
        return p2 != null && pe2 != null &&
                p2[2] == 0 && p2[3] == 0 &&
                pe2[0] == 0 && pe2[1] == 0 && pe2[2] == 0;
    }

    private boolean isAck(byte[] r) {
        return r != null && r.length >= 1 && (r[0] & 0x0F) == 0x0A;
    }

    private boolean looksLikeUscuid(byte[] r) {
        return r != null && r.length >= 16 &&
                (((r[0] & 0xFF) == 0x85) ||
                        ((r[0] & 0xFF) == 0x7A && (r[1] & 0xFF) == 0xFF));
    }

    private RootReport rootInspection() {
        RootReport rr = new RootReport();

        String script =
                "echo '--- ID ---'; id; " +
                "echo '--- DEVICE ---'; getprop ro.product.manufacturer; getprop ro.product.model; getprop ro.product.device; getprop ro.build.version.release; " +
                "echo '--- PN553 ---'; ls -lZ /dev/pn553 2>&1; " +
                "if sh -c 'exec 9<>/dev/pn553' 2>/dev/null; then echo __PN553_OPEN_OK__; else echo __PN553_OPEN_FAIL__; fi; " +
                "echo '--- NFC SERVICES ---'; service list 2>/dev/null | grep -i nfc; " +
                "echo '--- HAL SERVICES ---'; (lshal 2>/dev/null || true) | grep -Ei 'nfc|nxp' | head -80; " +
                "echo '--- CMD NFC ---'; cmd nfc help 2>&1 | head -100; " +
                "echo '--- DUMPSYS NFC ---'; dumpsys nfc 2>&1 | head -140; " +
                "echo '--- NFC PROPS ---'; getprop 2>/dev/null | grep -Ei 'nfc|nxp|sn220|pn553' | head -120; " +
                "echo '--- NFC PACKAGES ---'; PKGS=$(pm list packages 2>/dev/null | grep -Ei 'nxp.*dta|dta.*nfc|nfc.*dta|nxp.*nfc'); echo \"$PKGS\"; [ -n \"$PKGS\" ] && echo __DTA_TOOL_FOUND__; " +
                "echo '--- NFC BINARIES ---'; TOOLS=$(find /vendor/bin /system/bin /system_ext/bin /product/bin /odm/bin -maxdepth 3 -type f 2>/dev/null | grep -Ei '/[^/]*(nfc|dta)[^/]*$' | head -120); echo \"$TOOLS\"; echo \"$TOOLS\" | grep -Eqi 'dta|nfc_test|nxp' && echo __DTA_TOOL_FOUND__; " +
                "echo '--- NFC CONFIGS ---'; CFGS=$(find /vendor/etc /system/etc /system_ext/etc /product/etc /odm/etc -maxdepth 4 -type f 2>/dev/null | grep -Ei '/([^/]*nfc[^/]*|libnfc[^/]*)$' | head -120); echo \"$CFGS\"; " +
                "echo '--- NFC LIBS ---'; LIBS=$(find /vendor/lib64 /system/lib64 /system_ext/lib64 /product/lib64 /odm/lib64 /vendor/lib /system/lib -maxdepth 3 -type f 2>/dev/null | grep -Ei '/[^/]*nfc[^/]*\\.so$' | head -80); echo \"$LIBS\"; " +
                "echo '--- RAW/DTA SYMBOL SCAN ---'; RAWFOUND=0; for f in $LIBS; do if command -v strings >/dev/null 2>&1; then S=$(strings \"$f\" 2>/dev/null | grep -E 'NFA_SendRawFrame|NFA_SendRawVsCommand|phNxpEnable_DtaMode|INfcDta' | head -8); else S=$(grep -aE 'NFA_SendRawFrame|NFA_SendRawVsCommand|phNxpEnable_DtaMode|INfcDta' \"$f\" 2>/dev/null | head -8); fi; if [ -n \"$S\" ]; then echo FILE:$f; echo \"$S\"; RAWFOUND=1; fi; done; [ $RAWFOUND -eq 1 ] && echo __RAW_SYMBOL_FOUND__; " +
                "echo '--- NXP CONFIG HINTS ---'; for f in $CFGS; do H=$(grep -Eai 'DTA|RAW|NXP|EXTNS|NCI|PN553|SN220' \"$f\" 2>/dev/null | head -15); if [ -n \"$H\" ]; then echo FILE:$f; echo \"$H\"; fi; done; " +
                "echo '--- NFC DEVICE USERS ---'; (lsof /dev/pn553 2>/dev/null || fuser /dev/pn553 2>/dev/null || true) | head -40; ";

        String out = runSu(script + " 2>&1", 28000);
        rr.text = trim(out, 28000);
        rr.root = out.contains("uid=0");
        rr.pn553 = out.contains("/dev/pn553") && !out.contains("No such file or directory");
        rr.pn553Open = out.contains("__PN553_OPEN_OK__");
        rr.dtaTool = out.contains("__DTA_TOOL_FOUND__");
        rr.rawSymbols = out.contains("__RAW_SYMBOL_FOUND__");

        try {
            Class<?> c = Class.forName("android.nfc.dta.NfcDta");
            Method[] methods = c.getDeclaredMethods();
            rr.hiddenDtaClass = true;
            rr.text += "\n--- HIDDEN DTA CLASS ---\nFOUND android.nfc.dta.NfcDta methods=" + methods.length + "\n";
        } catch (Throwable t) {
            rr.hiddenDtaClass = false;
            rr.text += "\n--- HIDDEN DTA CLASS ---\nNOT FOUND/blocked: " + t.getClass().getSimpleName() + "\n";
        }

        rr.text += "\n--- LOW-LEVEL DECISION ---\n" +
                "ROOT=" + (rr.root ? "YES" : "NO") +
                " PN553=" + (rr.pn553 ? "YES" : "NO") +
                " PN553_OPEN=" + (rr.pn553Open ? "YES" : "NO") +
                " DTA_TOOL=" + (rr.dtaTool ? "YES" : "NO") +
                " RAW_SYMBOLS=" + (rr.rawSymbols ? "YES" : "NO") +
                " HIDDEN_DTA=" + (rr.hiddenDtaClass ? "YES" : "NO") + "\n";

        return rr;
    }

    private void appendRootSummary(RootReport rr) {
        String s = "ROOT=" + (rr.root ? "YES" : "NO") +
                "\nPN553=" + (rr.pn553 ? "YES" : "NO") +
                "\nPN553 open=" + (rr.pn553Open ? "YES" : "NO") +
                "\nDTA tool/package=" + (rr.dtaTool ? "YES" : "NO") +
                "\nRaw/vendor symbols=" + (rr.rawSymbols ? "YES" : "NO") +
                "\nHidden NfcDta=" + (rr.hiddenDtaClass ? "YES" : "NO") +
                "\nLow-level candidate=" + (rr.lowLevelCandidate() ? "YES" : "NO");
        setSummary(s);
    }

    private void appendLowLevelDecision(RootReport rr) {
        appendLog("ROOT=" + (rr.root ? "YES" : "NO"));
        appendLog("PN553=" + (rr.pn553 ? "YES" : "NO"));
        appendLog("PN553_OPEN=" + (rr.pn553Open ? "YES" : "NO"));
        appendLog("DTA_TOOL=" + (rr.dtaTool ? "YES" : "NO"));
        appendLog("RAW_SYMBOLS=" + (rr.rawSymbols ? "YES" : "NO"));
        appendLog("HIDDEN_DTA=" + (rr.hiddenDtaClass ? "YES" : "NO"));

        if (rr.lowLevelCandidate()) {
            appendLog("LOW-LEVEL ROUTE CANDIDATE: YES");
            appendLog("No s'envien NCI arbitraris a /dev/pn553. Cal una API/ordre vendor coneguda que permeti definir el nombre de bits TX.");
        } else {
            appendLog("LOW-LEVEL ROUTE CANDIDATE: NO");
            appendLog("No s'ha trobat cap eina o interfície coneguda per generar 40(7)/20(7) des del telèfon.");
        }
    }

    private String runSu(String command, int maxChars) {
        StringBuilder out = new StringBuilder();
        try {
            Process p = Runtime.getRuntime().exec(new String[]{"su", "-c", command});
            BufferedReader br = new BufferedReader(new InputStreamReader(p.getInputStream()));
            String line;
            while ((line = br.readLine()) != null && out.length() < maxChars) {
                out.append(line).append('\n');
            }
            p.waitFor();
        } catch (Exception e) {
            out.append("SU ERROR: ")
                    .append(e.getClass().getSimpleName())
                    .append(": ")
                    .append(e.getMessage())
                    .append('\n');
        }
        return out.toString();
    }

    private void finishSuccess(String message, byte[] p2, byte[] pe2) {
        appendLog("SUCCESS: " + message);
        setSummary(message + "\n\n02 = " + hex(p2) + "\nE2 = " + hex(pe2) +
                "\n\nComprova ara amb NFC Tools que sigui Writable: Yes.");
        setStatus("ÈXIT: lock bytes a zero.");
    }

    private static byte[] first4(byte[] in) {
        return in != null && in.length >= 4 ? Arrays.copyOfRange(in, 0, 4) : null;
    }

    private static String hex(byte[] data) {
        if (data == null) return "<no response>";
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < data.length; i++) {
            if (i > 0) sb.append(' ');
            sb.append(String.format(Locale.US, "%02X", data[i] & 0xFF));
        }
        return sb.toString();
    }

    private static String toHexColon(byte[] data) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < data.length; i++) {
            if (i > 0) sb.append(':');
            sb.append(String.format(Locale.US, "%02X", data[i] & 0xFF));
        }
        return sb.toString();
    }

    private static byte[] hexToBytes(String s) {
        String clean = s.replaceAll("[^0-9A-Fa-f]", "");
        if ((clean.length() & 1) != 0) throw new IllegalArgumentException("hex odd length");
        byte[] out = new byte[clean.length() / 2];
        for (int i = 0; i < out.length; i++) {
            out[i] = (byte) Integer.parseInt(clean.substring(i * 2, i * 2 + 2), 16);
        }
        return out;
    }

    private static String trim(String s, int n) {
        return s == null ? "" : (s.length() <= n ? s : s.substring(0, n) + "\n...[truncated]");
    }

    private void appendLog(String s) {
        ui.post(() -> logView.append(s + "\n"));
    }

    private void setStatus(String s) {
        ui.post(() -> status.setText(s));
    }

    private void setSummary(String s) {
        ui.post(() -> summary.setText(s));
    }

    private void setButtons(boolean enabled) {
        ui.post(() -> {
            autoButton.setEnabled(enabled);
            inspectButton.setEnabled(enabled);
        });
    }

    private int dp(int v) {
        return (int) (v * getResources().getDisplayMetrics().density + 0.5f);
    }

    private static class ProbeResult {
        boolean anyMagic;
        boolean uscuid;
    }

    private static class RootReport {
        boolean root;
        boolean pn553;
        boolean pn553Open;
        boolean dtaTool;
        boolean rawSymbols;
        boolean hiddenDtaClass;
        String text = "";

        boolean lowLevelCandidate() {
            return root && pn553 && (dtaTool || rawSymbols || hiddenDtaClass);
        }
    }
}
