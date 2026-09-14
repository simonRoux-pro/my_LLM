# Consignes projet

App Android personnelle : LLM local (llama.cpp), agent à outils, skills en JS
modifiables à chaud. Sideloadée, hors ligne d'abord, aucune télémétrie.

## Langue

Le propriétaire est francophone. Réponses en français. Textes d'interface en
français. Code, noms de symboles, commentaires et messages de commit en anglais,
sauf les chaînes destinées à l'utilisateur.

## Attribution Git

Commits signés `Simon Roux <pro.simon.roux@gmail.com>`. Ne pas ajouter de
co-auteur, de mention d'outil ou de lien de session dans les messages de commit,
les titres de PR ou les corps de PR.

## Build

Le SDK Android n'est pas disponible dans les sessions Claude Code sur le web
(`dl.google.com` est bloqué par la politique réseau). Conséquences :

- Les modules JVM purs se compilent et se testent localement :
  `./gradlew :core:model:compileKotlin :engine:api:compileKotlin
  :engine:remote:compileKotlin :agent:test`
  Pour ça il faut temporairement retirer les alias de plugin Android de
  `build.gradle.kts` et `google()` de `settings.gradle.kts`, puis les remettre.
- Les modules Android (`:app`, `:core:data`, `:engine:local`) sont vérifiés par
  la CI. Pousser et lire le résultat du workflow `build`.

Ne jamais annoncer qu'un module Android compile sans preuve d'un run CI vert.

## llama.cpp

Sous-module épinglé sur un commit précis. Son API C change souvent.

`engine/local/src/main/cpp/llama_bridge.cpp` a été écrit contre l'en-tête de ce
commit exact. Avant de bumper le sous-module, lire
`engine/local/src/main/cpp/llama.cpp/include/llama.h` et revérifier chaque
signature utilisée. Ne pas écrire d'appel llama.cpp de mémoire.

## Règles qui ne se négocient pas

- Aucune analytique, aucun rapport de crash distant, aucun SDK de suivi.
- Aucune requête réseau qui ne parte pas d'un endpoint configuré par
  l'utilisateur, d'une URL de modèle, ou de l'API GitHub pour les mises à jour.
- Le mode hors ligne (`AppSettings.offlineOnly`) est vérifié au moment de
  l'appel, jamais mis en cache au démarrage.
- Une skill n'atteint rien sans permission déclarée. Ne pas élargir
  `SkillBridge` sans ajouter la permission correspondante.
- Ne pas exposer `SKILL_ADMIN` à une skill écrite par le modèle.
- Toute écriture de skill crée une révision. Ne pas ajouter de chemin d'écriture
  qui contourne `SkillRepository.upsert`.

## Le bac à sable

`ScriptSandbox` et `SkillPrelude` sont la surface sensible. Toute modification :

1. Lire `ScriptSandboxTest` d'abord, il nomme les évasions déjà fermées.
2. Ajouter un test pour la propriété qu'on prétend préserver.
3. `./gradlew :agent:test` doit rester vert.

Ne pas assouplir le `ClassShutter`, ne pas remplacer `initSafeStandardObjects`
par `initStandardObjects`, ne pas remettre `__bridge` dans la portée globale.

## Style

- Commentaires seulement là où le « pourquoi » n'est pas lisible dans le code.
  Pas de commentaire qui paraphrase la ligne suivante.
- Pas de tirets cadratins dans les textes destinés à l'utilisateur.
- Préférer une fonction lisible à une astuce. Cette base de code est relue vite,
  sur un téléphone.

## Ajouter une fonctionnalité

1. Si ça peut être une skill, c'est une skill. Pas de code Kotlin.
2. Sinon : type du domaine dans `core:model`, persistance dans `core:data`,
   logique dans le module concerné, interface dans `app`.
3. Un nouvel outil intégré va dans `agent/tools/BuiltInTools.kt` et est
   enregistré dans `AppContainer.initialise`.
