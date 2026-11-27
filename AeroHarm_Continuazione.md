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

**Prima prova con lo strumento: 27/11/2026**, con l'AE-20 collegato via USB al
Mac e non al telefono. Il Mac ha mandato all'AE-20 gli stessi messaggi della
procedura guidata con gli script di `harmonizer/mac/` (§2.1), e Nic ha detto a
ogni passo cosa sentiva. L'app sul telefono con lo strumento collegato non è
ancora stata provata. Gli esiti sono in §4.0; sotto ogni test di §4 c'è la
riga «Esito 27/11/2026».

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

### 2.1 Prove dal Mac, con l'AE-20 collegato via USB

Con l'AE-20 collegato al Mac, CoreMIDI vede le porte `AE-20 MIDI IN1` (verso
lo strumento) e `AE-20 MIDI OUT1` (dallo strumento). Gli script in
`harmonizer/mac/` usano quelle due porte, che sono le stesse che apre l'app
(porta 0). Si compilano con `swiftc`, senza dipendenze:

```bash
cd harmonizer/mac
for f in prova ascolta sysex copia cc; do swiftc -O $f.swift -o $f; done
```

| Script | Cosa fa |
|---|---|
| `prova <prova>` | Manda all'AE-20 i messaggi di una prova della procedura guidata: `nota <ch> [cc val]`, `uno <ch>`, `canali`, `legato`, `poly`, `accordo`, `voicing`, `scala`, `mono`, `panic`. Prima delle note manda CC11 e CC2 a 100, perché senza CC2 il motore non suona (test 2). |
| `ascolta <secondi>` | Stampa cosa trasmette l'AE-20 mentre lo si suona, e alla fine un riepilogo per tipo di messaggio e canale. Serve a vedere su quale canale trasmette e quali CC manda. |
| `sysex leggi <a b c d> <size>` | Legge un parametro dello strumento con un RQ1 Roland (device ID `10`, model ID `00 00 00 7D`) e stampa il DT1 di risposta. Indirizzi in `AE-30_AE-20_MIDI_imple_eng06_W.pdf`, §3 «Parameter Address Map». |
| `sysex scrivi <a b c d> <valori>` | Scrive un parametro con un DT1. Scritto nella scena in uso (`01 00 …`), non cambia la scena salvata. |
| `copia <hi> <lo> [verifica]` | Copia la scena in uso nella scena utente all'indirizzo `<hi> <lo> 00 00`, blocco per blocco (56 blocchi, 3317 byte). Con `verifica` non scrive: rilegge le due scene e conta le differenze. |
| `cc <ch> <cc> <valore>` | Manda un solo Control Change. |

Indirizzo di una scena utente: scena n (1–600) → `40 + (n−1)/128`, `(n−1) mod 128`.
Esempio: la scena utente 101 (banco 03, posizione 1) sta a `40 64 00 00`.

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

### 3.1 Quando un'impostazione diventa effettiva (verificato il 27/11/2026)

- **Un valore cambiato nel menu dello strumento vale solo dopo l'uscita dal
  menu.** Con `MIDI Ctrl Sound` su Off nel display ma il menu ancora aperto,
  l'AE-20 rispondeva via SysEx `On` (`00 00 00 05` = `01`). Uscito dal menu,
  rispondeva `Off` (`00`).
- **`Tx Channel` non è nel menu dello strumento.** È un parametro della scena,
  blocco «MIDI Control Mode», all'indirizzo `01 00 60 00` della scena in uso
  (valore 0–15 = canale 1–16). La MIDI Implementation ha anche un blocco
  `01 00 61 00` con lo stesso campo, senza dire a cosa serva: il 27/11 sono
  stati scritti tutti e due a `0F`.
- **`Tx Channel` vale solo dopo aver spento e riacceso lo switch
  `[MIDI CONTROL]`.** Dopo la scrittura via SysEx l'AE-20 trasmetteva ancora
  sul canale 1; dopo off e on dello switch trasmetteva sul canale 16.
- Con lo stesso passaggio (switch off e on) anche `MIDI Ctrl Sound = Off` ha
  spento il suono dello strumento suonato a fiato. Prima del passaggio lo
  strumento suonava a fiato con `MIDI Ctrl Sound` Off letto via SysEx.

