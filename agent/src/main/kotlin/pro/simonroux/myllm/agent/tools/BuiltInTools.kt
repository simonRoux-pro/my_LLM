package pro.simonroux.myllm.agent.tools

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonPrimitive
import pro.simonroux.myllm.agent.Tool
import pro.simonroux.myllm.agent.ToolContext
import pro.simonroux.myllm.core.model.ToolResult
import pro.simonroux.myllm.core.model.ToolSpec
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * The tools that ship with the app.
 *
 * Kept small on purpose. Anything domain specific belongs in a skill, where it
 * can be edited on the phone; this set is only what a skill cannot bootstrap
 * itself from, plus the door to the change-request pipeline.
 */
object BuiltInTools {

    fun all(notes: NoteStore, changes: ChangeRequestSink): List<Tool> = listOf(
        currentTime(),
        remember(notes),
        recall(notes),
        forget(notes),
        webFetch(),
        deviceStatus(),
        requestAppChange(changes),
    )

    private fun currentTime(): Tool = simple(
        name = "current_time",
        description = "Donne la date et l'heure locales courantes. À appeler dès qu'une réponse dépend " +
            "d'aujourd'hui, de demain ou d'une durée : le modèle n'a aucune notion du temps par lui-même.",
        parameters = JsonSchema.empty,
    ) { _, _ ->
        val now = Date()
        val format = SimpleDateFormat("EEEE d MMMM yyyy, HH:mm", Locale.FRANCE)
        format.format(now) + " (epoch ms : ${now.time})"
    }

    // --- notes -----------------------------------------------------------

    private fun remember(notes: NoteStore): Tool = simple(
        name = "remember",
        description = "Mémorise durablement une information sur l'utilisateur ou sur une tâche en cours, " +
            "pour la retrouver dans une conversation ultérieure. Sert aux préférences, aux décisions prises " +
            "et aux faits stables, pas au contenu de la conversation courante.",
        parameters = JsonSchema.obj(
            "key" to JsonSchema.string("Identifiant court et stable, par exemple 'ville' ou 'projet_actuel'"),
            "value" to JsonSchema.string("Ce qu'il faut retenir"),
            required = listOf("key", "value"),
        ),
    ) { args, _ ->
        val key = args.text("key") ?: return@simple "Paramètre 'key' manquant."
        val value = args.text("value") ?: return@simple "Paramètre 'value' manquant."
        notes.put(key, value)
        "Mémorisé : $key"
    }

    private fun recall(notes: NoteStore): Tool = simple(
        name = "recall",
        description = "Relit ce qui a été mémorisé. Sans clé, renvoie tout ce qui est en mémoire.",
        parameters = JsonSchema.obj(
            "key" to JsonSchema.string("Clé précise à relire. Omettre pour tout lister."),
        ),
    ) { args, _ ->
        val key = args.text("key")
        if (key.isNullOrBlank()) {
            val all = notes.all()
            if (all.isEmpty()) "Rien en mémoire."
            else all.entries.joinToString("\n") { "${it.key} : ${it.value}" }
        } else {
            notes.get(key) ?: "Rien en mémoire pour '$key'."
        }
    }

    private fun forget(notes: NoteStore): Tool = simple(
        name = "forget",
        description = "Efface une information mémorisée.",
        parameters = JsonSchema.obj(
            "key" to JsonSchema.string("Clé à effacer"),
            required = listOf("key"),
        ),
    ) { args, _ ->
        val key = args.text("key") ?: return@simple "Paramètre 'key' manquant."
        if (notes.remove(key)) "Effacé : $key" else "Rien à effacer pour '$key'."
    }

    // --- device and network ----------------------------------------------

    private fun webFetch(): Tool = simple(
        name = "web_fetch",
        description = "Récupère le contenu d'une URL. Nécessite le réseau : échoue en mode hors ligne, " +
            "ce qui est le comportement attendu et non une erreur à contourner.",
        parameters = JsonSchema.obj(
            "url" to JsonSchema.string("URL complète, http ou https"),
            "max_chars" to JsonSchema.integer("Troncature de la réponse", default = 8000),
            required = listOf("url"),
        ),
        confirm = true,
    ) { args, context ->
        val url = args.text("url") ?: return@simple "Paramètre 'url' manquant."
        if (!context.host.networkAllowed()) {
            return@simple "Mode hors ligne actif : aucune requête réseau n'est possible."
        }
        val limit = args.text("max_chars")?.toIntOrNull() ?: 8000
        val response = context.host.httpRequest("GET", url, emptyMap(), null)
        buildString {
            append("HTTP ").append(response.status).append('\n')
            append(response.body.stripMarkup().take(limit))
            if (response.body.length > limit) append("\n[...tronqué]")
        }
    }

