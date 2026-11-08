# ESP32-C6-DevKitC-1 — ESP-SDR RX

Το `esp32c6_devkitc1_espsdr_rx_merged.bin` είναι ενιαίο αρχείο για εγγραφή στη διεύθυνση `0x0`. Περιέχει τον bootloader, τον πίνακα κατατμήσεων και την εφαρμογή του επίσημου προφίλ ESP-SDR για ESP32-C6. Η εφαρμογή κάνει λήψη I/Q. Το RAR του C3 δεν περιείχε το `hybrid/receiver.c` της έκδοσης εκπομπής.

## Φλασάρισμα σε Windows

Αποσυμπίεσε το ZIP. Σύνδεσε το καλώδιο δεδομένων στη θύρα **ESP32-C6 USB** της πλακέτας. Βρες το COM στη Διαχείριση Συσκευών και κλείσε όποια εφαρμογή το χρησιμοποιεί. Άνοιξε PowerShell στον φάκελο που περιέχει το BIN. Αν έχεις ήδη esptool 5.x, η πρώτη εντολή περισσεύει. Βάλε το δικό σου COM στη δεύτερη.

```powershell
py -m pip install "esptool>=5,<6"
py -m esptool --chip esp32c6 --port COM7 write-flash 0x0 esp32c6_devkitc1_espsdr_rx_merged.bin
```

Η εγγραφή αντικαθιστά το υπάρχον firmware, τον πίνακα κατατμήσεων και τα δεδομένα NVS. Αν το esptool δεν συνδεθεί, κράτα πατημένο το **BOOT**, πάτησε στιγμιαία **RESET**, άφησε το **BOOT**, έλεγξε αν άλλαξε το COM και ξανατρέξε τη δεύτερη εντολή. Μετά το φλασάρισμα πάτησε **RESET** ή αποσύνδεσε και ξανασύνδεσε την πλακέτα.

Για την πρώτη δοκιμή χρησιμοποίησε το **δικό σου ESP32-C6 RX Workbench**. Άνοιξε το Windows EXE ή εγκατάστησε το Android APK και σύνδεσε το κινητό με USB OTG. Πάτησε **Connect USB**, βάλε **2442 MHz** και επίλεξε **Live spectrum** ή **Fine snapshots**. Μπορείς επίσης να τρέξεις το ίδιο web app από Edge ή Chrome με την εντολή `python -m http.server 8000 --directory web` και τη διεύθυνση `http://localhost:8000`.

## Πηγαίος κώδικας και μεταγλώττιση

Στο πλήρες πακέτο, ο πηγαίος κώδικας του ESP-SDR βρίσκεται στον φάκελο `firmware-src/`, μαζί με το ESP-DSP της αντίστοιχης έκδοσης. Τα αρχεία του C6 βρίσκονται κυρίως στα `main/targets/esp32c6/` και `main/families/c5_c6_c61/`. Το ESP-SDR διανέμεται με άδεια GPL-3.0-or-later. Οι άδειες περιλαμβάνονται στον πηγαίο κώδικα.

- ESP-SDR `9b61e7465bee6da15209c5c09308043d08c46a1d`
- ESP-IDF `25fe69f946311abdaf9ad56591f25fedbc20ac98`
- ESP-DSP `a53a0756833c045311ea1d79a2badf495cdfde4c`

Με εγκατεστημένο και ενεργοποιημένο το ESP-IDF στο παραπάνω commit, μαζί με τα submodules του, τρέξε από τον φάκελο του ZIP σε ESP-IDF terminal:

```text
cd firmware-src
python tools/build_firmware.py --profile esp32c6 --version 9b61e7465bee6da15209c5c09308043d08c46a1d --output artifacts
python -m esptool --chip esp32c6 merge-bin -o ../esp32c6_devkitc1_espsdr_rx_rebuilt.bin 0x0 artifacts/esp32c6/esp32c6/0-bootloader.bin 0x8000 artifacts/esp32c6/esp32c6/1-partition-table.bin 0x10000 artifacts/esp32c6/esp32c6/2-esp_sdr.bin
```

Το παρεχόμενο BIN προέκυψε από συνένωση των τριών δημοσιευμένων εικόνων του [ESPARGOS](https://espargos.net/espsdr/app/flash.html). Τα SHA-256 των αρχικών εικόνων επαληθεύτηκαν από το επίσημο manifest. Ελέγχθηκαν επίσης το αναγνωριστικό ESP32-C6, τα αθροίσματα ελέγχου του bootloader και της εφαρμογής, ο πίνακας κατατμήσεων και οι διευθύνσεις στο ενιαίο αρχείο. Ο έλεγχος έγινε σε επίπεδο αρχείου. Το παρεχόμενο BIN δεν φλασαρίστηκε σε φυσική πλακέτα και δεν έγινε δεύτερη μεταγλώττιση του πηγαίου κώδικα εδώ.

[Πηγαίος κώδικας ESP-SDR](https://github.com/ESPARGOS/esp-sdr/tree/9b61e7465bee6da15209c5c09308043d08c46a1d)
