import CoreMIDI
import Foundation
// copia <dstHi> <dstLo> [verifica]
// Copia la Temporary Scene (01 00 xx xx) nella scena utente (dstHi dstLo xx xx), blocco per blocco.
// Con "verifica" non scrive: rilegge sorgente e destinazione e confronta.
var client = MIDIClientRef(); MIDIClientCreate("copia" as CFString, nil, nil, &client)
func nome(_ e: MIDIEndpointRef) -> String { var s: Unmanaged<CFString>?; MIDIObjectGetStringProperty(e, kMIDIPropertyDisplayName, &s); return (s?.takeRetainedValue() as String?) ?? "" }
let dest = (0..<MIDIGetNumberOfDestinations()).map { MIDIGetDestination($0) }.first { nome($0) == "AE-20 MIDI IN1" }!
var outPort = MIDIPortRef(); MIDIOutputPortCreate(client, "out" as CFString, &outPort)
let lock = NSLock()
var buffer: [UInt8] = []; var messaggi: [[UInt8]] = []
var inPort = MIDIPortRef()
MIDIInputPortCreate(client, "in" as CFString, { lista, _, _ in
    var p = lista.pointee.packet
    for _ in 0..<lista.pointee.numPackets {
        let b = withUnsafeBytes(of: p.data) { Array($0.prefix(Int(p.length))) }
        lock.lock()
        for x in b {
            if x == 0xF0 { buffer = [x] }
            else if x == 0xF7 { if !buffer.isEmpty { buffer.append(x); messaggi.append(buffer); buffer = [] } }
            else if x >= 0xF8 { }
            else if x >= 0x80 { buffer = [] }
            else if !buffer.isEmpty { buffer.append(x) }
        }
        lock.unlock()
        p = MIDIPacketNext(&p).pointee
    }
}, nil, &inPort)
for i in 0..<MIDIGetNumberOfSources() { let s = MIDIGetSource(i); if nome(s) == "AE-20 MIDI OUT1" { MIDIPortConnectSource(inPort, s, nil) } }

func invia(_ m: [UInt8]) {
    var lista = MIDIPacketList(); let p = MIDIPacketListInit(&lista)
    _ = MIDIPacketListAdd(&lista, 1024, p, 0, m.count, m)
    MIDISend(outPort, dest, &lista)
}
func roland(_ cmd: UInt8, _ corpo: [UInt8]) -> [UInt8] {
    let sum = UInt8((128 - corpo.reduce(0) { $0 + Int($1) } % 128) % 128)
    return [0xF0, 0x41, 0x10, 0, 0, 0, 0x7D, cmd] + corpo + [sum, 0xF7]
}
func a7(_ n: Int) -> [UInt8] { [UInt8((n >> 21) & 0x7F), UInt8((n >> 14) & 0x7F), UInt8((n >> 7) & 0x7F), UInt8(n & 0x7F)] }
func n7(_ b: ArraySlice<UInt8>) -> Int { b.reduce(0) { $0 << 7 | Int($1) } }

// Legge un blocco: restituisce i byte di dati, ricomposti per indirizzo.
func leggi(_ base: Int, _ size: Int) -> [UInt8]? {
    lock.lock(); messaggi = []; lock.unlock()
    invia(roland(0x11, a7(base) + a7(size)))
    var dati = [UInt8?](repeating: nil, count: size)
    let fine = Date().addingTimeInterval(1.5)
    while Date() < fine {
        RunLoop.current.run(until: Date().addingTimeInterval(0.02))
        lock.lock(); let ms = messaggi; lock.unlock()
        for m in ms where m.count > 14 && m[7] == 0x12 {
            let ad = n7(m[8..<12]); let d = Array(m[12..<(m.count - 2)])
            for (k, x) in d.enumerated() { let off = ad + k - base; if off >= 0 && off < size { dati[off] = x } }
        }
        if !dati.contains(where: { $0 == nil }) { return dati.map { $0! } }
    }
    return nil
}

// blocchi della scena: (offset a 7 bit dentro la scena, dimensione)
var blocchi: [(Int, Int)] = [(0x0000, 0x5A)]
for a in [0x01,0x02,0x03,0x04,0x05,0x06,0x07,0x08,0x09,0x0A,0x0F,0x10,0x12,0x13,0x14,0x15,0x16,0x17,0x18,0x19,0x1A,0x1B,0x1C,0x21,0x22,0x24] { blocchi.append((a << 7, 0x20)) }
for a in [0x0D,0x0E,0x11,0x1F,0x20,0x23] { blocchi.append((a << 7, 0x40)) }
for a in [0x0B,0x1D] { blocchi.append((a << 7, 0x80)) }
for p in 0..<5 { blocchi.append(((0x30 + p) << 7, 0x65)); blocchi.append(((0x40 + p) << 7, 0x09)); blocchi.append(((0x50 + 2*p) << 7, 0x90)) }
blocchi += [(0x60 << 7, 0x2D), (0x61 << 7, 0x2D), (0x70 << 7, 0x54), (0x71 << 7, 0x54), (0x72 << 7, 0x53), (0x73 << 7, 0x90)]

let a = CommandLine.arguments
let src = 0x01 << 21
let dst = Int(a[1], radix: 16)! << 21 | Int(a[2], radix: 16)! << 14
let verifica = a.count > 3
var errori = 0, totale = 0
for (off, size) in blocchi.sorted(by: { $0.0 < $1.0 }) {
    guard let d = leggi(src + off, size) else { print(String(format: "blocco %04X: lettura sorgente fallita", off)); errori += 1; continue }
    totale += size
    if verifica {
        guard let e = leggi(dst + off, size) else { print(String(format: "blocco %04X: lettura destinazione fallita", off)); errori += 1; continue }
        if d != e { errori += 1; print(String(format: "blocco %04X: DIVERSO", off), zip(d, e).enumerated().filter { $0.1.0 != $0.1.1 }.prefix(5).map { String(format: "+%02X %02X/%02X", $0.0, $0.1.0, $0.1.1) }) }
    } else {
        invia(roland(0x12, a7(dst + off) + d))
        RunLoop.current.run(until: Date().addingTimeInterval(0.06))
    }
}
print(verifica ? "verifica: \(blocchi.count) blocchi, \(totale) byte, \(errori) differenze o errori" : "copia: \(blocchi.count) blocchi, \(totale) byte, \(errori) errori")
