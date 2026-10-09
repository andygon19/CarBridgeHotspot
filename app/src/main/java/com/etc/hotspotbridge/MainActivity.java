package com.etc.hotspotbridge;

import android.Manifest;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.provider.Settings;
import android.text.method.ScrollingMovementMethod;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.EditText;
import android.widget.Spinner;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.app.ActivityCompat;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * App orquestadora: levanta el hotspot del equipo en la banda elegida (2.4 / 5 / auto)
 * y luego lanza el receptor (DiPlay / DiAuto u otro paquete) para que la proyeccion
 * use ESE hotspot, evitando el 5 GHz roto del G3i.
 *
 * Para Andy Gonzalez — prueba de concepto en XPeng G3i (XOS, Android 9).
 */
public class MainActivity extends AppCompatActivity {

    // Paquetes vistos en el logcat del propio G3i.
    private static final String[] RECEIVER_PRESETS = {
            "com.shihab.diplay",            // DiPlay (CarPlay)
            "com.andrerinas.headunitrevived" // DiAuto / Headunit (Android Auto)
    };

    private Spinner bandSpinner;
    private EditText ssidInput;
    private EditText passInput;
    private EditText channelInput;
    private EditText receiverInput;
    private TextView logView;
    private TextView credView;
    private android.widget.ImageView qrView;

    private HotspotController controller;
    private final ExecutorService worker = Executors.newSingleThreadExecutor();
    private final SimpleDateFormat ts = new SimpleDateFormat("HH:mm:ss", Locale.US);

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        bandSpinner = findViewById(R.id.bandSpinner);
        ssidInput = findViewById(R.id.ssidInput);
        passInput = findViewById(R.id.passInput);
        channelInput = findViewById(R.id.channelInput);
        receiverInput = findViewById(R.id.receiverInput);
        logView = findViewById(R.id.logView);
        logView.setMovementMethod(new ScrollingMovementMethod());
        credView = findViewById(R.id.credView);
        qrView = findViewById(R.id.qrView);

        ArrayAdapter<String> bandAdapter = new ArrayAdapter<>(
                this, android.R.layout.simple_spinner_dropdown_item,
                new String[]{"2.4 GHz", "5 GHz", "Auto"});
        bandSpinner.setAdapter(bandAdapter);

        // Valores por defecto sensatos para el G3i.
        ssidInput.setText("CARBRIDGE");
        passInput.setText("12345678");
        channelInput.setText("0");
        receiverInput.setText(RECEIVER_PRESETS[0]);

        controller = new HotspotController(getApplicationContext(), this::appendLog);

        Button startHotspotBtn = findViewById(R.id.startHotspotBtn);
        Button launchReceiverBtn = findViewById(R.id.launchReceiverBtn);
        Button startBothBtn = findViewById(R.id.startBothBtn);
        Button stopBtn = findViewById(R.id.stopBtn);
        Button statusBtn = findViewById(R.id.statusBtn);
        Button permsBtn = findViewById(R.id.permsBtn);
        Button presetDiplayBtn = findViewById(R.id.presetDiplayBtn);
        Button presetDiautoBtn = findViewById(R.id.presetDiautoBtn);

        presetDiplayBtn.setOnClickListener(v -> {
            receiverInput.setText(RECEIVER_PRESETS[0]); // com.shihab.diplay
            appendLog("Receptor seleccionado: DiPlay (" + RECEIVER_PRESETS[0] + ")");
        });
        presetDiautoBtn.setOnClickListener(v -> {
            receiverInput.setText(RECEIVER_PRESETS[1]); // com.andrerinas.headunitrevived
            appendLog("Receptor seleccionado: DiAuto (" + RECEIVER_PRESETS[1] + ")");
        });

        // Casilla: usar el hotspot del sistema (SSID fijo).
        android.widget.CheckBox systemHotspotCheck = findViewById(R.id.systemHotspotCheck);
        systemHotspotCheck.setOnCheckedChangeListener((btn, checked) -> {
            controller.setPreferSystemHotspot(checked);
            appendLog(checked
                    ? "Modo SSID FIJO activado: usa el hotspot configurado en Ajustes."
                    : "Modo automático: LocalOnlyHotspot (SSID aleatorio del sistema).");
        });

        // Abrir la pantalla de Ajustes de hotspot del sistema.
        Button openHotspotSettingsBtn = findViewById(R.id.openHotspotSettingsBtn);
        openHotspotSettingsBtn.setOnClickListener(v -> openHotspotSettings());

