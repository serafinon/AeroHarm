import CoreMIDI
import Foundation
var client = MIDIClientRef(); MIDIClientCreate("cc" as CFString, nil, nil, &client)
var porta = MIDIPortRef(); MIDIOutputPortCreate(client, "out" as CFString, &porta)
func nome(_ e: MIDIEndpointRef) -> String { var s: Unmanaged<CFString>?; MIDIObjectGetStringProperty(e, kMIDIPropertyDisplayName, &s); return (s?.takeRetainedValue() as String?) ?? "" }
let dest = (0..<MIDIGetNumberOfDestinations()).map { MIDIGetDestination($0) }.first { nome($0) == "AE-20 MIDI IN1" }!
let a = CommandLine.arguments.dropFirst().map { UInt8($0)! }   // canale cc valore
let b: [UInt8] = [0xB0 + a[0] - 1, a[1], a[2]]
var lista = MIDIPacketList(); let p = MIDIPacketListInit(&lista)
_ = MIDIPacketListAdd(&lista, 1024, p, 0, 3, b); MIDISend(porta, dest, &lista); usleep(100000)