    private fun deviceStatus(): Tool = simple(
        name = "device_status",
        description = "État de l'appareil : batterie, connectivité, stockage libre, mode hors ligne. " +
            "À consulter avant de proposer une action coûteuse comme un téléchargement de modèle.",
        parameters = JsonSchema.empty,
    ) { _, context ->
        context.host.deviceInfo().entries.joinToString("\n") { "${it.key} : ${it.value}" }
    }

    // --- self-modification, slow path -------------------------------------

    private fun requestAppChange(sink: ChangeRequestSink): Tool = simple(
        name = "request_app_change",
        description = "Enregistre une demande de modification de l'application elle-même : " +
            "ce qui ne peut pas se faire avec une skill parce que ça touche au code Kotlin, à l'interface, " +
            "à une dépendance ou à une permission système. La demande est mise en file et pourra être " +
            "envoyée à l'agent de développement. À utiliser quand tu butes sur une limite de l'app, " +
            "pas pour ce qu'une skill peut faire.",
        parameters = JsonSchema.obj(
            "title" to JsonSchema.string("Titre court et concret"),
            "body" to JsonSchema.string(
                "Ce qui manque, pourquoi, et le comportement attendu. Sois précis : ce texte sera lu " +
                    "par un agent qui n'a pas la conversation sous les yeux.",
            ),
            "kind" to JsonSchema.string(
                "Type de demande",
                enum = listOf("FEATURE", "BUG", "REFACTOR", "MODEL_SUPPORT", "UI", "PERFORMANCE"),
            ),
            required = listOf("title", "body"),
        ),
    ) { args, _ ->
        val title = args.text("title") ?: return@simple "Paramètre 'title' manquant."
        val body = args.text("body") ?: return@simple "Paramètre 'body' manquant."
        val id = sink.submit(title, body, args.text("kind") ?: "FEATURE")
        "Demande enregistrée ($id). Elle est visible dans l'onglet Évolutions, " +
            "où elle peut être exportée ou poussée en issue."
    }

    // --- plumbing --------------------------------------------------------

    private fun simple(
        name: String,
        description: String,
        parameters: JsonObject,
        confirm: Boolean = false,
        body: suspend (JsonObject, ToolContext) -> String,
    ): Tool = object : Tool {
        override val spec = ToolSpec(name, description, parameters, requiresConfirmation = confirm)

        override suspend fun execute(arguments: JsonObject, context: ToolContext): ToolResult {
            val startedAt = System.currentTimeMillis()
            return runCatching { body(arguments, context) }.fold(
                onSuccess = {
                    ToolResult("", name, true, it, System.currentTimeMillis() - startedAt)
                },
                onFailure = {
                    ToolResult(
                        "", name, false,
                        "Erreur : ${it.message ?: it::class.java.simpleName}",
                        System.currentTimeMillis() - startedAt,
                    )
                },
            )
        }
    }

    private fun JsonObject.text(key: String): String? =
        this[key]?.let { runCatching { it.jsonPrimitive.content }.getOrNull() }

    /**
     * Crude tag and script removal.
     *
     * A real HTML parser would be better, but most of what a small local model
     * can do with a page is read its prose, and every kilobyte of markup fed
     * into a 4k context is a kilobyte of conversation lost.
     */
    private fun String.stripMarkup(): String = this
        .replace(Regex("(?is)<(script|style)[^>]*>.*?</\\1>"), " ")
        .replace(Regex("(?s)<[^>]+>"), " ")
        .replace(Regex("&nbsp;?"), " ")
        .replace(Regex("[ \\t]+"), " ")
        .replace(Regex("\n{3,}"), "\n\n")
        .trim()
}

/** Long-lived key/value notes the assistant keeps about the user. */
interface NoteStore {
    suspend fun get(key: String): String?
    suspend fun put(key: String, value: String)
    suspend fun remove(key: String): Boolean
    suspend fun all(): Map<String, String>
}

/** Receives change requests raised by the model. */
interface ChangeRequestSink {
    /** @return the id of the stored request. */
    suspend fun submit(title: String, body: String, kind: String): String
}