        // Botones de modo completo.
        Button modeCarplayBtn = findViewById(R.id.modeCarplayBtn);
        Button modeAndroidautoBtn = findViewById(R.id.modeAndroidautoBtn);
        modeCarplayBtn.setOnClickListener(v -> {
            receiverInput.setText(RECEIVER_PRESETS[0]); // DiPlay
            appendLog("== Modo CarPlay (iPhone) ==");
            runStartBoth();
        });
        modeAndroidautoBtn.setOnClickListener(v -> {
            receiverInput.setText(RECEIVER_PRESETS[1]); // DiAuto
            appendLog("== Modo Android Auto ==");
            appendLog("Si es inalámbrico: en el teléfono Android desactiva 'Cambiar entre redes' / 'Aceleración de red' en Ajustes de Wi-Fi.");
            runStartBoth();
        });

        startHotspotBtn.setOnClickListener(v -> runStartHotspot());
        launchReceiverBtn.setOnClickListener(v -> launchReceiver());
        startBothBtn.setOnClickListener(v -> runStartBoth());
        stopBtn.setOnClickListener(v -> worker.execute(() -> controller.stopHotspot()));
        statusBtn.setOnClickListener(v -> worker.execute(() -> controller.reportCurrentApConfig()));
        permsBtn.setOnClickListener(v -> requestPermissionsAndSettings());

