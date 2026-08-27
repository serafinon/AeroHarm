# AeroHarm — armonizzatore diatonico per Roland Aerophone AE-20

Documentazione completa: strumento, progetto, codice, uso.
Aggiornata al 25 agosto 2026.

Per **continuare i test** quando l'Aerophone sarà disponibile, e per l'albero
delle decisioni su ogni esito, vedi `AeroHarm_Continuazione.md`: è scritto per
essere autosufficiente, anche per un altro agente.

---

## 1. Cos'è

Un'app Android che riceve il MIDI dell'Aerophone AE-20, genera una o più voci di
armonia **diatonica** seguendo una progressione di accordi, e le rimanda allo
strumento, che le suona col proprio motore seguendo le dinamiche di fiato del
musicista.

Il caso d'uso di partenza: improvvisare su un giro di blues con una voce che
armonizza per seconde, dove la qualità dell'intervallo cambia da sola a ogni
accordo.

Sulla stessa architettura gira anche un **arpeggiatore**: la nota suonata dice
solo da dove partire, e l'arpeggio sale sulle note dell'accordo corrente. Gli
effetti si scelgono uno alla volta e si configurano per ogni brano — vedi §14.

**Perché serve.** L'AE-20 ha un motore di armonia a intervalli **fissi**
(`Harmony 1`–`Harmony 4`): trasposizioni parallele costanti, che su una
progressione stonano. L'armonia *intelligente*, quella che segue scala e
tonalità, esiste **solo sull'AE-30**: nella Parameter Address Map i parametri
`Harmony Type`, `Harmony Tune`, `Intelligent Harmony Scale`,
`Intelligent Harmony Key` e `Intelligent Harmony 1–4 Assign` sono marcati
`(Only for AE-30)` e non sono raggiungibili in alcun modo, nemmeno via SysEx.

**Perché non basta pilotare via SysEx l'armonia nativa.** Gli `Harmony N Assign`
sono scrivibili anche sull'AE-20, ma lo strumento genera la voce nell'istante in
cui suoni la nota: un'app esterna la conosce solo *dopo* averla ricevuta, quindi
un SysEx correttivo arriverebbe buono per la nota successiva. Si potrebbe
aggiornare l'intervallo **per accordo**, mai **per nota**, lasciando l'armonia
parallela dentro l'accordo. Per le seconde, l'intervallo che parallelizzato
suona peggio, è inaccettabile. Le voci si generano quindi nell'app.

---

## 2. L'AE-20: dati tecnici

Fonti: *AE-30/AE-20 MIDI Implementation v3.00 (7 marzo 2023)*,
*AE-30/AE-20 Parameter Guide rev. 07*, *AE-20 Owner's Manual*,
*FS-1-WL Connection Guide*.

### 2.1 Ricezione MIDI

| Voce | Valore riconosciuto |
|---|---|
| Basic Channel | **1–5**, Default e Changed |
| Mode | **Mode 3** (OMNI OFF, POLY) e Mode 4 (M=1) |
| Note Number / True Voice | 0–127 |
| Velocity Note On / Off | sì |
| Channel Aftertouch | sì · Polyphonic Key Pressure: **no** |
| Pitch Bend | sì, −8192 … 0 … +8191 |
| Program Change / Bank Select | sì |
| System Exclusive | sì |
| System Real Time Clock | **no** — nessuna sincronizzazione a clock MIDI |
| Local On/Off | **no** — non si fa local off via MIDI |
| All Sound Off / All Notes Off / Reset All Controllers | sì |

**I canali di ricezione sono 1–5 e non sono configurabili.** Non esiste alcun
parametro `Rx Channel`: gli unici parametri "Rx" (`Rx Breath Controller`,
`Rx Side Key (C1)`…) riguardano se il motore risponde ai **propri sensori**.

**[DA VERIFICARE]** La corrispondenza canale → parte non è dichiarata. Ipotesi:
canale *n* → parte *n*. Lo risolve il test 3 della procedura guidata.

### 2.2 Control Change utili

`CC1` Modulation · `CC2` Breath · `CC5` Portamento Time · `CC7` Volume →
`Part Level` · `CC10` Pan · `CC11` Expression · `CC64` Hold · `CC65` Portamento ·
`CC68` Legato · `CC71–78` Resonance, Release, Attack, Cutoff, Decay, Vibrato
Rate/Depth/Delay (relativi, `00–40–7F` = −64…0…+63) · `CC84` Portamento Control ·
`CC91/93` Reverb/Chorus Send · `CC126/127` Mono/Poly.

**RPN**: `00 00` Pitch Bend Sensitivity 0–24 semitoni · `00 02` Channel Coarse
Tuning `10H–40H–70H` = −48…0…+48 semitoni → `Part Coarse Tune` · `7F 7F` null.

### 2.3 Richiamo delle scene

```
Bank Select MSB 085 + LSB 000-011 + PC 001-050  ->  banchi utente U01-U12
Bank Select MSB 085 + LSB 064-075 + PC 001-...  ->  categorie preset P01-P12
```

Usato dalla scaletta (§9). **[DA VERIFICARE]** su quale canale vada inviato;
l'app usa il canale della melodia.

### 2.4 L'indizio sull'espressione

Nella tabella *Reset All Controllers* il documento dichiara
`Expression 127 (max)` e subito dopo aggiunge *«However the controller will be
at minimum»*. Suggerisce che l'espressione sia tenuta al minimo, cioè che **una
nota iniettata resti muta finché non le si manda anche fiato o espressione**.
È l'incognita che il test 2 della procedura guidata risolve in trenta secondi.

### 2.5 Legato senza retrigger

Parametro di **tone**: `Legato Retrigger Interval`, valori `OFF` e `0–12`. Con
`OFF`, *«cambia solo l'intonazione dei tone in suono secondo l'altezza del
tasto»* — cambio di nota **senza nuovo attacco**. È il meccanismo su cui si regge
il §6.

