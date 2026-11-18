# 🛡️ SentinelCam — Portail Caméra WebRTC E2EE & Centre de Contrôle PWA

**SentinelCam** transforme un smartphone Android en portail caméra sur demande (caméra frontale/selfie par défaut, basculable vers la caméra arrière), pilotable en temps réel depuis une Progressive Web App (PWA) 100 % statique hébergée sur **GitHub Pages**, **sans aucun serveur propriétaire payant**.

---

## 🏗️ 1. Architecture Technique & Justification de Persistance Android

### Pourquoi une architecture hybride Capacitor 6 + Moteur Natif Kotlin (`CameraForegroundService`) ?
Sur Android 11 à 15+, une `WebView` standard met automatiquement en pause `navigator.mediaDevices.getUserMedia`, suspend les timers JavaScript après quelques minutes en arrière-plan et détruit le flux vidéo dès que l'écran est verrouillé ou qu'une autre application passe au premier plan.

Pour garantir une **stabilité absolue 24h/24** et une **consommation batterie quasi nulle en veille**, SentinelCam déporte 100 % de la logique critique dans un **Service Natif Kotlin de Premier Plan (`CameraForegroundService.kt`)** totalement indépendant du cycle de vie de l'activité WebView :

1. **Signalisation Serverless Redondante Native (`MqttSignalingEngine.kt` & `signalingService.ts`)** :
   - Connexion simultanée Dual-Active en WebSockets TLS (`wss://`) vers deux clusters publics mondiaux haute disponibilité (`broker.emqx.io` et `broker.hivemq.com`) avec déduplication anti-rejeu.
   - Gestion native `ConnectivityManager.NetworkCallback` + boucle de garde Coroutines assurant une reconnexion automatique infinie lors des bascules **Wi-Fi ↔ 4G/5G**.
2. **Capture Matérielle Headless à la Demande (`NativeWebRtcEngine.kt`)** :
   - Utilise la pile C++/JNI officielle **WebRTC Android (`io.github.webrtc-sdk:android`)** couplée à **`Camera2Enumerator`** et un contexte GPU hors-écran **`SurfaceTextureHelper` (`EglBase`)**.
   - **En veille (`Standby`)** : le capteur caméra physique est **100 % fermé (`stopCapture` + `dispose`)** — aucune consommation capteur, uniquement ~2 Ko/min de signalisation chiffrée.
   - **À l'allumage (`CMD_START_STREAM`)** : ouverture matérielle directe de la caméra frontale (`LENS_FACING_FRONT`), encodage matériel H.264/VP8 et streaming direct en **moins de 2 secondes**, même écran éteint ou en utilisant d'autres applications.
3. **Résilience Doze & Transparence Système Android** :
   - `PowerManager.PARTIAL_WAKE_LOCK` + `WifiManager.WIFI_MODE_FULL_LOW_LATENCY` + demande d'exemption Doze (`ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS`).
   - Relance automatique sur fermeture depuis le multitâche (`onTaskRemoved` + `AlarmManager`) et après redémarrage du téléphone (`BootReceiver` sur `BOOT_COMPLETED`).
   - Notification système permanente (`setOngoing(true)`) affichant en temps réel *"Portail SentinelCam actif - En attente"* ou *"Streaming caméra en cours"*, équipée des boutons rapides **« Mettre en veille »** et **« Couper le portail »**.

---

## 🔐 2. Sécurité Cryptographique de Bout en Bout (Zero-Knowledge)

1. **Dérivation Maîtresse Anti-GPU (`PBKDF2-HMAC-SHA256`, 150 000 itérations)** :
   - Le code à 6 caractères (généré via `SecureRandom` / `crypto.getRandomValues`) n'est jamais transmis en clair ni utilisé directement comme topic MQTT. Il dérive un identifiant de routage opaque 128 bits et une clé d'amorçage 256 bits.
2. **Verrouillage Cryptographique sur le Navigateur Appairé (`RFC 5869 HKDF-SHA256` + `HMAC-SHA256`)** :
   - Lors du premier appairage, un jeton secret 256 bits est dérivé via HKDF-SHA256 et verrouillé des deux côtés.
   - Lors des reconnexions suivantes (en 1 clic), le navigateur prouve la possession du jeton via un **challenge-response mutuel HMAC-SHA256** sans jamais retransmettre la clé sur le réseau, puis dérive une **clé de session éphémère AES-256-GCM** (Confidentialité Persistante / Forward Secrecy).
3. **Protection Anti-Brute-Force Stricte** :
   - Après **3 échecs** d'authentification, le portail verrouille temporairement l'accès selon une courbe exponentielle (`30s → 60s → 120s → 240s...`).
