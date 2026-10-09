package com.etc.hotspotbridge;

import android.content.Context;
import android.net.ConnectivityManager;
import android.net.wifi.WifiConfiguration;
import android.net.wifi.WifiManager;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;

import java.lang.reflect.Field;
import java.lang.reflect.Method;

/**
 * HotspotController
 *
 * Orquesta el arranque del SoftAP (hotspot local) del equipo forzando una banda concreta
 * (2.4 GHz / 5 GHz / auto) y registra qué estrategia funciona realmente en este hardware.
 *
 * Objetivo: evitar el fallo FAILED_NO_CHANNEL del G3i, que ocurre cuando la banda queda en
 * 5 GHz y el driver no encuentra un canal 5 GHz valido. Forzando 2.4 GHz el hotspot deberia
 * levantar en los canales 1-13 que el G3i si tiene disponibles.
 *
 * IMPORTANTE (honestidad tecnica): en Android 9 una app SIN privilegios de sistema puede no
 * tener permiso para controlar el SoftAP. Por eso este controlador intenta VARIAS rutas en
 * orden y deja constancia en el log de cual acepta el XOS del vehiculo. Ninguna de ellas
 * rompe la seguridad del sistema; usan APIs ocultas por reflexion que algunos ROMs de
 * fabricante permiten con el permiso WRITE_SETTINGS.
 */
public class HotspotController {

    public interface LogSink {
        void log(String line);
    }

    /** Bandas que exponemos como parametro. Los valores coinciden con WifiConfiguration. */
    public enum Band {
        AUTO(-1, "Auto"),
        GHZ_2_4(0, "2.4 GHz"),
        GHZ_5(1, "5 GHz");

        public final int apBandValue; // AP_BAND_ANY=-1, AP_BAND_2GHZ=0, AP_BAND_5GHZ=1
        public final String label;

        Band(int apBandValue, String label) {
            this.apBandValue = apBandValue;
            this.label = label;
        }
    }

    private final Context appContext;
    private final WifiManager wifiManager;
    private final LogSink logSink;
    private final Handler main = new Handler(Looper.getMainLooper());

    private WifiManager.LocalOnlyHotspotReservation localOnlyReservation;

    // Credenciales reales de la red creada (LocalOnlyHotspot las genera el sistema).
    private volatile String activeSsid;
    private volatile String activePass;
    private volatile String activeBandLabel;

    // Si true, no se crea LocalOnlyHotspot (SSID aleatorio); se usa el hotspot
    // del sistema (tethering), cuyo SSID/clave configura el usuario en Ajustes y es FIJO.
    private volatile boolean preferSystemHotspot = false;
    public void setPreferSystemHotspot(boolean v) { preferSystemHotspot = v; }

    public boolean isHotspotActive() {
        return localOnlyReservation != null;
    }

    public String getActiveSsid() { return activeSsid; }
    public String getActivePass() { return activePass; }
    public String getActiveBandLabel() { return activeBandLabel; }

    public HotspotController(Context context, LogSink logSink) {
        this.appContext = context.getApplicationContext();
        this.wifiManager = (WifiManager) appContext.getSystemService(Context.WIFI_SERVICE);
        this.logSink = logSink;
    }

    private void log(String line) {
        main.post(() -> logSink.log(line));
    }