⚠️ `Unison Switch = ON` **forza la parte in mono**. Va tenuto **OFF**.

### 2.6 Avvertenze che vincolano il codice

**Active Sensing.** *«Se l'intervallo tra messaggi supera i 420 ms, viene
eseguita la stessa procedura di All Sound Off, All Notes Off e Reset All
Controllers»*. Conseguenza: **l'app non trasmette mai `FEH`**. Se lo facesse e
poi si fermasse mezzo secondo, l'AE-20 spegnerebbe tutte le note da solo.

**Nessuna presa DIN.** L'AE-20 esce solo come *dispositivo* USB-C (serve un host)
o via Bluetooth LE MIDI. Qui: USB cablato.

**Non si alimenta via USB.** Resta sul suo alimentatore o sulle sei AA Ni-MH, e
non prosciuga il telefono. Ma il telefono in host mode non si ricarica sulla
stessa porta: per una serata lunga serve un hub USB-C alimentato.

**Polifonia massima: non documentata.** Nessun documento dichiara il numero di
voci: `maxVoices` è configurabile e va tarato provando.

**Editing delle scene**: solo con **Aerophone Pro Editor** (iOS/Android, via
Bluetooth). Il menu sullo strumento arriva a sistema, assign, MIDI e profondità
d'effetto, non ai parametri di parte.

---

## 3. Architettura

Topologia raccomandata, **`MIDI Ctrl Sound = Off`**: il motore interno non suona
nulla da sé e *tutto*, melodia inclusa, rientra dal loop.

```
AE-20  ──USB-C──▶  app  ──USB-C──▶  AE-20
Tx ch 16                            ch 1 → Part 1   melodia
MIDI CONTROL on                     ch 2 → Part 2   armonia 1
MIDI Ctrl Sound Off                 ch 3 → Part 3   armonia 2
                                    ch 4 → Part 4   armonia 3
```

**Una parte per voce**, in MONO con `Legato Switch = ON` e
`Legato Retrigger Interval = OFF`: legato e bend agiscono per canale e per
parte, quindi voci che devono muoversi indipendentemente non possono
condividerne una.

### 3.1 Perché questa topologia

| | Motore interno acceso | `MIDI Ctrl Sound = Off` |
|---|---|---|
| Melodia raddoppiata dalle parti in layer | **sì** | nessuno |
| Timing relativo fra le voci | armonia in ritardo → flam | **identico** |
| Latenza assoluta | zero sulla melodia | pochi ms su tutto |
| Dipendenza dall'app | parziale | totale |

Pochi millisecondi assoluti su un fiato equivalgono ad allontanarsi di un metro
dall'amplificatore. Il flam relativo invece si sente.

### 3.2 Core e shell

Il motore, il tracciamento voci e le macchine a stati sono **logica pura senza
I/O**, isolati dalla piattaforma. `Runtime.kt` e `Harmonizer.kt` compilano su JVM
senza Android: la logica resta collaudabile a freddo, e un eventuale device Max
for Live sarebbe un guscio nuovo sullo stesso nucleo.

---

## 4. Il motore di armonia: gradi, non semitoni

**Non** "scelgo un intervallo in semitoni e ne correggo la qualità": logica piena
di casi speciali che sbaglia sulle note cromatiche.

Modello per **gradi**:

1. L'accordo e la **scala scelta** definiscono un insieme di pitch class.
2. Array piatto ordinato di tutte le note MIDI ammesse.
3. Si punta all'ampiezza teorica del grado misurata **dalla nota reale**:
   `bersaglio = nota + 12 · gradi / noteNellOttava`.
4. Si aggancia la nota ammessa più vicina al bersaglio.

Maggiore, minore, giusta e diminuita **emergono da soli**, senza una riga di
logica sulla qualità.

### 4.1 Perché il bersaglio e non il conteggio per indici

Contando gli indici a partire dalla nota agganciata, se l'aggancio sposta la
melodia l'errore si somma all'intervallo. Esempio reale, `Db` su `Bb7`, terza
sopra — è la terza abbassata del blues in Bb, quindi capita di continuo:

| metodo | percorso | risultato |
|---|---|---|
| conteggio | `Db` aggancia a `C`, due gradi da `C` → `Eb` | 2 semitoni, **seconda** |
| bersaglio | terza ≈ 3,4 st sopra `Db` → `F` | 4 semitoni, **terza** |

Sulle note **dentro** la scala i due metodi coincidono: la correzione agisce solo
dove il vecchio sbagliava.

### 4.2 Convenzione degli offset

Passi di scala, non numeri d'intervallo:

| intervallo | offset | | intervallo | offset |
|---|---|---|---|---|
| unisono | 0 | | quinta | **+4** |
| seconda | **+1** | | sesta | +5 |
| terza | **+2** | | settima | +6 |
| quarta | +3 | | ottava | +7 |

Scrivere `+3` intendendo "terza" è l'errore più probabile: l'interfaccia mostra i
nomi musicali e converte internamente.

### 4.3 La scala è una scelta per ogni accordo

Non è una proprietà della qualità. Su `Bb7` puoi suonare misolidia, blues, lidia
dominante o alterata, e sono armonizzazioni diverse. Ogni passo della
progressione porta la propria scala; le scale proponibili sono filtrate per
qualità — su un accordo minore la ionica non compare.

Catalogo (19 scale). I modi della scala maggiore portano l'ordinale:

`ionica (1° m.)` · `dorica (2° m.)` · `frigia (3° m.)` · `lidia (4° m.)` ·
`misolidia (5° m.)` · `eolia (6° m.)` · `locria (7° m.)` · `minore melodica` ·
`lidia dominante` · `misolidia b6` · `locria #2` · `alterata` ·
`minore armonica` · `semitono-tono` · `tono-semitono` · `esatonale` · `blues` ·
`pentatonica maggiore` · `pentatonica minore`

