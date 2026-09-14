# myLLM

Un assistant LLM personnel qui tourne sur le téléphone, fonctionne sans réseau,
et sait écrire ses propres outils.

Application Android native, sideloadée, sans compte, sans télémétrie, sans
sauvegarde cloud. Tout ce que l'app sait de vous reste sur l'appareil.

## Ce que ça fait

**Hors ligne.** llama.cpp compilé pour l'appareil, modèles GGUF téléchargés une
fois. Le cache KV est conservé entre les tours, donc une réponse dans une longue
conversation démarre tout de suite au lieu de relire tout l'historique.

**En ligne quand c'est utile.** N'importe quel endpoint compatible OpenAI :
OpenRouter, Groq, Cerebras, Google AI Studio, ou un `llama-server` sur votre PC.
Un routeur choisit entre local et distant selon une politique que vous réglez, et
un interrupteur unique coupe toute sortie réseau.

**Extensible à chaud.** L'assistant peut écrire un outil en JavaScript, le
tester et l'appeler dans la foulée, sans recompiler et sans réseau. Chaque outil
est versionné, lisible et désactivable depuis l'app.

**Évolutif.** Ce qu'un script ne peut pas faire devient une demande de
modification, exportable vers un agent de code. Le build qui en sort est publié
en release GitHub, que l'app détecte et installe elle-même.

## État

Première version. Le socle est écrit et les modules JVM sont testés ; la
compilation Android complète (couche native incluse) se fait en CI. Voir
[ROADMAP.md](ROADMAP.md) pour ce qui reste.

## Construire

Le dépôt inclut llama.cpp en sous-module, épinglé sur un commit précis.

```bash
git clone --recurse-submodules https://github.com/simonroux-pro/my_llm.git
cd my_llm
./gradlew assembleDebug
```

Prérequis : JDK 17, SDK Android 35, NDK 27.2.12479018, CMake 3.22.1.
Seul `arm64-v8a` est compilé.

Sans machine de build, la CI produit un APK à chaque push : onglet Actions,
artefact `myllm-debug-apk`.

## Installer un modèle

Onglet **Modèles**. Le catalogue propose quelques GGUF adaptés à un téléphone
récent ; n'importe quelle autre URL GGUF fonctionne aussi. Le téléchargement
reprend là où il s'est arrêté.

Sur un appareil à 12 Go de RAM, un modèle 7B en Q4_K_M tourne confortablement.
Compter environ 25 % de RAM en plus que la taille du fichier, pour le cache KV.

## Structure

| Module | Rôle |
|---|---|
| `core:model` | Types du domaine, sans dépendance Android |
| `core:data` | Room, DataStore, secrets chiffrés, dépôts |
| `engine:api` | Contrat `LlmEngine`, commun au local et au distant |
| `engine:local` | Pont JNI vers llama.cpp |
| `engine:remote` | Client `/chat/completions` en streaming |
| `agent` | Boucle d'outils, bac à sable JS, auto-modification |
| `app` | Interface Compose, routage des moteurs, mises à jour |

Détails dans [ARCHITECTURE.md](ARCHITECTURE.md).

## Vie privée

- Aucune analytique, aucun rapport de crash distant, aucun SDK tiers de suivi.
- Sauvegarde cloud et transfert d'appareil désactivés dans le manifeste.
- Les clés API sont chiffrées en AES-GCM par une clé qui ne quitte pas le
  Keystore matériel, et n'apparaissent dans aucun export de configuration.
- Les seules requêtes sortantes vont aux endpoints que vous avez configurés,
  aux URL de téléchargement de modèles, et à l'API GitHub pour les mises à jour.
- Une skill ne peut pas atteindre le réseau, le stockage ou les notifications
  sans avoir déclaré la permission correspondante.

## Licence

Usage personnel.