### 3.2 La scena preparata: scena utente 101 (banco 03, posizione 1)

Il 27/11/2026, su decisione di Nic, il Mac ha salvato nella scena utente 101
(banco 03, posizione 1, prima vuota: «INIT SCENE») la scena «Taking Lead» con
queste modifiche, scritte via SysEx:

| Parametro | Parti | Valore |
|---|---|---|
| `Tx Channel` (blocchi MIDI Control Mode 01 e 02) | — | 16 |
| `Part Switch` | 2, 3, 4, 5 | On (prima Off) |
| `Mono/Poly` | 2, 3, 4, 5 | Mono (prima «Tone») |
| `Legato Switch` | 2, 3, 4, 5 | On |
| `Unison Switch` | 2, 3, 4, 5 | Off |

Il timbro delle parti 2–5 non è stato scelto: è quello che la scena aveva.
`Legato Retrigger Interval` dei timbri non è stato letto né impostato. La
rilettura della scena utente 101 dà 0 differenze rispetto alla scena scritta,
e Nic l'ha richiamata dallo strumento. **Non verificato**: che la scena resti
salvata dopo lo spegnimento dell'AE-20.

---

## 4. I test e cosa fare di ogni esito

Aprire l'app, `test` → `avvia`. Sette domande sì/no. Sotto, per ciascuna, cosa
cambiare nel codice a seconda della risposta.

### 4.0 Esiti delle prove del 27/11/2026

Prove fatte dal Mac (§2.1) sulla scena utente 101 (§3.2), con prova e
contro-prova dove indicato. I test 5 e 6 sono quelli della procedura guidata
del branch `vista-fx` (polifonia su una parte con `CC127`, otto voci di
voicing); qui sotto, in questa versione della guida, corrispondono al
«Test 5 — polifonia».

| Test | Esito | Prova e contro-prova |
|---|---|---|
| 1 — `MIDI Ctrl Sound` | Con **Off** effettivo (§3.1) l'AE-20 suonato a fiato è muto, e le note mandate dal Mac sul canale 1 si sentono. **"Off" è un local off: topologia §3 della documentazione valida.** | Off letto via SysEx (`00`); a fiato muto; nota dal Mac udita. |
| 2 — espressione | Il motore suona una nota ricevuta via MIDI **solo se preceduta da CC2**. Nota nuda: no. Con CC11 = 100: no. Con CC2 = 100: sì. | Tre prove con `MIDI Ctrl Sound` On. |
| 3 — canali e parti | I canali **1, 2, 3, 4** suonano, il **canale 5 no**. Il canale 3 suona la parte 3. | Parte 3 spenta via SysEx: il canale 3 tace; riaccesa: suona. Le parti 2 e 5 hanno gli stessi parametri (99 byte letti via SysEx): il silenzio del canale 5 non dipende dalla parte 5. La corrispondenza canale *n* → parte *n* è verificata solo per la parte 3. |
| 4 — legato | **Non deciso.** Con il timbro della parte 2 Nic non sente differenze fra `Legato Switch` On e Off. | Va rifatto con un timbro con attacco riconoscibile sulla parte 2. |
| 5 — polifonia su una parte | `CC127` mette la parte in poly: quattro note insieme sulla parte 2 si sentono. **`CC126` rimette la parte in mono solo con valore 1; con valore 0 l'AE-20 lo ignora.** | Il parametro `Mono/Poly` della parte 2 (`01 00 31 24`) letto via SysEx dopo ogni messaggio, due volte: CC126=0 → resta `01` (Poly); CC126=1 → `00` (Mono); CC127 → `01`. A orecchio: con CC127 accordo, con CC126=1 una nota sola. |
| 6 — voci del voicer | **Sei** note sulle parti 2–4, due per parte, suonano insieme. Più di sei non è stato provato. | Otto note insieme non si contano a orecchio: le note sono entrate una al secondo e nessuna è sparita. |

Il canale 5 e più di sei note insieme non sono stati provati oltre quanto
scritto sopra. Un indizio, **non verificato**: la Parameter Address Map ha
cinque «Scene Tone» per scena ma quattro «Temporary Tone PCM»
(`02 00 00 00` … `02 03 00 00`).

