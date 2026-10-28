# NFC Reader (Android, Jetpack Compose)

Reads and writes NDEF on NFC tags (built for NTAG215 cards, works with NTAG213/216 and other NDEF tags).

- **Read tab**: chip type (NTAG213/215/216 detection), UID, technologies, NDEF memory used/max, writable flag, decoded records (URL, Text, Phone, Email, vCard contact), scan history.
- **Write tab**: compose a message from URL / Text / Contact (vCard) records, see the byte budget, write it. "Erase tag" writes an empty NDEF message.

Open the folder in Android Studio, sync, and run on a real device (emulators have no NFC).
Note: NTAG215 has 504 bytes of user memory, but only ~492 are usable for NDEF.
# NFC
