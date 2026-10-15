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

## Compilar
GitHub Actions compila solo en cada cambio (pestaña Actions) y publica el APK en Releases como "ultima".
