# Architecture

Ce document explique les décisions qui coûteraient cher à défaire. Pour ce que
fait l'app, voir le README ; pour ce qui reste à faire, ROADMAP.md.

## Le principe directeur

Un moteur local et un endpoint distant présentent la même interface. Tout ce qui
est au-dessus (boucle d'agent, interface, persistance) ignore lequel répond.

```
                    ┌──────────────────────────┐
                    │        app (Compose)     │
                    └────────────┬─────────────┘
                                 │
                    ┌────────────▼─────────────┐
                    │      EngineManager       │  routage + une seule
                    │  local / distant / repli │  instance locale en RAM
                    └────────────┬─────────────┘
                                 │
                    ┌────────────▼─────────────┐
                    │        AgentLoop         │  outils, confirmations,
                    │   engine + ToolRegistry  │  plafond de tours
                    └────┬──────────────┬──────┘
                         │              │
          ┌──────────────▼───┐    ┌─────▼──────────────────┐
          │  LlamaCppEngine  │    │ OpenAiCompatibleEngine │
          │   JNI, hors      │    │   SSE, /chat/          │
          │   ligne          │    │   completions          │
          └──────────────────┘    └────────────────────────┘
                   implémentent tous deux LlmEngine
```

## Décisions

### Injection de dépendances manuelle

`AppContainer` construit tout à la main. Pas de Hilt, pas de Koin.

Le graphe tient sur un écran, et surtout : c'est une app censée se réécrire. Un
processeur d'annotations ajoute une étape de génération dont les échecs ne sont
pas visibles dans le source, ce qui est exactement ce qu'il ne faut pas quand un
modèle propose des modifications qu'un humain relit vite.

### `:core:model` et `:agent` sont du Kotlin pur

Pas de dépendance Android. Conséquence pratique : ils compilent et se testent
sans SDK Android, donc le bac à sable JS et la boucle d'outils sont vérifiés par
de vrais tests unitaires rapides. La partie de la base de code qui exécute du
code non relu est aussi celle qui est la plus testable.

### Le contrat JNI est mince

Le pont C++ expose « charge, tokenise, décode un lot, échantillonne un jeton ».
Tout le reste (assemblage du prompt, streaming, séquences d'arrêt, annulation)
est en Kotlin.

llama.cpp est épinglé sur un commit précis, parce que son API C bouge d'un mois
à l'autre. Le pont a été écrit contre l'en-tête de ce commit exact, pas de
mémoire. **Bumper le sous-module impose de revérifier chaque signature utilisée
dans `llama_bridge.cpp`.**

### Un seul thread pour l'inférence locale

Un `llama_context` n'est pas thread-safe et s'en écarter ne produit pas une
exception, mais un crash natif sans pile d'appels. Tout passe par un dispatcher
à un seul thread.

### Le cache KV survit aux tours

Entre deux tours, le préfixe commun des jetons est conservé et seul le suffixe
divergent est décodé. Sans ça, chaque message dans une conversation de 3000
jetons redémarre par plusieurs secondes de lecture du prompt, ce qui rend
l'usage réel pénible bien avant que la vitesse de génération ne pose problème.

### Les jetons sortent en octets, pas en texte

Un jeton porte souvent la moitié d'un caractère multi-octets, et les accents
sont précisément là où ça arrive. `Utf8Accumulator` retient les séquences
incomplètes jusqu'à ce qu'elles soient closes.

### Les outils sont appelés de deux façons

Un endpoint distant a un canal structuré pour les appels d'outils ; un GGUF
décodé par llama.cpp n'a que du texte. `AgentLoop` utilise le canal natif quand
il existe, et sinon décrit les outils dans le prompt puis relit les appels dans
la réponse, avec la convention `<tool_call>` sur laquelle Qwen est entraîné.
L'appelant voit les mêmes événements dans les deux cas.

## Le bac à sable

Trois couches, parce qu'une seule ne suffit pas.

1. **Portée sûre.** `initSafeStandardObjects` : `Packages`, `java`,
   `importClass` et `getClass` n'existent pas dans la portée.
2. **`ClassShutter` qui refuse tout.** Aucune liste blanche. Ce qui ferme les
   chemins non documentés vers la JVM.
3. **Une seule porte.** Le script ne voit qu'une fonction prenant deux chaînes
   et renvoyant une chaîne. On ne peut pas atteindre un objet qu'on n'a jamais
   reçu, et une chaîne ne porte aucune surface réflexive.

Le pont est capturé en fermeture puis retiré de la portée globale : une skill ne
peut ni l'appeler directement ni le remplacer pour se mentir à elle-même.

L'exécution est bornée : l'interpréteur rend la main toutes les quelques milliers
d'instructions et un script au-delà de son délai est tué. `while(true)` coûte
quelques millisecondes.

Rhino tourne en mode interprété (`optimizationLevel = -1`). Ce n'est pas un
réglage de performance : le mode optimisé génère du bytecode JVM à l'exécution,
ce qu'Android n'exécute pas.

`SkillBridge` applique ensuite les permissions. Le bac à sable contrôle ce qu'un
script peut atteindre, le pont contrôle ce qu'il a le droit d'en faire.

Ces propriétés sont couvertes par `ScriptSandboxTest`, qui nomme l'évasion que
chaque test ferme.

## Les deux moitiés de l'auto-modification

**Rapide, hors ligne, à chaud.** `skill_write` crée un outil, `skill_test`
l'exécute, `skill_restore` revient en arrière. Chaque écriture est une version, et
le code source est visible dans l'onglet Skills. Un modèle qui ne peut que lire
ses outils ne peut pas les réparer ; un modèle qui peut les écraser sans
historique est à une mauvaise édition d'un assistant cassé.

**Lente, en ligne, par rebuild.** `request_app_change` enregistre ce qu'une skill
ne peut pas faire. Le brief s'exporte vers un agent de code, la CI produit un
APK, l'app le détecte et l'installe. L'installation passe par l'installeur
système : l'utilisateur confirme, Android vérifie la signature.

## Ce qui n'est pas là

- **Pas d'offload GPU.** `n_gpu_layers` est câblé mais le backend Vulkan n'est
  pas compilé. Sur Mali, le gain est incertain et le coût de build élevé.
- **Pas de base chiffrée.** SQLCipher ajoute une dépendance native et une clé à
  gérer. Le verrouillage de l'appareil protège déjà le répertoire privé.
- **Pas d'images.** Le contrat `LlmEngine` a le drapeau, rien derrière.