    /**
     * Intenta arrancar el hotspot en la banda pedida, probando estrategias en orden.
     * Devuelve true si alguna reporto exito.
     */
    public boolean startHotspot(String ssid, String passphrase, Band band, int channel) {
        log("── Iniciando hotspot ──");
        log("SSID=" + ssid + "  banda=" + band.label + "  canal=" + (channel == 0 ? "auto" : channel));

        if (wifiManager == null) {
            log("ERROR: WifiManager no disponible.");
            return false;
        }

        // Si ya hay un LocalOnlyHotspot activo, reutilizarlo en vez de fallar
        // con "Caller already has an active LocalOnlyHotspot request".
        if (localOnlyReservation != null) {
            log("Ya hay un hotspot activo; se reutiliza.");
            log("   Red: SSID=" + activeSsid + "  clave=" + activePass +
                    "  banda=" + activeBandLabel);
            return true;
        }

        // MODO SSID FIJO: usar el hotspot del sistema (configurado en Ajustes).
        if (preferSystemHotspot) {
            log("Modo hotspot del sistema (SSID/clave FIJOS, los de Ajustes).");
            if (apInterfacePresent()) {
                log("AP del sistema ya activo. Se usa esa red.");
                setSystemCredsLabels();
                return true;
            }
            if (startSystemTethering()) {
                log("Hotspot del sistema iniciado. SSID/clave = los de Ajustes → Hotspot.");
                setSystemCredsLabels();
                return true;
            }
            log("No se pudo encender el hotspot del sistema.");
            log("→ Pulsa 'Ajustes de hotspot', define SSID/clave y enciéndelo; o desmarca la casilla.");
            return false;
        }

        // Para levantar SoftAP normalmente hay que apagar primero el Wi-Fi cliente.
        try {
            if (wifiManager.isWifiEnabled()) {
                log("Apagando Wi-Fi cliente para liberar la radio…");
                wifiManager.setWifiEnabled(false);
                sleep(1200);
            }
        } catch (Throwable t) {
            log("Aviso: no se pudo apagar Wi-Fi cliente: " + t.getClass().getSimpleName());
        }

        WifiConfiguration conf = buildConfig(ssid, passphrase, band, channel);

        // Estrategia 1: persistir la config del AP (con banda) y pedir tethering Wi-Fi.
        if (tryPersistConfigAndTether(conf, band)) {
            log("✅ Estrategia 1 OK: setWifiApConfiguration + startTethering.");
            return true;
        }

        // Estrategia 2: setWifiApEnabled(conf, true) por reflexion (clasico Android <=9).
        if (trySetWifiApEnabled(conf)) {
            log("✅ Estrategia 2 OK: setWifiApEnabled(config, true).");
            return true;
        }

        // Estrategia 3: LocalOnlyHotspot (API publica). No permite elegir banda,
        // pero en Android 9 suele caer en 2.4 GHz por defecto.
        if (band != Band.GHZ_5) {
            if (tryLocalOnlyHotspot()) {
                log("✅ Estrategia 3 OK: LocalOnlyHotspot (banda la elige el sistema).");
                log("   Nota: verifica la banda real; LocalOnlyHotspot no garantiza 2.4/5.");
                return true;
            }
        } else {
            log("Saltando LocalOnlyHotspot: pediste 5 GHz y esta ruta no permite fijar banda.");
        }

        log("❌ Ninguna estrategia pudo levantar el hotspot. Revisa permisos (WRITE_SETTINGS) " +
                "o usa el modo manual (prende el tethering tu mismo y solo lanza el receptor).");
        return false;
    }

    private WifiConfiguration buildConfig(String ssid, String passphrase, Band band, int channel) {
        WifiConfiguration conf = new WifiConfiguration();
        conf.SSID = ssid;
        if (passphrase == null || passphrase.isEmpty()) {
            conf.allowedKeyManagement.set(WifiConfiguration.KeyMgmt.NONE);
        } else {
            conf.preSharedKey = passphrase;
            conf.allowedKeyManagement.set(WifiConfiguration.KeyMgmt.WPA_PSK);
        }

        // Campo oculto apBand: -1 auto, 0 = 2.4 GHz, 1 = 5 GHz.
        setIntFieldQuietly(conf, "apBand", band.apBandValue);
        // Campo oculto apChannel (0 = automatico dentro de la banda).
        if (channel > 0) {
            setIntFieldQuietly(conf, "apChannel", channel);
        }
        return conf;
    }

    /** Estrategia 1: guarda la WifiApConfiguration (con banda) y arranca el tethering Wi-Fi. */
    private boolean tryPersistConfigAndTether(WifiConfiguration conf, Band band) {
        try {
            Method setApConfig = WifiManager.class.getMethod(
                    "setWifiApConfiguration", WifiConfiguration.class);
            Object ok = setApConfig.invoke(wifiManager, conf);
            log("setWifiApConfiguration devolvio: " + ok);
        } catch (Throwable t) {
            Throwable c = rootCause(t);
            log("Estrategia 1: setWifiApConfiguration falló → " +
                    c.getClass().getSimpleName() + ": " + String.valueOf(c.getMessage()));
            return false;
        }

        // startTethering via ConnectivityManager (TETHERING_WIFI = 0).
        try {
            ConnectivityManager cm = (ConnectivityManager)
                    appContext.getSystemService(Context.CONNECTIVITY_SERVICE);
            Class<?> callbackClass = Class.forName(
                    "android.net.ConnectivityManager$OnStartTetheringCallback");

            Object callbackProxy = java.lang.reflect.Proxy.newProxyInstance(
                    callbackClass.getClassLoader(),
                    new Class<?>[]{callbackClass},
                    (proxy, method, args) -> {
                        log("startTethering callback: " + method.getName());
                        return null;
                    });

            Method startTethering = ConnectivityManager.class.getMethod(
                    "startTethering", int.class, boolean.class, callbackClass);
            startTethering.invoke(cm, 0 /*TETHERING_WIFI*/, false, callbackProxy);
            log("startTethering(WIFI) invocado. Banda solicitada: " + band.label);
            sleep(2500);
            return true;
        } catch (Throwable t) {
            log("Estrategia 1: startTethering fallo (" + t.getClass().getSimpleName() +
                    ": " + String.valueOf(t.getMessage()) + ").");
            return false;
        }
    }

