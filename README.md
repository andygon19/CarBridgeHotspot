# CarBridge Hotspot — Orquestador para XPeng G3i

App de prueba para **levantar el hotspot del equipo en una banda concreta (2.4 / 5 / Auto)**
y luego **lanzar el receptor** (DiPlay / DiAuto u otro) para que la proyección use ese hotspot,
evitando el fallo `FAILED_NO_CHANNEL` que ocurre en el G3i cuando la banda queda en 5 GHz.

> Prueba de concepto. No modifica el sistema ni rompe protecciones: usa APIs de Android
> (algunas ocultas, por reflexión) que ciertos ROM de fabricante permiten con `WRITE_SETTINGS`.
> Si el XOS no deja controlar el SoftAP desde una app, el registro lo dirá claramente y queda
> el modo manual (prendes el tethering tú y solo usas el botón "Lanzar receptor").

## Qué hace

1. Eliges **banda** (2.4 / 5 / Auto), **SSID**, **contraseña** y **canal** (0 = automático).
2. "Iniciar hotspot" intenta, en orden, tres estrategias y registra cuál funciona:
   - **1.** `setWifiApConfiguration` (con banda) + `startTethering(WIFI)`
   - **2.** `setWifiApEnabled(config, true)` por reflexión (clásico Android ≤9)
   - **3.** `LocalOnlyHotspot` (API pública; la banda la elige el sistema, suele ser 2.4)
3. "Estado AP" lee la banda/canal reales del hotspot para que confirmes que quedó en 2.4.
4. "Lanzar receptor" abre el paquete indicado (prellenado con `com.shihab.diplay`).
5. "Iniciar todo" hace hotspot + verificación + lanzar receptor en un solo flujo.

Los paquetes prellenados salieron del logcat de tu propio G3i:
`com.shihab.diplay` (DiPlay) y `com.andrerinas.headunitrevived` (DiAuto).

## Requisitos de compilación

- Android Studio, o línea de comandos con JDK 17 y Android SDK (compileSdk 34).
- `minSdk 28`, `targetSdk 28` (a propósito, para conservar el comportamiento clásico de SoftAP).

## Compilar

Con Android Studio: abre la carpeta y pulsa *Run/Build*.

Por línea de comandos (si agregas el wrapper de Gradle):

```bash
gradle wrapper --gradle-version 8.2
./gradlew assembleDebug
# APK en app/build/outputs/apk/debug/app-debug.apk
```

## Instalar y probar en el G3i

```powershell
adb install -r app-debug.apk
# Si alguna estrategia falla por permisos, concede:
adb shell pm grant com.etc.hotspotbridge android.permission.WRITE_SECURE_SETTINGS
adb shell appops set com.etc.hotspotbridge WRITE_SETTINGS allow
```

En la app:
1. Botón **Permisos** (concede ubicación y WRITE_SETTINGS).
2. Banda = **2.4 GHz**, deja SSID/clave por defecto.
3. **Iniciar hotspot** → mira el registro.
4. **Estado AP** → confirma `apBand=0 (2.4 GHz)`.
5. Conecta el **iPhone** a ese SSID y pulsa **Lanzar receptor**.

## Honestidad técnica

En Android 9 una app sin firma de sistema **puede no tener permiso** para fijar la banda del
SoftAP. Esta app está hecha para **descubrir empíricamente** qué permite tu XOS: si ninguna
estrategia funciona, el camino sólido sigue siendo el modo **LAN externa** (router de viaje
2.4 GHz) con DiPlay/DiAuto, que no necesita tocar el hotspot del carro.
