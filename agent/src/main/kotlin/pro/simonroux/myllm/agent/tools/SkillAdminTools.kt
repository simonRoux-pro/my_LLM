package pro.simonroux.myllm.agent.tools

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import pro.simonroux.myllm.agent.SkillStore
import pro.simonroux.myllm.agent.Tool
import pro.simonroux.myllm.agent.ToolContext
import pro.simonroux.myllm.agent.script.ScriptResult
import pro.simonroux.myllm.agent.script.ScriptSandbox
import pro.simonroux.myllm.agent.script.SkillTool
import pro.simonroux.myllm.core.model.Skill
import pro.simonroux.myllm.core.model.SkillPermission
import pro.simonroux.myllm.core.model.ToolResult
import pro.simonroux.myllm.core.model.ToolSpec
import java.util.UUID

/**
 * The tools that let the model extend itself.
 *
 * This is the fast half of self-modification: a new capability exists the moment
 * the model finishes writing it, with no rebuild, no network and no restart.
 * The slow half, changing the app's own Kotlin, goes through the change-request tool.
 *
 * Writes are versioned and confirmable rather than forbidden. A model that can
 * only read its own tools cannot fix them, and a model that can overwrite them
 * with no history is one bad edit from a broken assistant.
 */
class SkillAdminTools(
    private val store: SkillStore,
    private val sandbox: ScriptSandbox = ScriptSandbox(),
    private val requireConfirmation: () -> Boolean = { true },
    /** Called after any change so the registry can pick up the new set. */
    private val onChanged: suspend () -> Unit = {},
) {

    fun all(): List<Tool> = listOf(list, read, write, test, remove, restore)

    private val json = Json { prettyPrint = true; ignoreUnknownKeys = true }

    // --- read ------------------------------------------------------------

    private val list = tool(
        name = "skills_list",
        description = "Liste les skills existantes avec leur description, leurs permissions et leur état. " +
            "À appeler avant d'en écrire une nouvelle, pour éviter les doublons.",
        parameters = JsonSchema.empty,
    ) { _, _ ->
        val skills = store.all()
        if (skills.isEmpty()) {
            "Aucune skill enregistrée."
        } else {
            skills.joinToString("\n") { skill ->
                buildString {
                    append(if (skill.enabled) "[actif] " else "[inactif] ")
                    append(skill.name).append(" (v").append(skill.version).append(") : ")
                    append(skill.description)
                    if (skill.permissions.isNotEmpty()) {
                        append(" | permissions : ")
                        append(skill.permissions.joinToString(",") { it.name })
                    }
                    skill.lastError?.let { append(" | dernière erreur : ").append(it) }
                }
            }
        }
    }

    private val read = tool(
        name = "skill_read",
        description = "Renvoie le code source complet et le schéma de paramètres d'une skill. " +
            "À appeler avant toute modification, pour ne pas réécrire à l'aveugle.",
        parameters = JsonSchema.obj(
            "name" to JsonSchema.string("Nom exact de la skill"),
            required = listOf("name"),
        ),
    ) { args, _ ->
        val name = args.text("name") ?: return@tool "Paramètre 'name' manquant."
        val skill = store.byName(name) ?: return@tool "Skill introuvable : $name"
        buildString {
            append("nom: ").append(skill.name).append('\n')
            append("version: ").append(skill.version).append('\n')
            append("description: ").append(skill.description).append('\n')
            append("permissions: ").append(skill.permissions.joinToString(",") { it.name }).append('\n')
            append("parametres:\n").append(json.encodeToString(JsonObject.serializer(), skill.parameters)).append('\n')
            append("code:\n").append(skill.code)
        }
    }

    // --- write -----------------------------------------------------------

    private val write = tool(
        name = "skill_write",
        description = """
            Crée ou remplace une skill : un outil en JavaScript que tu pourras appeler ensuite comme n'importe quel autre.

            Le code reçoit deux variables : `args` (les paramètres passés à l'appel) et `host` (l'accès à l'appareil).
            Il doit retourner une valeur, qui sera sérialisée en JSON et renvoyée comme résultat de l'outil.

            API disponible selon les permissions déclarées :
              host.log(message)
              host.http.get(url, headers) / host.http.post(url, body, headers) / host.http.json(url, options)  [NETWORK]
              host.memory.get(k) / set(k, v) / keys() / getJson(k, defaut) / setJson(k, v)                     [MEMORY]
              host.files.read(chemin) / write(chemin, contenu) / list()                                        [STORAGE]
              host.notify(titre, corps)                                                                        [NOTIFY]
              host.device()                                                                                    [DEVICE_INFO]
              host.ask(prompt, maxTokens)                                                                      [LLM]

            Ne demande que les permissions réellement utilisées : une permission non déclarée fait échouer l'appel.
            Le code est interrompu après le délai imparti, donc pas de boucle d'attente.
        """.trimIndent(),
        parameters = JsonSchema.obj(
            "name" to JsonSchema.string("Identifiant de l'outil, en minuscules avec des underscores"),
            "description" to JsonSchema.string("Ce que fait la skill et quand l'appeler. C'est ce que tu liras plus tard pour décider de l'utiliser."),
            "parameters" to JsonSchema.freeObject("Schéma JSON des arguments, au format {\"type\":\"object\",\"properties\":{...},\"required\":[...]}"),
            "code" to JsonSchema.string("Corps JavaScript de la skill"),
            "permissions" to JsonSchema.stringArray(
                "Parmi NETWORK, STORAGE, MEMORY, LLM, NOTIFY, DEVICE_INFO",
            ),
            "note" to JsonSchema.string("Raison de cette version, conservée dans l'historique"),
            required = listOf("name", "description", "code"),
        ),
        confirm = true,
    ) { args, _ ->
        val name = args.text("name")?.normalizeToolName()
            ?: return@tool "Paramètre 'name' manquant."
        if (name.isBlank()) return@tool "Nom de skill vide."

        val code = args.text("code") ?: return@tool "Paramètre 'code' manquant."
        val description = args.text("description").orEmpty()
        val parameters = args["parameters"]?.let { runCatching { it.jsonObject }.getOrNull() }
            ?: JsonSchema.empty
        val permissions = args.permissions()

        // Refuse to store something that cannot even be parsed. Reporting it here
        // means the model sees the syntax error while the code is still in its
        // context, instead of discovering it at the next call.
        val syntaxCheck = sandbox.validate(code)
        if (syntaxCheck is ScriptResult.Failure) {
            return@tool "Erreur de syntaxe, rien n'a ete enregistre : ${syntaxCheck.message}"
        }

        val existing = store.byName(name)
        val now = System.currentTimeMillis()
        val skill = Skill(
            id = existing?.id ?: UUID.randomUUID().toString(),
            name = name,
            description = description,
            parameters = parameters,
            code = code,
            version = (existing?.version ?: 0) + 1,
            enabled = existing?.enabled ?: true,
            permissions = permissions,
            createdAt = existing?.createdAt ?: now,
            updatedAt = now,
            authoredByModel = true,
        )

        val saved = store.upsert(skill, note = args.text("note").orEmpty())
        onChanged()

        val verb = if (existing == null) "créée" else "mise à jour"
        "Skill '${saved.name}' $verb en version ${saved.version}. " +
            "Elle est appelable immédiatement. Teste-la avec skill_test avant de t'en servir pour de bon."
    }

    private val test = tool(
        name = "skill_test",
        description = "Exécute une skill avec des arguments donnés et renvoie le résultat ou l'erreur, " +
            "sans passer par une vraie conversation. C'est la façon de vérifier une skill que tu viens d'écrire.",
        parameters = JsonSchema.obj(
            "name" to JsonSchema.string("Nom de la skill à tester"),
            "arguments" to JsonSchema.freeObject("Arguments à lui passer"),
            required = listOf("name"),
        ),
    ) { args, context ->
        val name = args.text("name") ?: return@tool "Paramètre 'name' manquant."
        val skill = store.byName(name) ?: return@tool "Skill introuvable : $name"
        val arguments = args["arguments"]?.let { runCatching { it.jsonObject }.getOrNull() }
            ?: JsonObject(emptyMap())

        val runner = SkillTool(skill, sandbox)
        val result = runner.execute(arguments, context)
        store.recordRun(skill.id, result.ok, if (result.ok) null else result.content.take(500))

        buildString {
            append(if (result.ok) "Succès" else "Échec").append(" (").append(result.durationMs).append(" ms)\n")
            append(result.content)
            if (runner.lastRunLog.isNotEmpty()) {
                append("\nJournal :\n")
                runner.lastRunLog.forEach { append("  ").append(it).append('\n') }
            }
        }
    }

    private val remove = tool(
        name = "skill_delete",
        description = "Supprime une skill. Son historique de versions est conservé et elle peut être restaurée.",
        parameters = JsonSchema.obj(
            "name" to JsonSchema.string("Nom de la skill à supprimer"),
            required = listOf("name"),
        ),
        confirm = true,
    ) { args, _ ->
        val name = args.text("name") ?: return@tool "Paramètre 'name' manquant."
        val skill = store.byName(name) ?: return@tool "Skill introuvable : $name"
        store.delete(skill.id)
        onChanged()
        "Skill '$name' supprimée."
    }

    private val restore = tool(
        name = "skill_restore",
        description = "Restaure une version antérieure d'une skill. À utiliser quand une modification a cassé quelque chose.",
        parameters = JsonSchema.obj(
            "name" to JsonSchema.string("Nom de la skill"),
            "version" to JsonSchema.integer("Numéro de version à restaurer"),
            required = listOf("name", "version"),
        ),
        confirm = true,
    ) { args, _ ->
        val name = args.text("name") ?: return@tool "Paramètre 'name' manquant."
        val version = args.text("version")?.toIntOrNull()
            ?: return@tool "Paramètre 'version' manquant ou non entier."
        val skill = store.byName(name) ?: return@tool "Skill introuvable : $name"

        val restored = store.restore(skill.id, version)
            ?: return@tool "Version $version introuvable pour '$name'. " +
                "Versions disponibles : ${store.revisions(skill.id).joinToString { it.version.toString() }}"

        onChanged()
        "Skill '$name' restaurée depuis la version $version, enregistrée en version ${restored.version}."
    }

    // --- plumbing --------------------------------------------------------

    private fun tool(
        name: String,
        description: String,
        parameters: JsonObject,
        confirm: Boolean = false,
        body: suspend (JsonObject, ToolContext) -> String,
    ): Tool = object : Tool {
        override val spec = ToolSpec(
            name = name,
            description = description,
            parameters = parameters,
            requiresConfirmation = confirm && requireConfirmation(),
        )

        override suspend fun execute(arguments: JsonObject, context: ToolContext): ToolResult {
            val startedAt = System.currentTimeMillis()
            return runCatching { body(arguments, context) }
                .fold(
                    onSuccess = {
                        ToolResult(
                            callId = "",
                            name = name,
                            ok = true,
                            content = it,
                            durationMs = System.currentTimeMillis() - startedAt,
                        )
                    },
                    onFailure = {
                        ToolResult(
                            callId = "",
                            name = name,
                            ok = false,
                            content = "Erreur : ${it.message ?: it::class.java.simpleName}",
                            durationMs = System.currentTimeMillis() - startedAt,
                        )
                    },
                )
        }
    }

    private fun JsonObject.text(key: String): String? =
        this[key]?.let { runCatching { it.jsonPrimitive.content }.getOrNull() }

    private fun JsonObject.permissions(): Set<SkillPermission> {
        val raw = this["permissions"] ?: return emptySet()
        val names = runCatching { raw.jsonArray.map { it.jsonPrimitive.content } }
            .getOrElse {
                runCatching { raw.jsonPrimitive.content.split(',') }.getOrElse { emptyList() }
            }
        return names.mapNotNull { candidate ->
            SkillPermission.entries.firstOrNull { it.name.equals(candidate.trim(), ignoreCase = true) }
        }
            // SKILL_ADMIN is never grantable from here: a model-authored skill
            // must not be able to rewrite the other skills behind the agent's back.
            .filterNot { it == SkillPermission.SKILL_ADMIN }
            .toSet()
    }

    /** Models produce names with spaces and capitals; tool names cannot have either. */
    private fun String.normalizeToolName(): String =
        trim().lowercase().replace(Regex("[^a-z0-9_]+"), "_").trim('_')
}
