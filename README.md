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
- Fonctionne sans Internet. Aucune autorisation demandée. Android 8.0 ou plus.

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

Pour publier sur le Play Store, il faut un APK/AAB **signé en release** :
**Build → Generate Signed App Bundle / APK** dans Android Studio.