### Test 1 — `MIDI Ctrl Sound`, priorità massima

Decide l'intera architettura. **Farlo per primo.**

**1a**: con `MIDI Ctrl Sound = ON` la nota inviata dall'app si sente?
**1b**: con `MIDI Ctrl Sound = OFF` si sente **ancora**?

| Esito 1b | Significato | Azione |
|---|---|---|
| **sì** | "off" è un local off: il motore risponde ancora al MIDI in ingresso | Nessuna modifica. Topologia §3 valida, lasciare `MIDI Ctrl Sound = Off`. |
| **no** | il motore è muto del tutto | Serve la topologia alternativa: vedi §4.1. |

**Esito 27/11/2026: 1b = sì.** Nessuna modifica. Attenzione a §3.1: la prima
volta il test aveva dato esiti invertiti perché le impostazioni non erano
ancora effettive.

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

**Esito 27/11/2026: suona solo con CC2.** L'azione della tabella è
`initialExpression = 100`, `mirrorCCs = intArrayOf(2, 11, 1)`: **non ancora
applicata**, in attesa di decisione di Nic. Anche la procedura guidata dell'app
manda solo CC11 prima delle note (`scanCanali` e `provaLegato` in
`MainActivity.kt`; sul branch `vista-fx` anche `provaPoly` e `provaVoicing`):
con questo strumento quelle prove non suonerebbero.

### Test 3 — mappa canali e parti

Lo scan suona la stessa nota su ch1…ch5. Annotare quali rispondono e con quale
timbro.

Azione: aggiornare in `HarmConfig` il campo `leadChannel` e il `channel` di
ciascun `VoiceConfig`. Se rispondono meno di quattro parti melodiche, ridurre il
numero di voci.

**Esito 27/11/2026: rispondono i canali 1–4, il canale 5 no.** Restano tre
parti di armonia (canali 2, 3, 4). Il voicer (branch `vista-fx`) usa i canali
2–5 (`CANALE_VOCE_BASE = 2`, `PARTI_ARMONIA = 4`). Come adattare il codice è
**una decisione di Nic ancora senza risposta**.

### Test 4 — legato senza retrigger

Due note sovrapposte sulla parte di armonia: si sente un nuovo attacco?

| Esito | Azione |
|---|---|
| **no**, cambia solo l'intonazione | Nessuna modifica: `Harmonizer.tick()` già manda la nuova nota prima del note-off. |
| **sì**, riattacca | Implementare la via a pitch bend: §4.2. |

**Esito 27/11/2026: non deciso.** Con il timbro della parte 2 della scena
utente 101 non si sente differenza fra legato acceso e spento. Rifare con un
timbro con attacco riconoscibile sulla parte 2, scelto da Nic.

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

**Esito parziale 27/11/2026** (prove dal Mac, non suonando):
- `CC127` mette una parte in poly, e una sola parte suona quattro note insieme.
- `CC126` rimette la parte in mono **solo con valore 1**: con valore 0 l'AE-20
  non cambia niente. Il codice del branch `vista-fx` manda `CC126` con valore 0
  (`concludi()` in `MainActivity.kt` e il cambio di effetto, §5 invariante 13
  della guida su quel branch): così le parti restano in poly. Correzione
  **non ancora applicata**, in attesa di decisione di Nic.
- Sei note sulle parti 2–4, due per parte, suonano insieme. Più di sei non è
  stato provato.

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

**Un'impostazione cambiata non ha effetto**: uscire dal menu dello strumento;
per `Tx Channel` e `MIDI Ctrl Sound` spegnere e riaccendere lo switch
`[MIDI CONTROL]` (§3.1). Il valore vero si legge dal Mac con `sysex leggi`.

**Nessun suono ma i messaggi arrivano**: quasi certamente il test 2 —
l'espressione. Provare a mano `initialExpression = 100`.

**Note doppie**: il motore interno è acceso mentre l'app ri-emette la melodia.
Vedi §4.1.

**L'app viene sospesa a metà pezzo**: escludere l'app dall'ottimizzazione
batteria nelle impostazioni di sistema e verificare che `HarmService` sia attivo
(notifica persistente presente).
