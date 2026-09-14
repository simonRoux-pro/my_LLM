# Suite

Par ordre de valeur réelle, pas de difficulté.

## À valider en premier

- [ ] **Activer GitHub Actions sur le dépôt.** Bloquant, et rien d'autre ne peut
      avancer avant. Tous les workflows échouent au démarrage sans annotation, y
      compris `smoke.yml` qui ne fait qu'un `echo` : ce n'est donc pas un
      problème de fichier. Regarder Settings puis Actions puis General
      (« Allow all actions »), et la facturation, le dépôt étant privé.
- [ ] **Premier build CI vert.** Les modules Android n'ont jamais été compilés,
      seulement écrits. Attendre des erreurs de compilation au premier run, et
      compter quelques allers-retours : c'est llama.cpp qui est long à bâtir.
- [ ] **Vérifier les URL du catalogue de modèles.** Elles suivent la convention
      de nommage de `bartowski` mais n'ont pas été testées. Un 404 au
      téléchargement se corrige en éditant `ModelCatalog.kt`.
- [ ] **Confirmer que Rhino tourne sur l'appareil.** Il passe les tests sur JVM
      et ne référence aucune classe absente d'Android, mais ça reste à voir en
      vrai. Si ça casse, `ScriptSandbox` est derrière une interface : QuickJS
      via le NDK est le remplaçant, la couche native existe déjà.

## Ensuite

- [ ] **Import d'un GGUF depuis le stockage.** Le téléchargement par URL marche ;
      pouvoir pointer un fichier déjà présent évite de retélécharger plusieurs
      gigaoctets.
- [ ] **Ajout d'un modèle par URL dans l'interface.** Le modèle de données le
      supporte déjà, il manque l'écran.
- [ ] **Service en premier plan pendant la génération.** Aujourd'hui une longue
      réponse peut être tuée si on quitte l'app.
- [ ] **Rendu Markdown des réponses.** Le réglage existe, l'affichage est en
      texte brut.
- [ ] **Liste et recherche des conversations.** La persistance et la requête de
      recherche sont écrites, l'écran n'existe pas.

## Plus tard

- [ ] Poussée directe d'une demande de modification en issue GitHub, avec un PAT
      chiffré. Le brief s'exporte déjà à la main.
- [ ] Bibliothèque de skills partageables, en import/export de fichier.
- [ ] Offload GPU par Vulkan, à mesurer avant de garder.
- [ ] Chiffrement de la base par SQLCipher.
- [ ] Entrée vocale hors ligne.
