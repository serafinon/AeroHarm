# AeroHarm — come continuare

Guida di consegna. Serve a chiudere le questioni rimaste aperte quando
l'Aerophone AE-20 sarà disponibile. È autosufficiente: chi la legge non ha
bisogno della cronologia del progetto, ma deve avere accanto
`AeroHarm_Documentazione.md` per il contesto tecnico.

---

## 1. Stato

**Funziona ed è verificato senza strumento**: il motore di armonia (gradi non
semitoni, scala scelta per accordo, grafia sul circolo delle quinte), il
tracciamento voci, le macchine a stati, l'app Android completa — carosello,
selettore circolare, editor progressione, scaletta con richiamo scena, servizio
in primo piano, procedura guidata.

**Non è mai stato provato con l'hardware.** Tutto ciò che segue riguarda quello.

Progetto: `~/Downloads/aerofoni/harmonizer/` — APK pronto in `AeroHarm.apk`
Documentazione: `~/Downloads/aerofoni/AeroHarm_Documentazione.md`
Manuali Roland: `~/Downloads/aerofoni/*.pdf`

---

## 2. Ambiente

```bash
# build
cd ~/Downloads/aerofoni/harmonizer/android
export ANDROID_HOME=~/Library/Android/sdk
export JAVA_HOME=$(/usr/libexec/java_home)
gradle :app:assembleDebug

# installa (sostituire l'ID del dispositivo con quello di `adb devices`)
ADB=~/Library/Android/sdk/platform-tools/adb
$ADB install -r app/build/outputs/apk/debug/app-debug.apk
$ADB shell am start -n harmonizer.app/.MainActivity

# log
$ADB logcat | grep -i harmonizer

# schermata
$ADB shell screencap -p /sdcard/s.png && $ADB pull /sdcard/s.png /tmp/s.png
```

Verifica del core **senza Android e senza strumento** (utile dopo ogni modifica
alla logica musicale):

```bash
cd ~/Downloads/aerofoni/harmonizer
K="/Applications/Android Studio.app/Contents/plugins/Kotlin/kotlinc"
java -cp "$K/lib/kotlin-compiler.jar" org.jetbrains.kotlin.cli.jvm.K2JVMCompiler \
  -nowarn -d out core/src/Harmony.kt core/src/Scales.kt core/src/DemoScale.kt
java -cp "out:$K/lib/kotlin-stdlib.jar" harmonizer.core.DemoScaleKt
```

Altri banchi: `DemoKt` (verifiche musicali), `DemoEstraneeKt` (note fuori scala),
`BenchKt` (costo per nota).

⚠️ `core/src/` e `android/app/src/main/java/harmonizer/core/` sono **due copie**
dello stesso modulo. Modificando l'una, riallineare l'altra.

---

## 3. Preparazione dello strumento

Da fare **prima** dei test, seguendo §10 della documentazione. In sintesi:
`MIDI CONTROL` on · `Tx Channel` 16 · `MIDI Speed` 1 · `Hold Mode` Off ·
`Breath_1` → CC11 · `Breath_2` → CC2 · `BiteDn_1`/`BiteUp_1` → Bend Down/Up ·
`S1_1` → CC86 Momentary 0/127 · `S2_1` → CC87 Momentary 0/127.

Scena, con Aerophone Pro Editor: Part 1 melodia; Parti 2–3 (e 4) con
`PartSW` On, `Mono/Poly` **MONO**, `Legato Switch` **ON**, `Unison Switch`
**Off**, tuning a 0; sul tone delle parti di armonia
`Legato Retrigger Interval` **OFF**.

---

## 4. I test e cosa fare di ogni esito

Aprire l'app, `test` → `avvia`. Sette domande sì/no. Sotto, per ciascuna, cosa
cambiare nel codice a seconda della risposta.

### Test 1 — `MIDI Ctrl Sound`, priorità massima

Decide l'intera architettura. **Farlo per primo.**

**1a**: con `MIDI Ctrl Sound = ON` la nota inviata dall'app si sente?
**1b**: con `MIDI Ctrl Sound = OFF` si sente **ancora**?

| Esito 1b | Significato | Azione |
|---|---|---|
| **sì** | "off" è un local off: il motore risponde ancora al MIDI in ingresso | Nessuna modifica. Topologia §3 valida, lasciare `MIDI Ctrl Sound = Off`. |
| **no** | il motore è muto del tutto | Serve la topologia alternativa: vedi §4.1. |