Ordine di probabilità per qualità, usato per l'evidenza nella striscia:
su `7` misolidia, blues, lidia dominante, alterata; su `m7` dorica, eolia,
frigia, blues.

Nota: sulle scale a poche note un grado vale di più. Sulla pentatonica minore la
"terza sopra `Bb`" cade su `Eb`, una quarta. È inerente alle scale gappate, non
un difetto.

### 4.4 Note estranee e scelta dell'intervallo

`strictMode`: spento (default) aggancia comunque; acceso **non armonizza** le
note fuori scala.

`selectionMode`: `FIXED` · `RANDOM` · `RANDOM_NO_REPEAT` · **`VOICE_LEADING`**
(default: sceglie il candidato la cui nota è più vicina alla precedente di quella
voce — dimezza il movimento e rende la linea cantabile) · `PHRASE`.

`voiceLow`/`voiceHigh` delimitano il registro: fuori si ripiega di ottave. Il
ripiegamento può invertire la funzione dell'intervallo — scelta consapevole,
tenuta così.

### 4.5 Grafia

Circolo delle quinte, non enarmonia arbitraria:

```
maggiori         C   G   D   A   E   B   F#  C#  Ab  Eb  Bb  F
relative minori  a   e   b   f#  c#  g#  eb  bb  f   c   g   d
```

Le due sequenze occupano le stesse dodici posizioni. L'unica classe che cambia
grafia fra i modi è la 8: `Ab` maggiore, `g#` minore. Le note dentro un accordo
seguono il versante della fondamentale: `Ab7` usa i bemolli, `G#m7` i diesis.

---

## 5. Comportamento sotto nota tenuta

Se tieni una nota attraverso un cambio di accordo, le voci **si spostano**
seguendo la nuova armonia. È il comportamento predefinito.

Un note-off seguito da note-on produrrebbe un nuovo attacco: in mezzo a una nota
tenuta suona come un colpo di lingua non dato. Due meccanismi:

**Via primaria — legato senza retrigger.** Ogni voce su parte propria, MONO,
`Legato Switch = ON`, `Legato Retrigger Interval = OFF`. L'app manda la nuova
nota **in sovrapposizione**, prima del note-off della precedente: la parte si
sposta di intonazione restando in suono.

**Via alternativa — pitch bend.** Se il legato non si comporta come promesso, si
sposta la voce col bend sul suo canale, sommando l'offset di riaccordatura al
bend che il musicista produce col morso:
`bendUscita = bendMorso + offsetRiaccordo`. L'app conosce la conversione perché è
lei a impostare il range con `RPN 00 00`.

**Regole.** La voce **conserva il proprio grado**: non si ri-estrae l'intervallo,
altrimenti salterebbe a metà nota. Se la nota risultante è **invariata non si
invia nulla** — molti cambi di accordo condividono note. La **melodia non si
tocca mai**.

---

## 6. Controlli e macchine a stati

Tre macchine **indipendenti**. Tenerle separate è ciò che permette all'armonia di
tacere mentre la progressione continua a scorrere.

| Macchina | Stati | Governa |
|---|---|---|
| Transport | `STOPPED`/`RUNNING` + BPM + posizione | avanzamento progressione |
| Mute | `ACTIVE`/`MUTED` | generazione delle voci o dell'arpeggio |
| Passthrough | sempre attivo | melodia ed espressione |

### 6.1 Assegnazione dei controlli fisici

| Controllo | Gesto | Azione |
|---|---|---|
| **S1** | pressione singola | toggle **mute** dell'effetto |
| **S2** | pressione singola | **"questo istante è la battuta 1"** |
| **S2** | pressione lunga (>800 ms) | **stop** completo |

Sullo strumento entrambi si configurano nello strato **MIDI** come CC in
**Momentary**, `Release Val 0` / `Press Val 127`. Lo strumento manda il gesto
fedele, **la logica la decide l'app**: il comportamento si cambia in software
senza rieditare l'AE-20.

Il gesto si riconosce **solo sul fronte di salita**: in Momentary il rilascio
manda anche il Release Value.

Nessun gesto richiede di tenere premuto mentre si suona. La pressione lunga è
riservata allo stop, che si usa fra un pezzo e l'altro con le mani libere.

I CC di controllo (86, 87 di default) sono **consumati** e mai inoltrati.

### 6.2 Mute

Toggle puro, immediato, in qualunque momento. Le voci in suono ricevono il loro
note-off, così la coda di rilascio è naturale. Alla riaccensione l'armonia
rientra subito, anche sotto una nota già tenuta. **Il transport non viene
toccato**: alla riaccensione l'armonia rientra sull'accordo giusto perché il
contatore non si è mai fermato.

### 6.3 Stop

**Fa**: ferma il transport, azzera alla battuta 1, spegne le voci con note-off
espliciti e All Notes Off, riporta il mute ad attivo.

**Non fa**: non interrompe la melodia né lo specchio dell'espressione. Con
`MIDI Ctrl Sound = Off` la melodia esiste solo perché l'app la ri-emette; uno
stop scritto come "smetti di trasmettere" lascerebbe lo strumento muto.

Lo stop sta su S2 e non su S1 perché nella pressione lunga l'azione breve scatta
comunque: su S2 è un riallineamento, inerte visto che subito dopo ci si ferma.

### 6.4 Riallineamento

Semantica unica: **"questo istante è la battuta 1"**. Da fermo avvia, in moto
riallinea. `TO_BAR_1` (default) o `PHASE_ALIGN`.

Poiché la progressione avanza sulle stanghette e viene riallineata a ogni giro,
un errore di BPM si accumula per un solo chorus: **la precisione del tap tempo
conta poco, l'affidabilità del riallineamento conta molto.**