    /** Estrategia 2: setWifiApEnabled(config, true) por reflexion. */
    private boolean trySetWifiApEnabled(WifiConfiguration conf) {
        try {
            Method setWifiApEnabled = WifiManager.class.getMethod(
                    "setWifiApEnabled", WifiConfiguration.class, boolean.class);
            Object result = setWifiApEnabled.invoke(wifiManager, conf, true);
            log("setWifiApEnabled devolvio: " + result);
            sleep(2000);
            return Boolean.TRUE.equals(result) || result == null;
        } catch (Throwable t) {
            log("Estrategia 2: setWifiApEnabled fallo (" + t.getClass().getSimpleName() +
                    ": " + String.valueOf(t.getMessage()) + ").");
            return false;
        }
    }

    /** Estrategia 3: LocalOnlyHotspot (API publica, sin control de banda). */
    private boolean tryLocalOnlyHotspot() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) {
            log("Estrategia 3: LocalOnlyHotspot requiere Android 8+.");
            return false;
        }
        final boolean[] started = {false};
        final Object lock = new Object();
        try {
            wifiManager.startLocalOnlyHotspot(new WifiManager.LocalOnlyHotspotCallback() {
                @Override
                public void onStarted(WifiManager.LocalOnlyHotspotReservation reservation) {
                    localOnlyReservation = reservation;
                    try {
                        WifiConfiguration c = reservation.getWifiConfiguration();
                        if (c != null) {
                            activeSsid = c.SSID;
                            activePass = c.preSharedKey;
                            int apBand = getIntFieldQuietly(c, "apBand", -999);
                            activeBandLabel = apBandLabel(apBand);
                            log("LocalOnlyHotspot activo. SSID=" + c.SSID + " key=" + c.preSharedKey);
                            log("Banda reportada (apBand=" + apBand + "): " + apBandLabel(apBand));
                            log("►► CONECTA EL IPHONE A: " + c.SSID + "  /  clave: " + c.preSharedKey);
                        }
                    } catch (Throwable ignored) { }
                    synchronized (lock) { started[0] = true; lock.notifyAll(); }
                }

                @Override
                public void onFailed(int reason) {
                    log("LocalOnlyHotspot onFailed, reason=" + reason);
                    synchronized (lock) { lock.notifyAll(); }
                }
            }, main);

            synchronized (lock) {
                try { lock.wait(6000); } catch (InterruptedException ignored) { }
            }
            return started[0];
        } catch (Throwable t) {
            log("Estrategia 3: startLocalOnlyHotspot fallo (" + t.getClass().getSimpleName() +
                    ": " + String.valueOf(t.getMessage()) + ").");
            return false;
        }
    }

    /** Apaga el hotspot que esta app haya levantado. */
    public void stopHotspot() {
        log("── Deteniendo hotspot ──");
        if (localOnlyReservation != null) {
            try {
                localOnlyReservation.close();
                log("LocalOnlyHotspot cerrado.");
            } catch (Throwable t) {
                log("Error cerrando LocalOnlyHotspot: " + t.getClass().getSimpleName());
            }
            localOnlyReservation = null;
        }
        activeSsid = null;
        activePass = null;
        activeBandLabel = null;
        // Intento de apagar SoftAP clasico.
        try {
            WifiConfiguration cur = null;
            try {
                Method getApConfig = WifiManager.class.getMethod("getWifiApConfiguration");
                cur = (WifiConfiguration) getApConfig.invoke(wifiManager);
            } catch (Throwable ignored) { }
            Method setWifiApEnabled = WifiManager.class.getMethod(
                    "setWifiApEnabled", WifiConfiguration.class, boolean.class);
            setWifiApEnabled.invoke(wifiManager, cur, false);
            log("setWifiApEnabled(false) invocado.");
        } catch (Throwable t) {
            log("No se pudo apagar SoftAP por reflexion: " + t.getClass().getSimpleName());
        }
        // Intento de stopTethering.
        try {
            ConnectivityManager cm = (ConnectivityManager)
                    appContext.getSystemService(Context.CONNECTIVITY_SERVICE);
            Method stopTethering = ConnectivityManager.class.getMethod("stopTethering", int.class);
            stopTethering.invoke(cm, 0 /*TETHERING_WIFI*/);
            log("stopTethering(WIFI) invocado.");
        } catch (Throwable ignored) { }
        // Reactivar el Wi-Fi cliente que apagamos al iniciar.
        try {
            wifiManager.setWifiEnabled(true);
            log("Wi-Fi cliente reactivado.");
        } catch (Throwable ignored) { }
    }

    private void setSystemCredsLabels() {
        activeSsid = "(el de Ajustes del sistema)";
        activePass = "(el de Ajustes del sistema)";
        activeBandLabel = "según Ajustes";
    }

    /** ¿Hay una interfaz de AP (softap) levantada ahora mismo? */
    private boolean apInterfacePresent() {
        try {
            java.util.Enumeration<NetworkInterface> ifs = NetworkInterface.getNetworkInterfaces();
            if (ifs == null) return false;
            for (NetworkInterface ni : java.util.Collections.list(ifs)) {
                String n = ni.getName();
                if (n == null) continue;
                boolean looksAp = n.startsWith("ap") || n.toLowerCase().contains("softap")
                        || n.equals("wlan1");
                if (looksAp && ni.isUp() && ni.getInetAddresses().hasMoreElements()) {
                    return true;
                }
            }
        } catch (Throwable ignored) { }
        return false;
    }

    /** Enciende el hotspot del sistema (tethering Wi-Fi) usando la config guardada en Ajustes. */
    private boolean startSystemTethering() {
        // Liberar la radio (una sola radio: no puede ser cliente y AP a la vez).
        try {
            if (wifiManager.isWifiEnabled()) {
                wifiManager.setWifiEnabled(false);
                sleep(1200);
            }
        } catch (Throwable ignored) { }
        try {
            ConnectivityManager cm = (ConnectivityManager)
                    appContext.getSystemService(Context.CONNECTIVITY_SERVICE);
            Class<?> callbackClass = Class.forName(
                    "android.net.ConnectivityManager$OnStartTetheringCallback");
            Object callbackProxy = java.lang.reflect.Proxy.newProxyInstance(
                    callbackClass.getClassLoader(),
                    new Class<?>[]{callbackClass},
                    (proxy, method, args) -> {
                        log("startTethering callback: " + method.getName());
                        return null;
                    });
            Method startTethering = ConnectivityManager.class.getMethod(
                    "startTethering", int.class, boolean.class, callbackClass);
            startTethering.invoke(cm, 0 /*TETHERING_WIFI*/, false, callbackProxy);
            log("startTethering(WIFI) del sistema invocado.");
        } catch (Throwable t) {
            Throwable c = rootCause(t);
            log("startTethering falló → " + c.getClass().getSimpleName()
                    + ": " + String.valueOf(c.getMessage()));
            return false;
        }
        // Esperar a que aparezca la interfaz del AP.
        for (int i = 0; i < 12; i++) {
            if (apInterfacePresent()) return true;
            sleep(500);
        }
        return apInterfacePresent();
    }

    /** Lee y reporta la configuracion de AP actual (util para verificar la banda real). */
    public void reportCurrentApConfig() {
        if (localOnlyReservation != null && activeSsid != null) {
            log("Red activa (LocalOnlyHotspot) → SSID=" + activeSsid
                    + " | clave=" + activePass + " | banda=" + activeBandLabel);
            return;
        }
        try {
            Method getApConfig = WifiManager.class.getMethod("getWifiApConfiguration");
            WifiConfiguration c = (WifiConfiguration) getApConfig.invoke(wifiManager);
            if (c == null) {
                log("getWifiApConfiguration devolvio null (el ROM puede ocultarla).");
                return;
            }
            int apBand = getIntFieldQuietly(c, "apBand", -999);
            int apChannel = getIntFieldQuietly(c, "apChannel", -999);
            log("Config AP actual → SSID=" + c.SSID
                    + " | apBand=" + apBand + " (" + apBandLabel(apBand) + ")"
                    + " | apChannel=" + apChannel);
        } catch (Throwable t) {
            Throwable c = rootCause(t);
            log("No se pudo leer la config AP: " + c.getClass().getSimpleName()
                    + ": " + String.valueOf(c.getMessage()));
        }
    }

    /** Desenvuelve InvocationTargetException y similares para ver el error real. */
    private Throwable rootCause(Throwable t) {
        Throwable c = t;
        while (c.getCause() != null && c.getCause() != c) {
            c = c.getCause();
        }
        return c;
    }

    // ───────── helpers de reflexion ─────────

    private void setIntFieldQuietly(Object target, String field, int value) {
        try {
            Field f = target.getClass().getField(field);
            f.setInt(target, value);
            log("Campo " + field + " = " + value + " aplicado.");
        } catch (Throwable t) {
            log("Aviso: no existe/accesible el campo " + field + " (" +
                    t.getClass().getSimpleName() + "). La banda podria no fijarse.");
        }
    }

    private int getIntFieldQuietly(Object target, String field, int fallback) {
        try {
            Field f = target.getClass().getField(field);
            return f.getInt(target);
        } catch (Throwable t) {
            return fallback;
        }
    }

    private String apBandLabel(int apBand) {
        switch (apBand) {
            case -1: return "Auto";
            case 0: return "2.4 GHz";
            case 1: return "5 GHz";
            default: return "desconocida";
        }
    }

    private void sleep(long ms) {
        try { Thread.sleep(ms); } catch (InterruptedException ignored) { }
    }
}
