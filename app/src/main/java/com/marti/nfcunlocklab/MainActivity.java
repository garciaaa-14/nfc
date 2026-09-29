package com.marti.nfcunlocklab;

import android.app.Activity;
import android.app.AlertDialog;
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
    private Button analyseButton;
    private volatile boolean autoArmed = false;
    private volatile boolean analyseArmed = false;
    private volatile boolean busy = false;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        nfcAdapter = NfcAdapter.getDefaultAdapter(this);
        setContentView(buildUi());
        showRootInfo();

        if (nfcAdapter == null) {
            status.setText("Aquest mòbil no té NFC.");
        } else if (!nfcAdapter.isEnabled()) {
            status.setText("Activa l'NFC del mòbil.");
        } else {
            status.setText("Prem AUTO UNLOCK i després apropa la targeta.");
        }
    }

    private View buildUi() {
        int pad = dp(18);
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(pad, pad, pad, pad);

        TextView title = new TextView(this);
        title.setText("NFC Unlock Lab v2");
        title.setTextSize(28);
        title.setGravity(Gravity.START);
        title.setPadding(0, 0, 0, dp(6));
        root.addView(title);

        TextView subtitle = new TextView(this);
        subtitle.setText("Auto Unlock per la teva NTAG216 clonada. Executa totes les rutes compatibles amb Android en una sola detecció.");
        subtitle.setTextSize(15);
        subtitle.setPadding(0, 0, 0, dp(14));
        root.addView(subtitle);

        status = new TextView(this);
        status.setTextSize(18);
        status.setPadding(dp(12), dp(12), dp(12), dp(12));
        root.addView(status);

        autoButton = new Button(this);
        autoButton.setText("AUTO UNLOCK — APROPA LA TARGETA");
        autoButton.setOnClickListener(v -> armAutoUnlock());
        root.addView(autoButton);

        analyseButton = new Button(this);
        analyseButton.setText("NOMÉS ANALITZAR");
        analyseButton.setOnClickListener(v -> armAnalyse());
        root.addView(analyseButton);

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

    private void armAutoUnlock() {
        if (busy) return;
        autoArmed = true;
        analyseArmed = false;
        logView.setText("");
        summary.setText("");
        status.setText("AUTO UNLOCK armat. Apropa la targeta i no la moguis.");
    }

    private void armAnalyse() {
        if (busy) return;
        autoArmed = false;
        analyseArmed = true;
        logView.setText("");
        summary.setText("");
        status.setText("Anàlisi armada. Apropa la targeta i no la moguis.");
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
        if (busy) return;
        if (!autoArmed && !analyseArmed) {
            setStatus("Targeta detectada. Prem AUTO UNLOCK per començar.");
            return;
        }

        boolean doUnlock = autoArmed;
        autoArmed = false;
        analyseArmed = false;
        busy = true;
        setButtons(false);

        new Thread(() -> {
            try {
                runFullFlow(tag, doUnlock);
            } finally {
                busy = false;
                setButtons(true);
            }
        }).start();
    }

    private void runFullFlow(Tag tag, boolean doUnlock) {
        appendLog("=== TARGETA DETECTADA ===");
        appendLog("UID: " + toHexColon(tag.getId()));

        if (!Arrays.equals(tag.getId(), EXPECTED_UID)) {
            appendLog("ATURAT: UID diferent de la targeta autoritzada.");
            setStatus("UID diferent. No s'ha escrit res.");
            return;
        }

        setStatus(doUnlock ? "AUTO UNLOCK en curs. No moguis la targeta." : "Analitzant. No moguis la targeta.");

        byte[] version = freshTx(tag, new byte[]{0x60}, "GET_VERSION");
        byte[] p2 = first4(freshTx(tag, new byte[]{0x30, 0x02}, "READ 02"));
        byte[] pE2 = first4(freshTx(tag, new byte[]{0x30, (byte)0xE2}, "READ E2"));

        appendLog("GET_VERSION: " + hex(version));
        appendLog("PAGE 02: " + hex(p2));
        appendLog("PAGE E2: " + hex(pE2));

        if (locksClear(p2, pE2)) {
            finishSuccess("La targeta ja està desbloquejada.", p2, pE2);
            return;
        }

        byte[] f0 = freshTx(tag, new byte[]{0x30, (byte)0xF0}, "MAGIC PROBE 30 F0");
        byte[] fa = freshTx(tag, new byte[]{0x30, (byte)0xFA}, "MAGIC PROBE 30 FA");
        byte[] fc = freshTx(tag, new byte[]{0x30, (byte)0xFC}, "MAGIC PROBE 30 FC");
        byte[] e050 = freshTx(tag, new byte[]{(byte)0xE0, 0x50}, "USCUID PROBE E0 50");
        byte[] e080 = freshTx(tag, new byte[]{(byte)0xE0, (byte)0x80}, "RATS PROBE E0 80");

        boolean magicNtag = f0 != null || fa != null || fc != null;
        boolean uscuid = looksLikeUscuid(e050) || looksLikeUscuid(e080);

        appendLog("Magic NTAG pages: " + (magicNtag ? "DETECTADES" : "no detectades"));
        appendLog("USCUID config: " + (uscuid ? "DETECTADA" : "no detectada"));
        if (e050 != null) appendLog("E0 50 RX: " + hex(e050));
        if (e080 != null) appendLog("E0 80 RX: " + hex(e080));

        if (!doUnlock) {
            showAnalysis(version, p2, pE2, magicNtag, uscuid, e050, e080);
            setStatus("Anàlisi completada.");
            return;
        }

        appendLog("\n=== RUTA 1: ESCRIPTURA NTAG ESTÀNDARD ===");
        attemptStandard(tag, false);
        p2 = readPage(tag, PAGE_STATIC_LOCK, "VERIFY RUTA 1 / 02");
        pE2 = readPage(tag, PAGE_DYNAMIC_LOCK, "VERIFY RUTA 1 / E2");
        if (locksClear(p2, pE2)) {
            finishSuccess("ÈXIT amb escriptura NTAG estàndard.", p2, pE2);
            return;
        }

        appendLog("\n=== RUTA 2: PWD_AUTH FFFFFFFF + ESCRIPTURA ===");
        byte[] auth = freshTx(tag,
                new byte[]{0x1B, (byte)0xFF, (byte)0xFF, (byte)0xFF, (byte)0xFF},
                "PWD_AUTH FF FF FF FF");
        appendLog("PWD_AUTH resposta: " + hex(auth));
        attemptStandard(tag, true);
        p2 = readPage(tag, PAGE_STATIC_LOCK, "VERIFY RUTA 2 / 02");
        pE2 = readPage(tag, PAGE_DYNAMIC_LOCK, "VERIFY RUTA 2 / E2");
        if (locksClear(p2, pE2)) {
            finishSuccess("ÈXIT després de PWD_AUTH.", p2, pE2);
            return;
        }

        appendLog("\n=== RUTA 3: MAGIC WAKEUP EN BYTE COMPLET ===");
        boolean wakeA = tryByteMagicSequence(tag, (byte)0x40, (byte)0x43, "40/43");
        if (wakeA) {
            p2 = readPage(tag, PAGE_STATIC_LOCK, "VERIFY MAGIC A / 02");
            pE2 = readPage(tag, PAGE_DYNAMIC_LOCK, "VERIFY MAGIC A / E2");
            if (locksClear(p2, pE2)) {
                finishSuccess("ÈXIT amb Magic 40/43.", p2, pE2);
                return;
            }
        }

        boolean wakeB = tryByteMagicSequence(tag, (byte)0x20, (byte)0x23, "20/23");
        if (wakeB) {
            p2 = readPage(tag, PAGE_STATIC_LOCK, "VERIFY MAGIC B / 02");
            pE2 = readPage(tag, PAGE_DYNAMIC_LOCK, "VERIFY MAGIC B / E2");
            if (locksClear(p2, pE2)) {
                finishSuccess("ÈXIT amb Magic 20/23.", p2, pE2);
                return;
            }
        }

        appendLog("\n=== RESULTAT FINAL ===");
        p2 = readPage(tag, PAGE_STATIC_LOCK, "FINAL 02");
        pE2 = readPage(tag, PAGE_DYNAMIC_LOCK, "FINAL E2");

        String root = rootProbe();
        appendLog(root);

        String reason;
        if (uscuid) {
            reason = "S'ha detectat una variant USCUID/Magic, però la backdoor restant necessita un wakeup de 7 bits. L'API NFC estàndard d'Android només envia bytes complets.";
        } else if (magicNtag) {
            reason = "S'han detectat pàgines Magic, però cap ruta compatible amb l'API Android ha pogut baixar els lock bits.";
        } else {
            reason = "No s'ha detectat una backdoor Magic accessible amb les comandes que el mòbil pot enviar des de NfcA.";
        }

        String rootExtra = root.contains("ROOT=YES") && root.contains("PN553=YES")
                ? "\n\nEl mòbil té root i /dev/pn553; això obre una possible ruta de controlador NXP de baix nivell, però no s'ha executat cap comanda directa al controlador perquè és específica del firmware i una trama errònia pot deixar l'NFC inestable."
                : "";

        String finalReason = reason + rootExtra +
                "\n\nLocks finals:\n02 = " + hex(p2) + "\nE2 = " + hex(pE2);
        setSummary(finalReason);
        setStatus("Auto Unlock completat: els locks continuen actius.");
    }

    private void attemptStandard(Tag tag, boolean afterAuth) {
        NfcA nfca = NfcA.get(tag);
        if (nfca == null) return;
        try {
            nfca.connect();
            nfca.setTimeout(1200);

            if (afterAuth) {
                try {
                    byte[] a = nfca.transceive(new byte[]{0x1B, (byte)0xFF, (byte)0xFF, (byte)0xFF, (byte)0xFF});
                    appendLog("AUTH(session) RX: " + hex(a));
                } catch (Exception e) {
                    appendLog("AUTH(session) error: " + e.getMessage());
                }
            }

            byte[] r2 = nfca.transceive(new byte[]{0x30, 0x02});
            byte[] rE2 = nfca.transceive(new byte[]{0x30, (byte)0xE2});
            byte[] p2 = first4(r2);
            byte[] pE2 = first4(rE2);
            if (p2 == null || pE2 == null) return;

            byte[] w02 = new byte[]{(byte)0xA2, 0x02, p2[0], p2[1], 0x00, 0x00};
            byte[] wE2 = new byte[]{(byte)0xA2, (byte)0xE2, 0x00, 0x00, 0x00, pE2[3]};

            appendLog("WRITE 02 TX: " + hex(w02));
            appendLog("WRITE 02 RX: " + hex(nfca.transceive(w02)));
            appendLog("WRITE E2 TX: " + hex(wE2));
            appendLog("WRITE E2 RX: " + hex(nfca.transceive(wE2)));
        } catch (Exception e) {
            appendLog("Standard write error: " + e.getClass().getSimpleName() + ": " + e.getMessage());
        } finally {
            try { nfca.close(); } catch (Exception ignored) {}
        }
    }

    private boolean tryByteMagicSequence(Tag tag, byte first, byte second, String name) {
        NfcA nfca = NfcA.get(tag);
        if (nfca == null) return false;
        boolean any = false;
        try {
            nfca.connect();
            nfca.setTimeout(700);

            try {
                appendLog("MAGIC " + name + " step1 TX: " + hex(new byte[]{first}));
                byte[] r = nfca.transceive(new byte[]{first});
                appendLog("MAGIC " + name + " step1 RX: " + hex(r));
                any = true;
            } catch (Exception e) {
                appendLog("MAGIC " + name + " step1: " + e.getMessage());
            }

            try {
                appendLog("MAGIC " + name + " step2 TX: " + hex(new byte[]{second}));
                byte[] r = nfca.transceive(new byte[]{second});
                appendLog("MAGIC " + name + " step2 RX: " + hex(r));
                any = true;
            } catch (Exception e) {
                appendLog("MAGIC " + name + " step2: " + e.getMessage());
            }

            if (any) {
                try {
                    byte[] r2 = nfca.transceive(new byte[]{0x30, 0x02});
                    byte[] rE2 = nfca.transceive(new byte[]{0x30, (byte)0xE2});
                    byte[] p2 = first4(r2);
                    byte[] pE2 = first4(rE2);
                    if (p2 != null && pE2 != null) {
                        byte[] w02 = new byte[]{(byte)0xA2, 0x02, p2[0], p2[1], 0x00, 0x00};
                        byte[] wE2 = new byte[]{(byte)0xA2, (byte)0xE2, 0x00, 0x00, 0x00, pE2[3]};
                        appendLog("MAGIC " + name + " WRITE02 RX: " + hex(nfca.transceive(w02)));
                        appendLog("MAGIC " + name + " WRITEE2 RX: " + hex(nfca.transceive(wE2)));
                    }
                } catch (Exception e) {
                    appendLog("MAGIC " + name + " write: " + e.getMessage());
                }
            }
        } catch (Exception e) {
            appendLog("MAGIC " + name + " session: " + e.getMessage());
        } finally {
            try { nfca.close(); } catch (Exception ignored) {}
        }
        return any;
    }

    private byte[] freshTx(Tag tag, byte[] command, String label) {
        NfcA nfca = NfcA.get(tag);
        if (nfca == null) return null;
        try {
            nfca.connect();
            nfca.setTimeout(1000);
            appendLog(label + " TX: " + hex(command));
            byte[] r = nfca.transceive(command);
            appendLog(label + " RX: " + hex(r));
            return r;
        } catch (Exception e) {
            appendLog(label + " ERROR: " + e.getClass().getSimpleName() + ": " + e.getMessage());
            return null;
        } finally {
            try { nfca.close(); } catch (Exception ignored) {}
        }
    }

    private byte[] readPage(Tag tag, int page, String label) {
        return first4(freshTx(tag, new byte[]{0x30, (byte)page}, label));
    }

    private boolean locksClear(byte[] p2, byte[] pE2) {
        return p2 != null && pE2 != null &&
                p2[2] == 0 && p2[3] == 0 &&
                pE2[0] == 0 && pE2[1] == 0 && pE2[2] == 0;
    }

    private boolean looksLikeUscuid(byte[] r) {
        if (r == null || r.length < 16) return false;
        return (r[0] == (byte)0x85) || (r[0] == 0x7A && r[1] == (byte)0xFF);
    }

    private void showAnalysis(byte[] version, byte[] p2, byte[] pE2,
                              boolean magicNtag, boolean uscuid,
                              byte[] e050, byte[] e080) {
        String s = "UID correcte\n" +
                "GET_VERSION: " + hex(version) + "\n" +
                "02: " + hex(p2) + "\n" +
                "E2: " + hex(pE2) + "\n" +
                "Magic NTAG pages: " + (magicNtag ? "sí" : "no") + "\n" +
                "USCUID: " + (uscuid ? "sí" : "no") + "\n" +
                "E0 50: " + hex(e050) + "\n" +
                "E0 80: " + hex(e080) + "\n" +
                rootProbe();
        setSummary(s);
    }

    private void finishSuccess(String message, byte[] p2, byte[] pE2) {
        String s = message + "\n\n02 = " + hex(p2) + "\nE2 = " + hex(pE2) +
                "\n\nTorna a obrir NFC Tools i comprova que ara aparegui Writable: Yes.";
        appendLog("SUCCESS: " + message);
        setSummary(s);
        setStatus("ÈXIT: lock bytes a zero.");
    }

    private void showRootInfo() {
        new Thread(() -> appendLog(rootProbe())).start();
    }

    private String rootProbe() {
        String root = "NO";
        String pn = "NO";
        try {
            Process p = Runtime.getRuntime().exec(new String[]{"su", "-c",
                    "id -u; if [ -e /dev/pn553 ]; then echo PN553_YES; else echo PN553_NO; fi"});
            BufferedReader br = new BufferedReader(new InputStreamReader(p.getInputStream()));
            String line;
            StringBuilder out = new StringBuilder();
            while ((line = br.readLine()) != null) out.append(line).append('\n');
            p.waitFor();
            String txt = out.toString();
            if (txt.contains("0")) root = "YES";
            if (txt.contains("PN553_YES")) pn = "YES";
        } catch (Exception ignored) {}
        return "ROOT=" + root + "  PN553=" + pn;
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
            analyseButton.setEnabled(enabled);
        });
    }

    private static byte[] first4(byte[] in) {
        if (in == null || in.length < 4) return null;
        return Arrays.copyOfRange(in, 0, 4);
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

    private int dp(int v) {
        return (int) (v * getResources().getDisplayMetrics().density + 0.5f);
    }
}
