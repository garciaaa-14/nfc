# NFC Unlock Lab

Aplicació Android de diagnòstic per a la targeta analitzada amb UID `04:B0:B1:BB:4A:59:80`, detectada com una NTAG216 no genuïna.

## Què fa

- Treballa amb `android.nfc.tech.NfcA` i `transceive()`.
- En detectar una targeta llegeix:
  - `GET_VERSION` (`60`)
  - pàgina `02` (`30 02`)
  - pàgina `E2` (`30 E2`)
- Mostra els bytes i detecta si hi ha lock bytes actius.
- Inclou un botó d'intent de restauració que només s'habilita després d'haver llegit les dues pàgines.
- Per defecte, l'escriptura queda limitada al UID concret `04:B0:B1:BB:4A:59:80`.
- L'intent de restauració conserva els bytes no relacionats amb els locks i intenta:
  - Page 02: `.. .. 00 00`
  - Page E2: `00 00 00 ..`
- Després torna a llegir les pàgines per comprovar si el clon ha acceptat el canvi.
- Inclou consola raw per enviar comandes NFC-A de bytes complets.

## Limitacions importants

En una NTAG216 NXP autèntica, els lock bits són irreversibles. Aquesta app només té alguna possibilitat perquè el tag analitzat sembla un clon. Android `NfcA.transceive()` només permet comandes formades per bytes complets; no pot enviar seqüències de 7 bits utilitzades per algunes variants Magic.

## Compilar

1. Obre aquesta carpeta amb Android Studio.
2. Deixa que Android Studio instal·li/seleccioni Android SDK 35 si ho demana.
3. `Build > Build APK(s)` o executa directament al mòbil per USB.
4. Activa NFC al telèfon.
5. Obre `NFC Unlock Lab` i apropa la targeta.

## Ús recomanat

1. Primer només apropa la targeta i guarda/copia el log.
2. Comprova que el UID sigui exactament `04:B0:B1:BB:4A:59:80`.
3. Si les pàgines mostren els valors esperats, prem `INTENTAR RESTAURAR LOCK BYTES`.
4. Mantén la targeta quieta durant l'intent.
5. Torna a comprovar-la amb NXP TagInfo/NFC Tools.