        appendLog("Listo. Revisa permisos (boton 'Permisos') antes de la primera prueba.");
        appendLog("Flujo sugerido: Permisos → Iniciar hotspot → Estado → Lanzar receptor.");
    }

    private HotspotController.Band selectedBand() {
        switch (bandSpinner.getSelectedItemPosition()) {
            case 0: return HotspotController.Band.GHZ_2_4;
            case 1: return HotspotController.Band.GHZ_5;
            default: return HotspotController.Band.AUTO;
        }
    }

    private int selectedChannel() {
        try {
            return Integer.parseInt(channelInput.getText().toString().trim());
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    private void runStartHotspot() {
        final String ssid = ssidInput.getText().toString().trim();
        final String pass = passInput.getText().toString();
        final HotspotController.Band band = selectedBand();
        final int channel = selectedChannel();
        worker.execute(() -> {
            boolean ok = controller.startHotspot(ssid, pass, band, channel);
            updateCreds(ok);
        });
    }

    private void updateCreds(boolean ok) {
        final String s = controller.getActiveSsid();
        final String p = controller.getActivePass();
        final String b = controller.getActiveBandLabel();
        runOnUiThread(() -> {
            if (ok && s != null) {
                credView.setText("Red creada ✓\nSSID: " + s + "\nClave: " + p
                        + "\nBanda: " + b + "\n→ Escanea el QR o conéctate a esta red.");
            } else {
                credView.setText("Red creada: (falló, revisa el registro)");
            }
        });
        // Mostrar QR solo cuando hay credenciales reales (modo LocalOnlyHotspot).
        if (ok && s != null && p != null && !s.startsWith("(")) {
            showWifiQr(s, p);
        } else {
            runOnUiThread(() -> qrView.setVisibility(android.view.View.GONE));
        }
    }

    /** Genera y muestra un QR estándar de Wi-Fi para unir el iPhone/Android con un escaneo. */
    private void showWifiQr(String ssid, String pass) {
        try {
            String payload = "WIFI:T:WPA;S:" + wifiEscape(ssid) + ";P:" + wifiEscape(pass) + ";;";
            int size = 520;
            com.google.zxing.common.BitMatrix m = new com.google.zxing.MultiFormatWriter()
                    .encode(payload, com.google.zxing.BarcodeFormat.QR_CODE, size, size);
            android.graphics.Bitmap bmp = android.graphics.Bitmap.createBitmap(
                    size, size, android.graphics.Bitmap.Config.RGB_565);
            for (int x = 0; x < size; x++) {
                for (int y = 0; y < size; y++) {
                    bmp.setPixel(x, y, m.get(x, y)
                            ? android.graphics.Color.BLACK : android.graphics.Color.WHITE);
                }
            }
            runOnUiThread(() -> {
                qrView.setImageBitmap(bmp);
                qrView.setVisibility(android.view.View.VISIBLE);
            });
        } catch (Throwable t) {
            appendLog("No se pudo generar el QR: " + t.getClass().getSimpleName());
        }
    }

    /** Escapa los caracteres especiales del formato WIFI: \ ; , : " */
    private String wifiEscape(String s) {
        if (s == null) return "";
        return s.replace("\\", "\\\\")
                .replace(";", "\\;")
                .replace(",", "\\,")
                .replace(":", "\\:")
                .replace("\"", "\\\"");
    }

    private void runStartBoth() {
        final String ssid = ssidInput.getText().toString().trim();
        final String pass = passInput.getText().toString();
        final HotspotController.Band band = selectedBand();
        final int channel = selectedChannel();
        worker.execute(() -> {
            boolean ok = controller.startHotspot(ssid, pass, band, channel);
            updateCreds(ok);
            controller.reportCurrentApConfig();
            if (ok) {
                try { Thread.sleep(1500); } catch (InterruptedException ignored) { }
                runOnUiThread(this::launchReceiver);
            } else {
                appendLog("No se lanzara el receptor porque el hotspot no arranco.");
            }
        });
    }

    private void launchReceiver() {
        String pkg = receiverInput.getText().toString().trim();
        if (pkg.isEmpty()) {
            toast("Indica el paquete del receptor.");
            return;
        }
        PackageManager pm = getPackageManager();
        Intent launch = pm.getLaunchIntentForPackage(pkg);
        if (launch == null) {
            appendLog("No se encontro el paquete '" + pkg + "'. ¿Esta instalado?");
            toast("Paquete no instalado: " + pkg);
            return;
        }
        launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        startActivity(launch);
        appendLog("Lanzado receptor: " + pkg);
    }

    private void openHotspotSettings() {
        // Intenta abrir la pantalla de tethering/hotspot del sistema.
        Intent[] tries = new Intent[] {
                new Intent().setClassName("com.android.settings",
                        "com.android.settings.TetherSettings"),
                new Intent().setClassName("com.android.settings",
                        "com.android.settings.Settings$TetherSettingsActivity"),
                new Intent(Settings.ACTION_WIRELESS_SETTINGS)
        };
        for (Intent i : tries) {
            try {
                i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                startActivity(i);
                appendLog("Abriendo Ajustes de hotspot… define SSID/clave y enciéndelo.");
                return;
            } catch (Exception ignored) { }
        }
        appendLog("No se pudo abrir Ajustes de hotspot automáticamente. Ábrelo manual: Ajustes → Conexiones/Red → Hotspot.");
    }

    private void requestPermissionsAndSettings() {
        // WRITE_SETTINGS se concede en una pantalla especial del sistema.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && !Settings.System.canWrite(this)) {
            appendLog("Abriendo pantalla para conceder WRITE_SETTINGS…");
            try {
                Intent i = new Intent(Settings.ACTION_MANAGE_WRITE_SETTINGS,
                        Uri.parse("package:" + getPackageName()));
                startActivity(i);
            } catch (Exception e) {
                appendLog("No se pudo abrir ACTION_MANAGE_WRITE_SETTINGS: " + e.getMessage());
            }
        } else {
            appendLog("WRITE_SETTINGS ya concedido.");
        }
        // Ubicacion: necesaria en Android 9 para operaciones Wi-Fi.
        ActivityCompat.requestPermissions(this, new String[]{
                Manifest.permission.ACCESS_FINE_LOCATION,
                Manifest.permission.CHANGE_WIFI_STATE,
                Manifest.permission.ACCESS_WIFI_STATE
        }, 1001);
        appendLog("Si una estrategia sigue fallando, concede WRITE_SECURE_SETTINGS por ADB:");
        appendLog("  adb shell pm grant " + getPackageName() + " android.permission.WRITE_SECURE_SETTINGS");
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, @NonNull String[] permissions,
                                           @NonNull int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        for (int i = 0; i < permissions.length; i++) {
            boolean granted = grantResults.length > i
                    && grantResults[i] == PackageManager.PERMISSION_GRANTED;
            appendLog("Permiso " + permissions[i] + " → " + (granted ? "concedido" : "denegado"));
        }
    }

    private void appendLog(String line) {
        String stamped = "[" + ts.format(new Date()) + "] " + line + "\n";
        runOnUiThread(() -> {
            logView.append(stamped);
            // auto-scroll al final
            final int scrollAmount = logView.getLayout() == null ? 0
                    : logView.getLayout().getLineTop(logView.getLineCount()) - logView.getHeight();
            logView.scrollTo(0, Math.max(scrollAmount, 0));
        });
    }

    private void toast(String msg) {
        Toast.makeText(this, msg, Toast.LENGTH_SHORT).show();
    }

    @Override
    protected void onDestroy() {
        worker.shutdownNow();
        super.onDestroy();
    }
}
