import CoreMIDI
import Foundation

// Stessi messaggi della procedura guidata di MainActivity.kt, verso "AE-20 MIDI IN1".
var client = MIDIClientRef(); MIDIClientCreate("prove" as CFString, nil, nil, &client)
var porta = MIDIPortRef(); MIDIOutputPortCreate(client, "out" as CFString, &porta)
func nome(_ e: MIDIEndpointRef) -> String { var s: Unmanaged<CFString>?; MIDIObjectGetStringProperty(e, kMIDIPropertyDisplayName, &s); return (s?.takeRetainedValue() as String?) ?? "" }
let dest = (0..<MIDIGetNumberOfDestinations()).map { MIDIGetDestination($0) }.first { nome($0) == "AE-20 MIDI IN1" }!

func invia(_ b: [UInt8]) {
    var lista = MIDIPacketList(); let p = MIDIPacketListInit(&lista)
    _ = MIDIPacketListAdd(&lista, 1024, p, 0, b.count, b)
    MIDISend(porta, dest, &lista)
    print(b.map { String(format: "%02X", $0) }.joined(separator: " "))
}
func cc(_ ch: Int, _ n: Int, _ v: Int) { invia([UInt8(0xB0 + ch - 1), UInt8(n), UInt8(v)]) }
func on(_ ch: Int, _ n: Int) { invia([UInt8(0x90 + ch - 1), UInt8(n), 100]) }
func off(_ ch: Int, _ n: Int) { invia([UInt8(0x80 + ch - 1), UInt8(n), 0]) }
func attendi(_ ms: Int) { usleep(useconds_t(ms * 1000)) }

let a = CommandLine.arguments
switch a[1] {
case "uno":     // uno <canale>: CC11 e CC2 a 100, poi Do5
    let ch = Int(a[2])!; cc(ch, 11, 100); cc(ch, 2, 100); on(ch, 72); attendi(1200); off(ch, 72)
case "nota":    // nota <canale> [cc valore]
    let ch = Int(a[2])!
    if a.count > 4 { cc(ch, Int(a[3])!, Int(a[4])!) }
    on(ch, 72); attendi(1200); off(ch, 72)
case "canali":
    for c in 1...5 { print("canale \(c)"); cc(c, 11, 100); cc(c, 2, 100); on(c, 72); attendi(600); off(c, 72); attendi(200) }
case "legato":
    let ch = 2
    cc(ch, 11, 100); cc(ch, 2, 100); on(ch, 72); attendi(900); on(ch, 74); attendi(50); off(ch, 72); attendi(950); off(ch, 74)
case "poly":
    let ch = 2, note = [60, 64, 67, 71]
    cc(ch, 127, 0); cc(ch, 11, 100); cc(ch, 2, 100); for n in note { on(ch, n) }; attendi(1500); for n in note { off(ch, n) }
case "voicing":
    let note = [72, 69, 65, 62, 60, 57, 53, 50]
    for (k, n) in note.enumerated() { let ch = 2 + k % 4; cc(ch, 127, 0); cc(ch, 11, 100); cc(ch, 2, 100); on(ch, n) }
    attendi(2000)
    for (k, n) in note.enumerated() { off(2 + k % 4, n) }
case "accordo": // quattro note sul canale 2 senza CC127
    let ch = 2, note = [60, 64, 67, 71]
    cc(ch, 11, 100); cc(ch, 2, 100); for n in note { on(ch, n) }; attendi(1500); for n in note { off(ch, n) }
case "scala":  // sei note di voicing che entrano una al secondo, due per parte su ch2-4, tenute
    let note = [72, 69, 65, 62, 60, 57]
    for c in 2...4 { cc(c, 127, 0); cc(c, 11, 100); cc(c, 2, 100) }
    for (k, n) in note.enumerated() { print("entra la nota \(k + 1)"); on(2 + k % 3, n); attendi(1000) }
    attendi(1500)
    for (k, n) in note.enumerated() { off(2 + k % 3, n) }
case "mono":
    for c in 2...5 { cc(c, 126, 0) }
case "panic":
    for c in 1...16 { cc(c, 123, 0) }
default: print("?")
}
attendi(100)
