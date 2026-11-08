# Δοκιμή Audio RX σε Waveshare ESP32-C6-Zero

Χρειαζόμαστε το Waveshare ESP32-C6-Zero, καλώδιο USB-C δεδομένων και
Windows x64 με Edge ή Chrome. Χρησιμοποίησε την ενσωματωμένη USB-C της
πλακέτας. Κλείσε το ESP-WebSDR και κάθε πρόγραμμα που κρατά ανοιχτή τη σειριακή θύρα.

1. Αν η κάρτα **Firmware** ήδη γράφει `c6_audio_rx`, προχώρησε στο βήμα 2.
   Για πρώτη εγκατάσταση στο **Waveshare C6 Zero**, φλάσαρε το
   `esp32c6_zero_audio_rx.bin` από το [release του Zero](https://github.com/z1000biker/esp32c3rxtx/releases/tag/c6-zero-v0.1.0-preview).
   Αντικατάστησε το `COM24` με τη θύρα που δείχνουν τα δικά σου Windows.
   Το φλασάρισμα αντικαθιστά το firmware που υπάρχει στην πλακέτα.

   ```powershell
   py -m pip install "esptool>=5,<6"
   py -m esptool --chip esp32c6 --port COM24 write-flash 0x0 esp32c6_zero_audio_rx.bin
   ```

   Αν το esptool δεν συνδέεται, κράτα **BOOT**, πάτησε στιγμιαία **RESET**,
   άφησε **BOOT** και έλεγξε αν άλλαξε το COM.
2. Άνοιξε το [Windows Audio Workbench 0.1.1](https://github.com/z1000biker/esp32c3rxtx/releases/download/c6-audio-v0.1.1-preview/ESP32-C6-Audio-Windows-x64.exe).
   Πάτησε **Connect USB** και διάλεξε τη θύρα Espressif. Έλεγξε ότι
   εμφανίζονται **READY** και **c6_audio_rx**.
3. Άφησε **USB I/Q rate → 15.625 kS/s · 8-bit**, βάλε τη συχνότητα γνωστού
   αναλογικού σήματος στη ζώνη 2,4 GHz, επίλεξε τη σωστή αποδιαμόρφωση
   (**AM / NFM / USB / LSB**) και πάτησε **Start RX + audio**.
   Άφησέ το να τρέξει για 30 δευτερόλεπτα. Για έλεγχο μόνο της ροής USB
   αρκεί οποιαδήποτε συχνότητα λήψης, π.χ. 2442 MHz.
4. Στείλε ένα screenshot με την ένδειξη **I/O statistics** και πες αν ο
   ήχος ήταν συνεχής, είχε διακοπές ή ακουγόταν μόνο θόρυβος. Γράψε επίσης
   ποια συχνότητα και διαμόρφωση χρησιμοποίησες. Αν αποτύχει η σύνδεση,
   στείλε το ακριβές μήνυμα από το **Device console**.

Αν δεν έχεις γνωστή αναλογική πηγή, σημείωσε «δοκιμή ροής USB μόνο».
Τα συνηθισμένα πακέτα Wi-Fi και Bluetooth δεν είναι πηγή αναλογικού
ήχου για αυτή τη δοκιμή.
