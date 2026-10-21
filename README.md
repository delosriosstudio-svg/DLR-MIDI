# DLR MIDI

Controlador MIDI para REAPER desde un teléfono Android (probado para Samsung S25 FE).

## Fase 1 (esta versión)
- **TECLADO** (horizontal): multitouch, deslizar entre teclas, velocidad según la altura del toque
  (arriba suave, abajo fuerte), OCT − / OCT +.
- **SUSTAIN** (CC64): mantener = pedal pisado · doble toque = ENGANCHADO · un toque más = suelta.
- **FADERS** (vertical): 4 faders muy suaves (relativos, no saltan; siguen al dedo aunque se trabe).
  Toca la etiqueta de arriba para elegir su CC. Por defecto: CC1 Modulación, CC11 Expresión, CC7 Volumen, CC74 Cutoff.
- **PITCH**: rueda vertical que vuelve sola al centro al soltarla.
- **CANAL**: un canal MIDI para toda la app (botón arriba).

## Instalar en el teléfono
1. En el teléfono abre la página **Releases** de este repositorio y descarga `DLR-MIDI.apk`.
2. Ábrelo; Android pedirá permitir "instalar apps de esta fuente": acepta.

## Conectar a REAPER (cable USB)
1. Conecta el teléfono al Mac con el cable USB.
2. En el teléfono baja la barra de notificaciones, toca la notificación de USB y elige **MIDI**.
   (Si no aparece: Ajustes > Opciones de desarrollador > Configuración USB predeterminada > MIDI.)
3. Abre DLR MIDI: el punto se pone verde ("Conectado por USB").
4. En REAPER: Preferences > Audio > MIDI Inputs > el dispositivo del teléfono > **Enable input**.
5. En el track: armar para grabar, entrada MIDI = el teléfono (o All MIDI inputs) y monitor encendido.

## Conectar por Wi-Fi (sin cable)
Usa la **Red MIDI de Apple** (RTP-MIDI), que el Mac ya trae. El teléfono y el Mac tienen que estar en el **mismo Wi-Fi**.
1. En el Mac abre **Configuración de Audio MIDI** > menú Ventana > **Mostrar Estudio MIDI** > doble clic en **Red**.
2. Si no hay ninguna sesión, crea una con **+** y marca la casilla para activarla.
3. Abre DLR MIDI en el teléfono. Se conecta sola a esa sesión (el punto se pone verde: "Conectado por Wi-Fi").
   También puedes conectarte desde el Mac: en **Directorio** aparece **DLR MIDI** > **Conectar**.
4. En REAPER: Preferences > Audio > MIDI Inputs > la sesión de red (ej. "Session 1") > **Enable input**.
- Si el cable USB está conectado, se usa el cable (así REAPER no recibe cada nota dos veces).

## Panel de TRANSPORTE (deslizar desde el borde izquierdo)
- **PLAY · STOP · GRABAR · INICIO**: mandan **MMC** y además un **CC en el canal 16**
  (115 Play, 116 Stop, 117 Grabar, 118 Inicio).
  En REAPER: Actions > Show action list > elige la acción (ej. *Transport: Play*) > **Add…** > toca el botón en el teléfono.
  Usa solo una de las dos formas: si asignas los CC, no actives también el MMC en REAPER (o la orden llega dos veces).
- **PRESET** ◀ ▶: manda **Program Change** en el canal de la app. Toca el número para elegir uno directo (1–128).
  En REAPER, para que el plugin cambie de preset: en la ventana del plugin, botón **+** > **Link to MIDI program change**.

## Compilar
GitHub Actions compila solo en cada cambio (pestaña Actions) y publica el APK en Releases como "ultima".