### 6.5 Perché il tap tempo non sta su un tasto fisico

Overloading di un tasto per tap tempo e reset non è risolvibile: al **primo
tocco** l'app non può sapere se sia un reset isolato o il primo di una sequenza.
Può solo agire subito (e sbagliare se era tap tempo) o aspettare la finestra (e
il reset arriva tardi). E le due esigenze tirano in direzioni opposte: per il tap
lento la finestra dev'essere larga, per un reset reattivo nulla.

Il reset è timing-critical e si usa suonando: sta su S2. Il tap tempo si usa fra
un pezzo e l'altro: sta sullo schermo, con il pulsante largo in basso.

---

## 7. Interfaccia

### 7.1 Schermata principale

- **Nome del brano** in cima, piccolo, e sotto la **riga di stato** compatta e
  sempre presente: collegamento, BPM, tempo del pezzo, voci attive, tempo di
  elaborazione in microsecondi.
- **Carosello degli accordi**: la progressione a schede scorrevoli in
  orizzontale, quella corrente evidenziata e centrata. I quadratini in basso a
  ogni scheda sono i quarti; sulla scheda attiva indicano a che punto sei.
  Si scorre trascinando il dito.
  - **tap** su una scheda → la progressione salta lì;
  - **pressione lunga** → apre il selettore per modificare quell'accordo;
  - **scheda «+» in coda**, più stretta delle altre → aggiunge un accordo.

  Tap e trascinamento si distinguono con la soglia di movimento di sistema
  (`scaledTouchSlop`) e un limite di 350 ms, così scorrere non seleziona mai per
  sbaglio. La pressione lunga scatta a 500 ms con un ritorno aptico.
- **FX** e **BATT. 1**: due pulsanti quadrati sulla stessa riga, a simboli
  disegnati, con nell'angolo l'etichetta minuscola `S1` / `S2` che ricorda a
  quale controllo fisico corrispondono. `FX` silenzia l'effetto attivo,
  qualunque sia — l'etichetta diceva `ARMONIA` finché l'armonizzatore era
  l'unico effetto.
- **STOP**: riga a sé, più piccolo, quadrato rosso, etichetta `hold S2`.
- **TAP**: occupa tutto lo spazio rimanente in basso, col BPM corrente.
- **Sezione di collegamento**: visibile finché lo strumento non è connesso,
  poi sparisce.

La vista principale ha **due vicine**: la scaletta a sinistra e gli effetti a
destra. Si raggiungono trascinando il dito — verso destra la scaletta, verso
sinistra gli effetti — oppure dai pulsanti della riga di navigazione.

### 7.2 Selettore a circolo delle quinte

A tutto schermo, due corone concentriche: esterna maggiori, interna relative
minori, allineate. Maggiore e relativa minore condividono la posizione angolare,
quindi **il raggio da solo sceglie il modo**: un unico trascinamento seleziona
fondamentale e modo. Scorrendo il dito il blocco si evidenzia e si ingrandisce
sporgendo dalla corona; si conferma al rilascio.

Sotto, due strisce compatte: **qualità** e **scala**, con le più probabili in
evidenza. Il default della scala dipende dalla qualità; cambiando qualità la
scala si sposta solo se quella corrente non è più compatibile.

Sui settori adiacenti stanno gli accordi a distanza di quinta, quindi le
progressioni reali occupano spicchi vicini: un blues in Bb vive in `Eb Bb F`,
tre settori confinanti.

### 7.3 Progressione

Elenco dei passi con posizione d'inizio, accordo, scala e **durata**. Tocco per
editare, `✕` per eliminare, `+` per aggiungere.

### 7.2.1 Navigazione

Non ci sono pulsanti «indietro»: si torna col **tasto indietro di Android**.
Dalla progressione, dalla scaletta, dagli effetti e dalla schermata test si
rientra nella vista principale; dal selettore si torna da dove è stato aperto — progressione se ci si
è arrivati dalla lista, vista principale se dalla pressione lunga sul carosello.
Dalla principale il tasto indietro esce dall'app.

Selezionando un brano nella scaletta, l'evidenziazione resta visibile per un
istante e poi si torna da soli alla vista principale: serve a vedere che la
scelta è stata registrata.

### 7.3.1 Durate: la risoluzione è il quarto di battuta

La durata di un accordo si misura in **quarti di battuta**, non in battute: il
minimo è `¼`, e si sale di un quarto alla volta fino a 16 battute. Serve per le
progressioni dove un accordo dura mezza battuta o tre quarti — un turnaround
serrato, un accordo di passaggio.

Il modello lo riflette dappertutto, non solo nell'interfaccia: `ChordStep.quarti`
è l'unità di base, `Transport.quarto()` avanza a quella risoluzione,
`Progression.totalQuarti` e `stepIndexAt()` ragionano in quarti.
`ChordStep.battute()` resta come comodità per scrivere durate intere.

**Come si leggono nel carosello.** Ogni scheda porta i quadratini dei quarti,
raggruppati per **battuta** secondo il tempo del pezzo — stacco più largo ogni 3,
4 o 5 — e **mandati a capo ogni due battute**. Un accordo da quattro battute in
4/4 mostra quindi due file da otto, la seconda sotto la prima. I quadratini
accesi sono i quarti già trascorsi. Oltre le quattro file si passa a un
quadratino per battuta, altrimenti non ci starebbero.

### 7.3.2 Progressione predefinita

**IV-II-V-I in Do**, una battuta per accordo, con il **modo relativo** di
ciascun grado come scala: F lidia, D- dorica, G7 misolidia, C ionica. Sono tutte
la scala di Do maggiore lette da gradi diversi — verificato: i quattro insiemi di
classi coincidono. Le voci di armonia restano quindi dentro la tonalita' e si
muovono di poco, che e' proprio la condizione in cui si sente se il
meccanismo funziona.