### 4.1 Se il test 1b fallisce

Occorre `MIDI Ctrl Sound = On`, il che comporta due problemi.

**Problema A — la melodia verrebbe raddoppiata.** Con il motore acceso la
melodia la suona già lo strumento. Va tolta la ri-emissione: in
`Harmonizer.kt`, funzioni `onNoteOn` e `onNoteOff`, rimuovere le righe
`out.noteOn(config.leadChannel, ...)` e `out.noteOff(config.leadChannel, ...)`.
Mettere la scelta dietro un flag `HarmConfig.reemitLead` invece di cancellarla.

**Problema B — le parti di armonia sono in layer**, quindi suonano anche la nota
che il musicista preme, non solo quelle iniettate. Due strade, entrambe da
valutare al banco:

- **Filtro per Key Range**. Su ciascuna parte di armonia impostare
  `Key Range` in una zona che il musicista non suona mai (p.es. `C0`–`B1`) e
  compensare con `Part Octave Shift`. L'app trasporta le note iniettate in
  quella zona e la parte le rialza. Limite: `Part Octave Shift` arriva a ±3
  ottave, quindi la zona utile è stretta — verificare che copra l'estensione
  che usi davvero.
- **Filtro per Velocity Range**. Iniettare le note di armonia con una velocity
  in una banda che il tonguing non produce mai, e impostare
  `Velocity Range` delle parti di armonia su quella banda. Più semplice, ma
  dipende da quali velocity genera davvero il tuo tonguing: guardarle prima nel
  monitor MIDI.

Se nessuna delle due regge, resta la topologia con motore acceso e melodia non
ri-emessa, accettando il flam fra melodia (immediata) e armonia (dal loop).
Misurarlo prima di scartarlo: potrebbe essere tollerabile.

### Test 2 — serve l'espressione?

**2**: nota nuda. **2b**: preceduta da `CC11 = 100`. **2c**: da `CC2 = 100`.

| Esito | Azione in `HarmConfig` (`Harmonizer.kt`) |
|---|---|
| suona già nuda | `initialExpression = 0` |
| suona con CC11 | `initialExpression = 100`, `mirrorCCs = intArrayOf(11, 2, 1)` |
| suona con CC2 | `initialExpression = 100`, `mirrorCCs = intArrayOf(2, 11, 1)` |
| non suona mai | non toccare il codice: rivedere canale e configurazione della parte, poi ripetere |

Se serve l'espressione, verificare anche che `emitHarmony` la invii **prima**
del note-on — lo fa già, ma il diradamento (`thinMs`) potrebbe sopprimerla se il
valore non è cambiato. In quel caso forzare l'invio al primo note-on di una
voce, bypassando `sendCcThinned`.

### Test 3 — mappa canali e parti

Lo scan suona la stessa nota su ch1…ch5. Annotare quali rispondono e con quale
timbro.

Azione: aggiornare in `HarmConfig` il campo `leadChannel` e il `channel` di
ciascun `VoiceConfig`. Se rispondono meno di quattro parti melodiche, ridurre il
numero di voci.

### Test 4 — legato senza retrigger

Due note sovrapposte sulla parte di armonia: si sente un nuovo attacco?

| Esito | Azione |
|---|---|
| **no**, cambia solo l'intonazione | Nessuna modifica: `Harmonizer.tick()` già manda la nuova nota prima del note-off. |
| **sì**, riattacca | Implementare la via a pitch bend: §4.2. |

### 4.2 Se il legato riattacca — implementare il bend

In `Harmonizer.kt`:

1. All'avvio, impostare il range di bend sui canali di armonia con
   `RPN 00 00`: inviare `cc(ch,101,0)`, `cc(ch,100,0)`, `cc(ch,6,N)`,
   `cc(ch,38,0)`, `cc(ch,101,127)`, `cc(ch,100,127)` con `N` = semitoni
   (2 basta: i movimenti fra accordi vicini stanno in uno o due semitoni).
2. Memorizzare l'ultimo pitch bend ricevuto dal morso in un campo
   `bendMorso` dentro `onPitchBend`, e smettere di inoltrarlo tale e quale ai
   canali di armonia.
