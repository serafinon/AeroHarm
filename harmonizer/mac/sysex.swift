import CoreMIDI
import Foundation
// sysex leggi <a b c d> <size>   -> manda RQ1 e stampa il DT1 di risposta
// sysex scrivi <a b c d> <v...>  -> manda DT1
var client = MIDIClientRef(); MIDIClientCreate("sysex" as CFString, nil, nil, &client)
func nome(_ e: MIDIEndpointRef) -> String { var s: Unmanaged<CFString>?; MIDIObjectGetStringProperty(e, kMIDIPropertyDisplayName, &s); return (s?.takeRetainedValue() as String?) ?? "" }
let dest = (0..<MIDIGetNumberOfDestinations()).map { MIDIGetDestination($0) }.first { nome($0) == "AE-20 MIDI IN1" }!
var outPort = MIDIPortRef(); MIDIOutputPortCreate(client, "out" as CFString, &outPort)
var inPort = MIDIPortRef()
MIDIInputPortCreate(client, "in" as CFString, { lista, _, _ in
    var p = lista.pointee.packet
    for _ in 0..<lista.pointee.numPackets {
        let b = withUnsafeBytes(of: p.data) { Array($0.prefix(Int(p.length))) }
        if b.first == 0xF0 || (b.first ?? 0) < 0x80 { print("ricevuto:", b.map { String(format: "%02X", $0) }.joined(separator: " ")) }
        p = MIDIPacketNext(&p).pointee
    }
}, nil, &inPort)
for i in 0..<MIDIGetNumberOfSources() { let s = MIDIGetSource(i); if nome(s) == "AE-20 MIDI OUT1" { MIDIPortConnectSource(inPort, s, nil) } }
func hex(_ s: String) -> UInt8 { UInt8(s, radix: 16)! }
let a = CommandLine.arguments
let addr = (2...5).map { hex(a[$0]) }
let dati: [UInt8] = a[1] == "leggi" ? [0, 0, 0, hex(a[6])] : a[6...].map { hex($0) }
let corpo = addr + dati
let sum = UInt8((128 - corpo.reduce(0) { ($0 + Int($1)) } % 128) % 128)
let msg: [UInt8] = [0xF0, 0x41, 0x10, 0x00, 0x00, 0x00, 0x7D, a[1] == "leggi" ? 0x11 : 0x12] + corpo + [sum, 0xF7]
print("inviato: ", msg.map { String(format: "%02X", $0) }.joined(separator: " "))
var lista = MIDIPacketList(); let p = MIDIPacketListInit(&lista)
_ = MIDIPacketListAdd(&lista, 1024, p, 0, msg.count, msg)
MIDISend(outPort, dest, &lista)
RunLoop.main.run(until: Date().addingTimeInterval(1.0))