### 7.3.3 Il tempo del pezzo

Sono ammessi **3/4, 4/4 e 5/4**. Il tempo si sceglie in cima alla vista di
configurazione della progressione ed è **memorizzato per ogni brano** della
scaletta; compare nella riga di stato accanto al BPM.

Il tempo governa tre cose: quanti quarti fanno una battuta, quindi quali durate
sub-battuta esistono — in 3/4 solo `1/4` e `2/4`, in 4/4 fino a `3/4`, in 5/4
fino a `4/4`; il raggruppamento dei quadratini nel carosello; e l'avanzamento del
transport.

Il denominatore della frazione resta sempre 4, perché **un quarto è un quarto in
qualsiasi tempo**: cambia solo quanti ne servono per fare una battuta. Nel
transport il quarto è il movimento (`60/bpm`) e la battuta è `movimenti × quarto`
— non un quarto di battuta, che coinciderebbe col movimento solo in 4/4.

### 7.4 Schermata test

Procedura guidata e **monitor MIDI grezzo**, separati dalla vista principale.
Il monitor mostra i messaggi in ingresso (`ch1 90 72 100`) e gli esiti del
wizard: durante l'esecuzione sarebbe un fiume, perché il fiato genera CC in
continuazione, quindi sta qui e non nella schermata che guardi suonando.

---

## 8. Scaletta e richiamo della scena

Ogni brano porta nome, progressione, BPM e — opzionalmente — la **scena
dell'AE-20**. Selezionandolo si caricano tutti e tre e si invia il Bank Select +
Program Change: un tocco e hai progressione, tempo e suono.

La scaletta è salvata nelle preferenze e sopravvive al riavvio.

⚠️ Le impostazioni delle parti di armonia (MONO, Legato, Retrigger Off, Unison
Off) sono **per scena**: ogni scena richiamata dalla scaletta va preparata di
conseguenza con Aerophone Pro Editor.

---

## 9. Struttura del codice

```
harmonizer/
  core/src/                    modulo puro, collaudabile su JVM
    Harmony.kt                 grafia, accordi, AllowedNotes, HarmonyEngine, Progression
    Scales.kt                  catalogo scale e compatibilità
    Demo.kt                    banco di verifica musicale
    DemoEstranee.kt            confronto fra i due metodi di trasposizione
    DemoScale.kt               effetto della scelta della scala
    DemoFx.kt                  banco di verifica degli effetti (§14)
    Bench.kt                   misura del costo per nota
  android/
    app/src/main/java/harmonizer/
      core/                    copia del modulo puro
      app/
        Harmonizer.kt          runtime: voci, arpeggio, mute, stop  (no Android)
        Fx.kt                  configurazioni degli effetti, sequenza dell'arpeggio  (no Android)
        Runtime.kt             VoiceTracker, Transport, TapTempo, AllowedCache  (no Android)
        Midi.kt                MidiManager, ricezione, invio, richiamo scena
        Clock.kt               clock degli effetti, thread a priorità audio
        MainActivity.kt        schermate e procedura guidata
        Widgets.kt             SymbolButton, ChordCarousel
        Ui.kt                  Strip, ChordPicker, ProgressionView
        FxView.kt              vista degli effetti, selettore verticale dei gradi
        Scorrimento.kt         gesto di cambio vista, bidirezionale
        CircleOfFifthsView.kt  selettore circolare
        Setlist.kt             modello, persistenza, schermata
        HarmService.kt         servizio in primo piano e wake lock
```

### 9.1 Il modulo che decide se funziona

`VoiceTracker` — mappa nota-in-ingresso → note emesse, con **slot separati per
voce**: il mute agisce sulle voci di armonia, la melodia resta. Sulle linee
legato l'AE-20 manda note sovrapposte: senza questa mappa i note-off finiscono
sulla nota sbagliata e restano note appese.

### 9.2 Prestazioni

Misurato, tre voci con voice leading su tre gradi:

```
per nota            0,106 microsecondi
cambio di accordo   1,134 microsecondi   (qualche volta al minuto)
```

Un evento MIDI ha un budget di qualche **millisecondo**: il core sta quattro
ordini di grandezza sotto. Non è lui la sorgente di latenza.

**Ma su Android nulla può garantire una latenza**, perché non è un sistema
real-time: restano scheduler, garbage collector e trasporto USB. Quello che si
può fare è non aggiungerne di evitabile:

- percorso caldo **senza allocazioni**: `AllowedCache` precalcola all'avvio i
  12 × 19 insiemi di note ammesse, quindi il cambio d'accordo è una lettura da
  tabella; `VoiceTracker` usa array a dimensione fissa; il buffer MIDI in uscita
  è preallocato;
- thread MIDI a `THREAD_PRIORITY_URGENT_AUDIO`;
- nessun lock né I/O bloccante nel percorso della nota;
- diradamento dei CC: un valore si invia solo se cambiato e non più di una volta
  ogni 5 ms, per canale e per numero.

Il tempo di elaborazione è mostrato nell'app, così si verifica invece di fidarsi.

### 9.3 Affidabilità dal vivo

`HarmService` è un **servizio in primo piano** con notifica persistente e
**wake lock**. Senza, Android decide a metà pezzo che l'app non è importante e
taglia le note. Conta più della latenza.

Va inoltre esclusa l'app dall'ottimizzazione batteria nelle impostazioni di
sistema.

---

## 10. Configurazione dell'AE-20

### 10.1 Menu dello strumento