4. **Double Chiffrement E2EE & Vérification SAS Anti-MitM** :
   - Tous les paquets de signalisation sont chiffrés en **AES-256-GCM** (IV aléatoire 96 bits + tag d'intégrité 128 bits + fenêtre temporelle anti-rejeu ±120s).
   - Le flux audio/vidéo et le `RTCDataChannel` télémétrique sont chiffrés nativement en **DTLS-SRTP** avec vérification d'empreinte **SAS (Short Authentication String)** visuelle (`🛡️ 🔐 ⚡ 🛰️` + code hexadécimal).

---

## 📂 3. Arborescence Complète du Projet

```text
campwa/
├── .github/workflows/
│   ├── deploy-pages.yml              # Build PWA + déploiement automatique GitHub Pages
│   └── build-apk.yml                 # Build Android SDK / Java 17 / Gradle -> APK signé (Artifacts + Releases)
├── public/
│   ├── manifest.webmanifest          # Manifeste PWA installable
│   ├── sw.js                         # Service Worker hors-ligne / mise à jour réseau
│   ├── icon.svg                      # Icône vectorielle SentinelCam
│   └── icon-maskable.svg             # Icône adaptative Android/PWA
├── src/
│   ├── crypto/cryptoEngine.ts        # PBKDF2, HKDF, HMAC, AES-256-GCM, SAS, Anti-Brute-Force
│   ├── services/
│   │   ├── signalingService.ts       # Client MQTT WSS redondant (EMQX + HiveMQ)
│   │   ├── webrtcController.ts       # Gestionnaire WebRTC Poste de Contrôle + stats + captures PNG
│   │   ├── browserEmitterEngine.ts   # Mode émetteur navigateur pour tests instantanés Web-to-Web
│   │   └── deviceVault.ts            # Coffre local des jetons d'appairage & serveurs STUN/TURN
│   ├── native/sentinelPlugin.ts      # Pont TypeScript <-> Plugin Capacitor Kotlin
│   ├── components/
│   │   ├── HeaderBar.tsx             # Barre de navigation, installation PWA & téléchargement APK
│   │   ├── ControlStationView.tsx    # Interface Centre de Contrôle (6 caractères, flux, HUD, captures)
│   │   ├── AndroidEmitterView.tsx    # Assistant de consentement unique & tableau de bord Portail
│   │   ├── SecuritySasModal.tsx      # Inspecteur d'empreinte SAS & DTLS-SRTP
│   │   └── SettingsModal.tsx         # Diagnostic courtiers WSS & configuration TURN
│   ├── types/sentinel.ts             # Contrats de types complets
│   ├── App.tsx
│   ├── main.tsx
│   └── index.css
├── android/app/src/main/
│   ├── AndroidManifest.xml           # Permissions Android 11-15 + ForegroundService + BootReceiver
│   └── java/com/sentinelcam/app/
│       ├── MainActivity.kt
│       ├── plugin/SentinelNativePlugin.kt
│       ├── crypto/CryptoProtocol.kt
│       ├── signaling/MqttSignalingEngine.kt
│       ├── webrtc/NativeWebRtcEngine.kt
│       ├── service/CameraForegroundService.kt
│       ├── receiver/BootReceiver.kt
│       └── storage/PortalPreferences.kt
├── capacitor.config.ts
├── vite.config.ts
├── tailwind.config.js
└── package.json
```

---

## 🚀 4. Guide de Déploiement Rapide & Utilisation

### A. Déploiement Automatique (GitHub Actions)
Dès chaque `git push` sur la branche `main` de [`https://github.com/axeldelaro/campwa`](https://github.com/axeldelaro/campwa) :
1. **Workflow `Build & Release SentinelCam Android APK`** :
   - Compile le projet natif Kotlin + WebRTC avec Java 17 et Android SDK 34.
   - Signe automatiquement **`SentinelCam.apk`** (schémas V1 + V2) et le publie directement dans **GitHub Releases (`/releases/latest`)** ainsi que dans les **Artifacts** de l'onglet *Actions*.
2. **Workflow `Deploy SentinelCam PWA to GitHub Pages`** :
   - Génère le bundle statique optimisé et publie simultanément sur la branche `gh-pages` et l'environnement GitHub Pages.
   - Vérifiez dans **Settings → Pages** de votre dépôt GitHub que la source est configurée sur **`Deploy from a branch` (`gh-pages` / `/root`)** ou **`GitHub Actions`** : votre Poste de Contrôle est immédiatement accessible sur **`https://axeldelaro.github.io/campwa/`**.

### B. Mise en route en 60 secondes
1. **Sur le smartphone Android** :
   - Téléchargez et installez **`SentinelCam.apk`** depuis l'onglet **Releases** du dépôt (ou le bouton *APK Android* en haut à droite du site web).
   - Au tout premier lancement, validez l'assistant unique (autorisations Caméra/Micro/Notifications + exemption d'optimisation batterie Doze).
   - Notez le **code à 6 caractères** affiché à l'écran : vous pouvez désormais verrouiller l'écran ou utiliser d'autres applications, le portail reste actif en arrière-plan.
2. **Sur le Poste de Contrôle Web (`https://axeldelaro.github.io/campwa/`)** :
   - Saisissez le code à 6 caractères : l'appairage cryptographique HKDF s'effectue et mémorise votre appareil pour une reconnexion future en **1 clic**.
   - Cliquez sur **« Allumer la caméra »** pour démarrer le flux direct en moins de 2 secondes, ou **« Mettre en veille la caméra »** pour couper physiquement le capteur à distance.

### C. Développement Local
```bash
npm install
npm run dev        # Lance le Poste de Contrôle en local sur http://localhost:5173
npm run build      # Vérification TypeScript stricte + build de production Vite
npx cap sync       # Synchronise le bundle Web vers le projet natif Android
```
