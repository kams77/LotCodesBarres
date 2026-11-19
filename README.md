# Lot Codes-Barres (Android)

Application Android qui génère des codes-barres **en lot** et les exporte en **PDF A4** (planche d'étiquettes) ou en **ZIP d'images PNG**.

## Fonctions

- 8 formats : Code 128, EAN-13, EAN-8, UPC-A, Code 39, ITF-14, Codabar, QR code.
- Chiffre de contrôle calculé automatiquement pour EAN-13, EAN-8, UPC-A et ITF-14 (et vérifié si vous le donnez).
- Deux sources de valeurs :
  - **Séquence** : préfixe + numéro + suffixe, avec un nombre de chiffres fixe (ex. `KIN-00001` à `KIN-00500`).
  - **Liste** : une valeur par ligne (copiée depuis Excel, par exemple).
- Jusqu'à 2 000 codes par lot. Les valeurs invalides sont listées, les autres sont générées.
- Export PDF A4 avec 2 à 5 colonnes et traits de coupe ; export PNG haute résolution dans un ZIP.
- Le fichier s'ouvre dans le menu de partage d'Android : enregistrer dans Fichiers, Drive, WhatsApp, impression…
- **Enregistrer le PDF** directement sur le téléphone (choix du dossier), ou l'**envoyer** (WhatsApp, Gmail, Drive…).
- **Import en ligne** : lien Google Sheets (partagé « Tous les utilisateurs disposant du lien ») ou fichier CSV/TXT ; la 1re colonne est utilisée.
- **TPE à imprimante intégrée** : Sunmi (V2, V2 Pro, P2, T2…) et modèles compatibles « InnerPrinter », détectée automatiquement.
- **Impression Android** : services d'impression des TPE (PAX, Ingenico, Verifone… selon le fabricant), imprimantes Wi-Fi
  (HP, Epson, Canon, Brother, Mopria) ou « Enregistrer au format PDF » ; étiquettes sur rouleau 58/80 mm ou planche A4.
- **Imprimante POS thermique (ESC/POS)** : Bluetooth (appareil appairé), Wi-Fi/réseau (IP + port 9100) ou USB (câble OTG) ;
  papier 58 mm (384 points) ou 80 mm (576 points), copies par code, coupe automatique, page de test.
- Fonctionne aussi sans Internet (sauf l'import en ligne). Android 8.0 ou plus.

## Obtenir l'APK

### Option A — Android Studio (sur un ordinateur)

1. Installez Android Studio : https://developer.android.com/studio
2. Décompressez ce dossier, puis **File → Open** et choisissez le dossier `LotCodesBarres`.
3. Attendez la fin de la synchronisation Gradle (il faut Internet la première fois).
4. Menu **Build → Build App Bundle(s) / APK(s) → Build APK(s)**.
5. Cliquez sur **locate** dans la notification : le fichier est
   `app/build/outputs/apk/debug/app-debug.apk`.
6. Copiez l'APK sur le téléphone et ouvrez-le. Android demandera d'autoriser
   l'installation depuis cette source : acceptez.

### Option B — GitHub, sans rien installer

1. Créez un dépôt sur GitHub et envoyez-y le contenu de ce dossier.
2. L'onglet **Actions** lance automatiquement « Construire l'APK » (environ 5 minutes).
3. Ouvrez l'exécution terminée, puis téléchargez **LotCodesBarres-apk** en bas de la page.

### En ligne de commande

```
./gradlew assembleDebug        # Linux / macOS
gradlew.bat assembleDebug      # Windows
```

## Structure

```
app/src/main/java/cd/codesbarres/lot/
  MainActivity.kt    écran, génération en arrière-plan, partage
  BarcodeEngine.kt   validation des valeurs et dessin des codes (bibliothèque ZXing)
  Exporter.kt        PDF A4 et ZIP de PNG
app/src/main/res/layout/activity_main.xml   interface
```

## Version et signature

- Version actuelle : **1.0** (versionCode 1). Pour une mise à jour, augmentez
  `versionCode` et `versionName` dans `app/build.gradle.kts`.
- GitHub Actions produit l'APK release **non signé** (branche `apk`), car ce dépôt
  est public et la clé de signature ne doit jamais y figurer.
- Signature, avec la clé `lotcodesbarres-release.jks` gardée en lieu sûr :

```
java -jar apksigner.jar sign --ks lotcodesbarres-release.jks \
  --ks-key-alias lotcodesbarres --out LotCodesBarres-v1.0.apk \
  LotCodesBarres-release-non-signe.apk
```

Toutes les futures mises à jour doivent être signées avec **la même clé**, sinon
Android refusera de les installer par-dessus l'ancienne version.