| Parametro | Valore | Perché |
|---|---|---|
| switch `MIDI CONTROL` | **ON** | abilita la trasmissione MIDI |
| `MIDI Ctrl Sound` | **On** per i test, poi **Off** | vedi test 1 |
| `MIDI Ctrl PC` / `MIDI Ctrl BS` | Off | evita cambi di scena involontari |
| `Tx Channel` | **16** | fuori da 1–5: un eventuale rientro non triggera parti |
| `MIDI Speed` | 1 | latenza minima |
| `MIDI Velocity` | Tongued | velocity espressiva |
| `Hold Mode` | **Off** | altrimenti le note restano appese |
| `Bite Ctrl Mode` | Sax o E-Wind | genera il bend da inoltrare |

In `MENU ▸ MIDI`, strato MIDI degli Assign:

- `Breath_1` → **CC11 (Expression)**
- `Breath_2` → **CC2 (Breath)** — entrambi, così l'app sceglie quello che funziona
- `BiteDn_1` → **Bend Down** · `BiteUp_1` → **Bend Up**
- `S1_1` → **CC 86**, Momentary, Release 0 / Press 127 → mute
- `S2_1` → **CC 87**, Momentary, Release 0 / Press 127 → riallineamento e stop

In `MENU ▸ System`: `Asgn Src Breath`, `Bite`, `Lever`, `S1/S2` su **System**.

### 10.2 Scena, con Aerophone Pro Editor

Per **ogni** scena usata dal vivo:

- `Part 1` — melodia. `Coarse Tune` e `Fine Tune` a **0**.
- `Part 2`, `Part 3` (e `Part 4`) — voci di armonia:
  `PartSW` On · `Mono/Poly` **MONO** · `Legato Switch` **ON** ·
  `Unison Switch` **Off** · `Coarse`/`Fine Tune` 0
- Sul tone delle parti di armonia: `Legato Retrigger Interval` **OFF**
- Salvare come scena utente e annotarne banco e numero per la scaletta.

`Legato Retrigger Interval = OFF` serve all'armonia e **non disturba
l'arpeggiatore**: l'arpeggio manda il note-off prima del note-on successivo,
quindi ogni nota riattacca comunque. Sono i due comportamenti opposti dello
stesso parametro, ottenuti dall'app scegliendo l'ordine dei messaggi.

---

## 11. La procedura guidata

Non chiede di sapere cosa testare: manda lei i messaggi, fa domande a risposta
sì/no e ricava da sola la configurazione. Sette passi:

1. **Test 1a** — `MIDI Ctrl Sound = ON`, nota su ch1. La senti?
2. **Test 1b** — `MIDI Ctrl Sound = OFF`, stessa nota. La senti ancora?
   → **decide l'intera architettura**: se sì, "off" è un local off e la topologia
   del §3 è valida; se no, il motore è muto del tutto e serve quella alternativa.
3. **Test 2** — nota senza espressione.
4. **Test 2b** — nota preceduta da `CC11 = 100`.
5. **Test 2c** — nota preceduta da `CC2 = 100`.
   → stabiliscono se serve lo specchio dell'espressione e su quale CC.
6. **Test 3** — la stessa nota su ch1…ch5 → mappa canali e parti.
7. **Test 4** — due note sovrapposte sulla parte di armonia. Nuovo attacco?
   → decide fra legato (§5 via primaria) e pitch bend (via alternativa).

Alla fine l'app stampa nel monitor la configurazione ricavata.

---

## 12. Stato e cosa manca

**Fatto**: motore di armonia verificato musicalmente su JVM; catalogo scale con
selezione per accordo; grafia sul circolo delle quinte; app Android che compila e
gira; livello MIDI con ricezione, invio e richiamo scena; macchine a stati;
carosello; selettore circolare con strisce; editor progressione; scaletta con
persistenza; servizio in primo piano; procedura guidata; **vista degli effetti**
con armonizzatore configurabile e arpeggiatore, verificati su JVM (§14).

**Manca**: tutto ciò che richiede l'Aerophone in mano — cioè la verifica delle
quattro incognite, la taratura di `maxVoices`, e la scelta fra legato e bend.

**Non implementato per scelta**: pilotaggio SysEx dell'armonia nativa (§1),
`minDurationGate` e `breathGate` esistono nella configurazione ma non hanno
ancora un'interfaccia.

### 12.1 Permessi e comportamento di sistema

`POST_NOTIFICATIONS` è dichiarato **e richiesto a runtime**: da Android 13
dichiararlo non basta, e senza il permesso la notifica del servizio in primo
piano non compare, rendendolo più fragile — proprio la cosa che deve reggere
durante un pezzo.

Il **MIDI non richiede permessi**: con `MidiManager` è il servizio di sistema a
possedere il dispositivo USB, quindi nessun dialogo di autorizzazione.

`android.software.midi` è dichiarata `required="false"` e verificata a runtime,
così l'app resta installabile dove il flag non è dichiarato. C'è un filtro USB
(`res/xml/usb_midi.xml`) che riconosce la classe Audio con sottoclasse
MIDIStreaming, per l'apertura automatica al collegamento — **[DA VERIFICARE]**,
il comportamento varia fra produttori.

### 12.2 Rotazione a testa in giù

Il telefono sul leggio sta capovolto, con la USB verso l'alto. L'activity usa
`screenOrientation="sensorPortrait"`: ruota fra verticale normale e capovolto
seguendo il sensore, e **funziona anche con la rotazione automatica bloccata**
nelle impostazioni di sistema.

`configChanges` copre orientamento, dimensioni e layout, quindi al ribaltamento
l'activity **non viene ricreata**: la connessione MIDI e lo stato del transport
sopravvivono. Senza, ogni giro avrebbe scollegato lo strumento a metà pezzo.

---

## 13. Accessorio opzionale

Il footswitch **BOSS FS-1-WL** si accoppia all'AE-20 in Bluetooth MIDI — lo
strumento mostra "MIDI Online" — e può accoppiarsi **contemporaneamente** a
strumento e telefono, presentandosi come `FS-1-WL+`. Non serve: S1 e S2 bastano.
Diventa interessante per spostare mute e riallineamento sotto il piede.

