import CoreMIDI
import Foundation
// Stampa per N secondi i messaggi che arrivano da "AE-20 MIDI OUT1" (e OUT2), saltando i CC continui ripetuti.
var client = MIDIClientRef(); MIDIClientCreate("ascolta" as CFString, nil, nil, &client)
func nome(_ e: MIDIEndpointRef) -> String { var s: Unmanaged<CFString>?; MIDIObjectGetStringProperty(e, kMIDIPropertyDisplayName, &s); return (s?.takeRetainedValue() as String?) ?? "" }
var conteggio = [String: Int]()
var inPort = MIDIPortRef()
MIDIInputPortCreateWithProtocol(client, "in" as CFString, ._1_0, &inPort) { lista, src in
    for p in lista.unsafeSequence() {
        let w = p.pointee.words.0
        let st = (w >> 16) & 0xFF, d1 = (w >> 8) & 0xFF, d2 = w & 0xFF
        if st < 0x80 { continue }
        let tipo = st & 0xF0, ch = (st & 0x0F) + 1
        let k = String(format: "%02X", tipo) + " ch\(ch)" + (tipo == 0xB0 ? " CC\(d1)" : "")
        conteggio[k, default: 0] += 1
        if tipo == 0x90 || tipo == 0x80 || conteggio[k]! <= 2 {
            print(String(format: "ch%2d  %02X %3d %3d", ch, tipo, d1, d2)); fflush(stdout)
        }
    }
}
for i in 0..<MIDIGetNumberOfSources() { let s = MIDIGetSource(i); if nome(s).hasPrefix("AE-20") { MIDIPortConnectSource(inPort, s, nil) } }
RunLoop.main.run(until: Date().addingTimeInterval(Double(CommandLine.arguments[1])!))
print("riepilogo:"); for (k, v) in conteggio.sorted(by: { $0.key < $1.key }) { print("  \(k): \(v)") }