3. In `tick()`, invece di `out.noteOn(neu)` + `out.noteOff(old)`, calcolare
   `offsetSemitoni = neu - notaBaseDellaVoce` e inviare
   `pitchBend(ch, 8192 + bendMorso + offsetSemitoni * 8192 / N)`.
4. `notaBaseDellaVoce` è la nota con cui la voce è entrata: va memorizzata nel
   `VoiceTracker` accanto alla nota corrente, perché il bend è relativo a
   quella.

Attenzione: il bend è **per canale**, quindi ogni voce deve stare su un canale
proprio. Se il test 3 ha mostrato meno canali disponibili del previsto, ridurre
le voci di conseguenza.

### Test 5 — polifonia (non nel wizard, da fare a mano)

Non documentata da Roland. Suonare tenendo più note con tutte le voci attive e
verificare a quante voci simultanee le note iniziano a essere tagliate. Impostare
`VoiceTracker(maxNotes, maxVoices)` in `Harmonizer.kt` di conseguenza.

---

## 5. Invarianti da non rompere

Sono vincoli derivati dai manuali o dal comportamento voluto. Chi tocca il codice
li deve conoscere.

1. **Mai trasmettere Active Sensing (`FEH`).** Se l'AE-20 lo riceve e poi passano
   più di 420 ms senza messaggi, esegue da solo All Sound Off, All Notes Off e
   Reset All Controllers. Se un framework lo invia d'ufficio, disattivarlo.
2. **Lo stop non deve fermare la melodia.** Con `MIDI Ctrl Sound = Off` la
   melodia esiste solo perché l'app la ri-emette: uno stop scritto come "smetti
   di trasmettere" lascia lo strumento muto. Vedi `Harmonizer.stopAll`.
3. **Il mute non deve toccare il transport.** La progressione continua a
   scorrere, così alla riaccensione l'armonia rientra sull'accordo giusto.
4. **La voice map ha slot separati per voce.** Il mute agisce sulle voci di
   armonia, la melodia resta. Senza, sulle linee legato restano note appese.
5. **I CC di controllo (86, 87) sono consumati, mai inoltrati.**
6. **Percorso caldo senza allocazioni**: `AllowedCache` precalcola gli insiemi,
   `VoiceTracker` usa array fissi, il buffer MIDI è preallocato. Non introdurre
   `HashMap`, liste o stringhe dentro `onNoteOn`/`onNoteOff`/`onControlChange`.
7. **Gesti riconosciuti solo sul fronte di salita**: in Momentary il rilascio
   manda anche il Release Value.
8. **La voce conserva il proprio grado alla riaccordatura**, e se la nota
   risultante è invariata non si invia nulla.

---

## 6. Cosa resta da fare, oltre ai test

- `minDurationGate` e `breathGate` esistono in `HarmConfig` ma non hanno
  interfaccia: aggiungere due controlli in una schermata impostazioni.
- Nessuna schermata per configurare le voci (gradi, registro, modo di selezione,
  canale): oggi si cambiano solo nel codice, in `HarmConfig.voices`.
- La scaletta non ha import/export: vive solo nelle preferenze del telefono.
- Nella striscia qualità del selettore, `triade` viene prima di `7`: in contesto
  jazz converrebbe l'ordine inverso (`ChordPicker.qualitaDisponibili`).
- Device Max for Live: non iniziato. Il core è già isolato, servirebbe solo il
  guscio `midiin`/`midiout`.

---

## 7. Se qualcosa non funziona

**Nessun dispositivo MIDI trovato**: verificare che il cavo USB-C sia dati e non
solo ricarica, che il telefono supporti USB OTG host, e che l'AE-20 sia acceso
con `USB Driver = Generic`.

**Note appese**: `panic` nella schermata test. Se ricorre, è quasi sempre la
voice map: controllare che ogni percorso che spegne una voce azzeri anche lo slot.

**Nessun suono ma i messaggi arrivano**: quasi certamente il test 2 —
l'espressione. Provare a mano `initialExpression = 100`.

**Note doppie**: il motore interno è acceso mentre l'app ri-emette la melodia.
Vedi §4.1.

**L'app viene sospesa a metà pezzo**: escludere l'app dall'ottimizzazione
batteria nelle impostazioni di sistema e verificare che `HarmService` sia attivo
(notifica persistente presente).
