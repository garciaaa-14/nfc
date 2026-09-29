package com.marti.nfcunlocklab;

import android.app.Activity;
import android.app.AlertDialog;
import android.nfc.NfcAdapter;
import android.nfc.Tag;
import android.nfc.tech.NfcA;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.InputType;
import android.view.Gravity;
import android.view.View;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import java.io.IOException;
import java.util.Arrays;
import java.util.Locale;

public class MainActivity extends Activity implements NfcAdapter.ReaderCallback {

    private static final byte[] EXPECTED_UID = hexToBytes("04B0B1BB4A5980");
    private static final int PAGE_STATIC_LOCK = 0x02;
    private static final int PAGE_DYNAMIC_LOCK = 0xE2;

    private NfcAdapter nfcAdapter;
    private final Handler ui = new Handler(Looper.getMainLooper());
    private TextView status;
    private TextView tagInfo;
    private TextView logView;
    private EditText rawInput;
    private Button restoreButton;
    private Button rawButton;
    private CheckBox uidGuard;
    private volatile Tag lastTag;
    private volatile Snapshot lastSnapshot;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        nfcAdapter = NfcAdapter.getDefaultAdapter(this);
        setContentView(buildUi());

        if (nfcAdapter == null) {
            status.setText("Aquest mòbil no té NFC.");
        } else if (!nfcAdapter.isEnabled()) {
            status.setText("Activa l'NFC del mòbil i torna a l'app.");
        } else {
            status.setText("Apropa la targeta NFC al mòbil.");
        }
    }

    private View buildUi() {
        int pad = dp(18);
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(pad, pad, pad, pad);

        TextView title = new TextView(this);
        title.setText("NFC Unlock Lab");
        title.setTextSize(28);
        title.setGravity(Gravity.START);
        title.setPadding(0, 0, 0, dp(8));
        root.addView(title);

        TextView subtitle = new TextView(this);
        subtitle.setText("Diagnòstic i intent controlat per la teva NTAG216 clonada. No modifica res automàticament.");
        subtitle.setTextSize(15);
        subtitle.setPadding(0, 0, 0, dp(14));
        root.addView(subtitle);

        status = new TextView(this);
        status.setTextSize(18);
        status.setPadding(dp(12), dp(12), dp(12), dp(12));
        root.addView(status);

        tagInfo = new TextView(this);
        tagInfo.setText("Encara no s'ha llegit cap targeta.");
        tagInfo.setTextIsSelectable(true);
        tagInfo.setPadding(0, dp(12), 0, dp(12));
        root.addView(tagInfo);

        uidGuard = new CheckBox(this);
        uidGuard.setChecked(true);
        uidGuard.setText("Només permetre escriptura al UID 04:B0:B1:BB:4A:59:80");
        root.addView(uidGuard);

        restoreButton = new Button(this);
        restoreButton.setText("INTENTAR RESTAURAR LOCK BYTES");
        restoreButton.setEnabled(false);
        restoreButton.setOnClickListener(v -> confirmRestore());
        root.addView(restoreButton);

        TextView rawLabel = new TextView(this);
        rawLabel.setText("Comanda NFC-A raw (hex):");
        rawLabel.setPadding(0, dp(18), 0, dp(4));
        root.addView(rawLabel);

        rawInput = new EditText(this);
        rawInput.setHint("Exemple: 60   o   30 E2");
        rawInput.setInputType(InputType.TYPE_CLASS_TEXT);
        rawInput.setSingleLine(true);
        root.addView(rawInput);

        rawButton = new Button(this);
        rawButton.setText("ENVIAR COMANDA RAW");
        rawButton.setEnabled(false);
        rawButton.setOnClickListener(v -> sendRawFromUi());
        root.addView(rawButton);

        Button clear = new Button(this);
        clear.setText("NETEJAR LOG");
        clear.setOnClickListener(v -> logView.setText(""));
        root.addView(clear);

        TextView logTitle = new TextView(this);
        logTitle.setText("Log");
        logTitle.setTextSize(18);
        logTitle.setPadding(0, dp(18), 0, dp(6));
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
        lastTag = tag;
        new Thread(() -> inspectTag(tag)).start();
    }

    private void inspectTag(Tag tag) {
        String uid = toHexColon(tag.getId());
        appendLog("\n=== TAG DETECTADA ===");
        appendLog("UID: " + uid);

        NfcA nfca = NfcA.get(tag);
        if (nfca == null) {
            setStatus("La targeta no exposa NfcA.");
            return;
        }

        try {
            nfca.connect();
            nfca.setTimeout(1000);

            byte[] version = tx(nfca, new byte[]{0x60}, "GET_VERSION (60)");
            byte[] p2Read = tx(nfca, new byte[]{0x30, 0x02}, "READ page 02 (30 02)");
            byte[] pE2Read = tx(nfca, new byte[]{0x30, (byte) 0xE2}, "READ page E2 (30 E2)");

            byte[] p2 = first4(p2Read);
            byte[] pE2 = first4(pE2Read);
            Snapshot snap = new Snapshot(tag.getId(), version, p2, pE2);
            lastSnapshot = snap;

            boolean correctUid = Arrays.equals(tag.getId(), EXPECTED_UID);
            boolean staticLocked = p2 != null && (p2[2] != 0 || p2[3] != 0);
            boolean dynamicLocked = pE2 != null && (pE2[0] != 0 || pE2[1] != 0 || pE2[2] != 0);

            String info = "UID: " + uid +
                    "\nUID esperat: " + (correctUid ? "SÍ" : "NO") +
                    "\nGET_VERSION: " + hex(version) +
                    "\nPàgina 02: " + hex(p2) +
                    "\nPàgina E2: " + hex(pE2) +
                    "\nStatic locks actius: " + (staticLocked ? "SÍ" : "NO") +
                    "\nDynamic locks actius: " + (dynamicLocked ? "SÍ" : "NO");

            ui.post(() -> {
                tagInfo.setText(info);
                boolean canEnable = p2 != null && pE2 != null && (!uidGuard.isChecked() || correctUid);
                restoreButton.setEnabled(canEnable);
                rawButton.setEnabled(true);
                status.setText("Lectura completada. Mantén la targeta a prop per fer més proves.");
            });

        } catch (Exception e) {
            appendLog("ERROR: " + e.getClass().getSimpleName() + ": " + e.getMessage());
            setStatus("No s'ha pogut completar la lectura. Torna a apropar la targeta.");
        } finally {
            try { nfca.close(); } catch (Exception ignored) {}
        }
    }

    private void confirmRestore() {
        Snapshot snap = lastSnapshot;
        if (snap == null) return;

        if (uidGuard.isChecked() && !Arrays.equals(snap.uid, EXPECTED_UID)) {
            new AlertDialog.Builder(this)
                    .setTitle("UID diferent")
                    .setMessage("Per seguretat, aquesta app només escriu a la targeta concreta que m'has passat.")
                    .setPositiveButton("OK", null)
                    .show();
            return;
        }

        if (snap.page2 == null || snap.pageE2 == null) return;

        String msg = "L'app intentarà escriure només els bytes de lock, mantenint la resta de bytes tal com s'han llegit.\n\n" +
                "02 actual: " + hex(snap.page2) + "\n" +
                "02 intent: " + hex(new byte[]{snap.page2[0], snap.page2[1], 0x00, 0x00}) + "\n\n" +
                "E2 actual: " + hex(snap.pageE2) + "\n" +
                "E2 intent: " + hex(new byte[]{0x00, 0x00, 0x00, snap.pageE2[3]}) + "\n\n" +
                "En una NTAG original això serà rebutjat. En un clon pot funcionar o no.";

        new AlertDialog.Builder(this)
                .setTitle("Intentar restaurar locks?")
                .setMessage(msg)
                .setNegativeButton("Cancel·lar", null)
                .setPositiveButton("Intentar", (d, w) -> {
                    Tag t = lastTag;
                    boolean enforceUid = uidGuard.isChecked();
                    if (t != null) new Thread(() -> attemptRestore(t, enforceUid)).start();
                })
                .show();
    }

    private void attemptRestore(Tag tag, boolean enforceUid) {
        Snapshot snap = lastSnapshot;
        if (snap == null) return;
        if (enforceUid && !Arrays.equals(tag.getId(), EXPECTED_UID)) {
            appendLog("Escriptura cancel·lada: UID no coincideix.");
            return;
        }

        NfcA nfca = NfcA.get(tag);
        if (nfca == null) return;
        try {
            nfca.connect();
            nfca.setTimeout(1200);
            setStatus("Intentant restaurar els lock bytes...");

            byte[] p2Now = first4(tx(nfca, new byte[]{0x30, 0x02}, "Pre-read 02"));
            byte[] pE2Now = first4(tx(nfca, new byte[]{0x30, (byte)0xE2}, "Pre-read E2"));
            if (p2Now == null || pE2Now == null) throw new IOException("No es poden llegir les pàgines abans d'escriure.");

            // Conservative guard: preserve non-lock bytes exactly as currently read.
            byte[] write02 = new byte[]{(byte)0xA2, 0x02, p2Now[0], p2Now[1], 0x00, 0x00};
            byte[] writeE2 = new byte[]{(byte)0xA2, (byte)0xE2, 0x00, 0x00, 0x00, pE2Now[3]};

            byte[] r02 = tx(nfca, write02, "WRITE 02 -> locks 00 00");
            appendLog("WRITE 02 ACK/NAK: " + hex(r02));

            byte[] rE2 = tx(nfca, writeE2, "WRITE E2 -> locks 00 00 00");
            appendLog("WRITE E2 ACK/NAK: " + hex(rE2));

            byte[] after02 = first4(tx(nfca, new byte[]{0x30, 0x02}, "Verify 02"));
            byte[] afterE2 = first4(tx(nfca, new byte[]{0x30, (byte)0xE2}, "Verify E2"));

            boolean staticCleared = after02 != null && after02[2] == 0 && after02[3] == 0;
            boolean dynamicCleared = afterE2 != null && afterE2[0] == 0 && afterE2[1] == 0 && afterE2[2] == 0;

            appendLog("RESULTAT 02: " + hex(after02));
            appendLog("RESULTAT E2: " + hex(afterE2));

            if (staticCleared && dynamicCleared) {
                setStatus("ÈXIT: els lock bytes han quedat a zero. Torna a llegir-la amb NFC Tools.");
            } else {
                setStatus("El xip ha mantingut algun lock. Aquest clon no accepta l'escriptura estàndard; mira el log.");
            }
        } catch (Exception e) {
            appendLog("ERROR RESTORE: " + e.getClass().getSimpleName() + ": " + e.getMessage());
            setStatus("Intent fallit. El xip probablement rebutja l'escriptura estàndard dels locks.");
        } finally {
            try { nfca.close(); } catch (Exception ignored) {}
        }
    }

    private void sendRawFromUi() {
        Tag tag = lastTag;
        if (tag == null) return;
        String input = rawInput.getText().toString();
        byte[] cmd;
        try {
            cmd = hexToBytes(input);
            if (cmd.length == 0) throw new IllegalArgumentException("comanda buida");
        } catch (Exception e) {
            new AlertDialog.Builder(this).setTitle("Hex incorrecte")
                    .setMessage("Escriu bytes hex, per exemple: 60 o 30 E2")
                    .setPositiveButton("OK", null).show();
            return;
        }
        new Thread(() -> {
            NfcA nfca = NfcA.get(tag);
            if (nfca == null) return;
            try {
                nfca.connect();
                nfca.setTimeout(1200);
                byte[] resp = tx(nfca, cmd, "RAW " + hex(cmd));
                setStatus("Resposta raw: " + hex(resp));
            } catch (Exception e) {
                appendLog("RAW ERROR: " + e.getMessage());
                setStatus("La comanda raw ha fallat.");
            } finally {
                try { nfca.close(); } catch (Exception ignored) {}
            }
        }).start();
    }

    private byte[] tx(NfcA nfca, byte[] command, String label) throws IOException {
        appendLog(label + "  TX: " + hex(command));
        byte[] response = nfca.transceive(command);
        appendLog(label + "  RX: " + hex(response));
        return response;
    }

    private void appendLog(String s) {
        ui.post(() -> logView.append(s + "\n"));
    }

    private void setStatus(String s) {
        ui.post(() -> status.setText(s));
    }

    private static byte[] first4(byte[] in) {
        if (in == null || in.length < 4) return null;
        return Arrays.copyOfRange(in, 0, 4);
    }

    private static String hex(byte[] data) {
        if (data == null) return "<null>";
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

    private static class Snapshot {
        final byte[] uid;
        final byte[] version;
        final byte[] page2;
        final byte[] pageE2;

        Snapshot(byte[] uid, byte[] version, byte[] page2, byte[] pageE2) {
            this.uid = uid == null ? null : uid.clone();
            this.version = version == null ? null : version.clone();
            this.page2 = page2 == null ? null : page2.clone();
            this.pageE2 = pageE2 == null ? null : pageE2.clone();
        }
    }
}