---

## 14. Gli effetti

L'armonizzatore non è più l'unico effetto, e i suoi parametri non sono più
scritti nel codice. La **vista degli effetti** sta a destra della principale,
speculare alla scaletta che sta a sinistra: si raggiunge trascinando il dito
verso sinistra o col pulsante `fx`.

In cima si sceglie l'effetto — `harmonizer` o `arpeggiator` — e sotto compaiono i
parametri di quello scelto. Non c'è un «conferma»: ogni tocco applica subito e
salva nel brano, perché qui si regola mentre si prova a suonare.

I pannelli si costruiscono **una volta sola**: toccare un chip ridipinge i chip
di quella striscia e nient'altro. Ricostruire il pannello — che è la scorciatoia
naturale, e il primo modo in cui era scritto — riportava lo scorrimento in cima
a ogni tocco, cioè rendeva inusabile il pannello dell'arpeggiatore, che è più
alto dello schermo.

### 14.1 Uno alla volta, entrambi memorizzati

**Attivo uno solo.** Due catene in serie raddoppierebbero il lavoro sul percorso
della nota, che è l'unica cosa su cui l'app può ancora peggiorare la latenza (le
altre — scheduler, GC, USB — non le governa).

**Memorizzati tutti e due.** Ogni brano della scaletta porta l'effetto attivo
*e* la configurazione di entrambi: passare da armonizzatore ad arpeggiatore e
tornare indietro non perde niente. Anche i gradi delle voci spente restano:
`HarmonizerCfg.gradi` conserva sempre quattro valori, si usano i primi `voci`.
Formato delle preferenze: `v5`.

### 14.2 Armonizzatore: i parametri che prima erano nel codice

| Parametro | Valori |
|---|---|
| **voci** | 1–4 voci **di armonia**, una parte per voce dal canale 2 |
| **movimento** | grado fisso · casuale · casuale alternato · movimento minimo |
| **grado** | a grado fisso uno per voce; negli altri modi un intervallo **per voce** |

Le voci si **aggiungono** alla melodia, che continua a passare sul canale 1: con
una voce in uscita suonano due note. Non è un dettaglio di conteggio — con
`MIDI Ctrl Sound = Off` la melodia esiste solo perché l'app la ri-emette (§3), e
né il mute né lo stop la toccano (§6.3).

I nomi dei modi sono scritti per essere capiti **mentre si scelgono**, e sotto
la striscia compare la spiegazione di quello selezionato — «guida voci» non
dice nulla a chi non conosce il termine inglese. Le spiegazioni stanno in
`MODI_HARM`, accanto ai nomi, non in questo documento: chi regola l'app non ce
l'ha in mano.

| Modo | Cosa fa |
|---|---|
| **grado fisso** | ogni voce tiene sempre il proprio grado: l'armonia è parallela alla melodia, e cambia qualità secondo l'accordo |
| **casuale** | a ogni nota ogni voce pesca un grado nell'intervallo; può ripetere quello di prima, quindi qualche nota resta ferma |
| **casuale alternato** | come casuale, ma una voce non ripesca il grado appena usato: l'intervallo cambia per forza (`RANDOM_NO_REPEAT`) |
| **movimento minimo** | ogni voce prende il grado la cui nota è più vicina a quella che ha appena suonato: è il voice leading (§4.4), il modo consigliato |

**Il grado si sceglie su un selettore verticale**, una colonna per voce col
numero in cima, che cambia forma perché le due domande sono diverse:

- a **grado fisso** una maniglia per voce, col proprio grado;
- negli **altri modi** una **barra** per voce: gli estremi entro cui *quella*
  voce può muoversi. Si trascinano i due capi, oppure la barra intera per
  spostare l'intervallo senza cambiarne la larghezza.

Verticale perché è un'altezza — in alto suona in alto — e una colonna per voce,
così due voci sullo stesso grado non si nascondono a vicenda: si vede l'accordo
che si sta impilando.

⚠️ **Un intervallo per voce, e non uno in comune.** Prima era uno globale, e
prima ancora era l'unione dei gradi delle voci: due errori in fila, ciascuno
imparato dall'uso. L'unione legava l'insieme alle voci, e con una voce sola
l'insieme aveva **un** elemento — «casuale» non poteva muovere niente. L'unico
intervallo condiviso muoveva sì, ma tutte le voci nello stesso spazio, quindi
pescavano le stesse note e si accavallavano. Con un intervallo per voce si
decide che la prima sta fra 2a e 3a e la seconda fra 4a e 5a: restano distinte
per costruzione, e si controlla *quanto* ciascuna può muoversi. Le tre domande —
quante voci, dove sta ognuna, quanto può muoversi — sono indipendenti, e ora
hanno tre controlli indipendenti.

**Il gesto si prende solo se il dito parte vicino a una maniglia** o dentro una
barra; altrove il tocco passa alla vista sotto, che lo usa per cambiare
schermata. Prima il selettore agganciava il valore alla riga sfiorata, quindi un
tocco di passaggio spostava un grado e uno scorrimento laterale cambiava
l'armonia invece di cambiare vista — su un leggio, un parametro che cambia da
solo.

**L'unisono dentro l'intervallo viene saltato**: una voce all'unisono con la
melodia è una voce che sparisce, e come esito di un tiro casuale suona come un
buco. Resta solo se l'intervallo è esattamente l'unisono, che è una scelta
esplicita. Nel selettore il grado saltato si vede, in grigio spento.

Come i gradi diventano insiemi per il motore (§4.4):

- **grado fisso** → ogni voce riceve un `degreeSet` di un solo elemento, il suo;
- **gli altri modi** → ogni voce riceve l'insieme del **proprio** intervallo, e
  ne pesca un grado per nota secondo il modo.

L'insieme è comunque **ruotato per voce**. Serve quando due voci condividono lo
stesso intervallo: col movimento minimo il primo candidato sarebbe lo stesso per
entrambe — `set[size/2]` quando non c'è una nota precedente — e partirebbero
all'unisono. Con la rotazione partono separate, e da lì il movimento minimo le
tiene separate, perché ognuna minimizza il movimento rispetto alla *propria*
nota precedente. Con intervalli disgiunti le voci non si incrociano affatto.

### 14.3 Arpeggiatore

Vale **tutta** l'architettura di prima. Cambia solo cosa esce: non le voci
armonizzate a partire dalla melodia, ma un arpeggio che parte dalla nota
suonata.

**Segue l'armonia da solo.** L'arpeggio sale sulle note ammesse dal passo
corrente della progressione — i chord tone, oppure tutta la scala se si vuole —
e la nota suonata dice solo da dove partire (se è estranea, si aggancia alla più
vicina). Non c'è una riga di logica sull'accordo: cambia il passo, cambiano le
note.

**Sta sulla griglia del transport, non sulla nota.** Quindi:
`BATT. 1` riallinea anche l'arpeggio, `STOP` lo ferma, il tasto `FX` lo silenzia
lasciando scorrere la progressione, il BPM è quello del brano. A transport fermo
l'arpeggio **tace** — non c'è griglia — mentre la melodia passa comunque, come
sempre (§6.3).

| Parametro | Valori |
|---|---|
| **direzione** | su · giù · su-giù · giù-su · casuale |
| **articolazione** | 1/4 · 1/8 · **terzine** · 1/16 · **quintine** · **sestine** · **settimine** · 1/32 · **terzine di 1/16** |
| **estensione** | 1–4 ottave |
| **note** | dell'accordo (arpeggio) · tutta la scala (scala corsa) |
| **gate** | 10–100% dello step: è la durata della nota |
| **swing** | 0–50%: ritardo degli step dispari |

L'articolazione si esprime in **note per quarto**: 3 sono le terzine di ottavi,
5 le quintine, 6 le sestine, 7 i gruppi di sette, 12 le terzine di sedicesimi.
Il quarto è il movimento in qualsiasi tempo (§7.3.3), quindi la suddivisione non
dipende da 3/4, 4/4 o 5/4.

Su `su-giù` e `giù-su` **gli estremi non si ripetono**: su tre note fa
`1-2-3-2`, non `1-2-3-3-2-1`.

**Riattacco.** L'arpeggio manda il note-off **prima** del note-on successivo.
Con `Legato Retrigger Interval = OFF` — che serve all'armonia per spostarsi
senza riattaccare (§5) — due note sovrapposte cambierebbero solo l'intonazione:
un arpeggio senza attacchi. Lo stesso parametro dà i due comportamenti opposti,
e a scegliere è l'ordine dei messaggi.

**Priorità all'ultima nota.** L'arpeggio parte dall'ultima nota ricevuta; al suo
note-off passa a un'altra nota ancora tenuta, se c'è, altrimenti tace.

### 14.4 Il clock

L'armonizzatore si accontentava del tick dell'interfaccia a 40 ms: doveva solo
accorgersi dei cambi di accordo. L'arpeggiatore no — a 120 bpm in sestine uno
step dura 21 ms, e a 40 ms di risoluzione la sestina non esisterebbe.

`Clock.kt` è quindi un thread proprio a `THREAD_PRIORITY_URGENT_AUDIO`, come
quello del MIDI, che chiama `Harmonizer.tick()`. Il periodo si adatta:
**2 ms** con l'arpeggiatore, **20 ms** con l'armonizzatore — cinquecento
risvegli al secondo per stare a guardare la battuta sarebbero batteria buttata.
Misurato sul telefono: 14% di un core con l'arpeggiatore, 3% con
l'armonizzatore.

Si pianifica su **tempi assoluti** e non a intervalli, altrimenti l'errore di
ogni giro si sommerebbe; se si accumula ritardo non si rincorre, si riparte da
ora — una pausa del sistema fa **saltare** gli step mancati, non sparare una
raffica di recupero. Il `Runnable` è uno solo e si ripianifica da sé: nel
percorso caldo non si allocano nemmeno i risvegli.

### 14.5 Cosa è verificato

`core/src/DemoFx.kt` è il banco degli effetti, su JVM senza strumento. Il tempo
è un parametro e non l'orologio: le prove chiamano `tick()` agli istanti che
vogliono, quindi si verificano in un istante cose che dal vivo richiederebbero
minuti.

Verificato: le voci multiple sui gradi scelti e il loro spostamento al cambio
d'accordo conservando il grado; la rotazione dell'insieme che separa le voci;
che ogni voce riceva il **proprio** intervallo e che due voci con fasce
disgiunte non si sovrappongano mai in duecento attacchi casuali; che
l'intervallo **non** dipenda dal numero di voci — una voce sola in modo casuale
produce quattro note diverse su un intervallo di quattro gradi — e che l'unisono
venga saltato dentro un intervallo più largo; le
sestine (12 note in un secondo a 120 bpm) e lo swing al millisecondo; il
riattacco con note-off prima del note-on e nessuna nota appesa; l'arpeggio che
segue la progressione; il mute che silenzia lasciando scorrere; stop e
riallineamento; il cambio di effetto a caldo senza note appese; un clock
irregolare con jitter 0–4 ms che non perde uno step su sessanta; una pausa di
sistema di 500 ms che produce **una** nota e non una raffica.

**Non verificato**, perché richiede lo strumento: che il riattacco suoni come un
riattacco, la latenza reale sul percorso USB, e quanto in alto si possa spingere
l'articolazione prima che l'AE-20 o il telefono non stiano più dietro.
