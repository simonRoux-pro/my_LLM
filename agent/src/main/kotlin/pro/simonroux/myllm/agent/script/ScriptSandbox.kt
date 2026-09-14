package pro.simonroux.myllm.agent.script

import org.mozilla.javascript.ClassShutter
import org.mozilla.javascript.Context
import org.mozilla.javascript.ContextFactory
import org.mozilla.javascript.Function
import org.mozilla.javascript.RhinoException
import org.mozilla.javascript.Scriptable
import org.mozilla.javascript.ScriptableObject
import org.mozilla.javascript.Undefined

/**
 * Runs untrusted JavaScript with no way out.
 *
 * Three things make this safe enough to run code an LLM wrote without reading it:
 *
 *  - The scope is built with initSafeStandardObjects, so `Packages`, `java`,
 *    `importClass` and `getClass` are not there to begin with.
 *  - A ClassShutter refuses every Java class by name, which closes the paths
 *    that would otherwise reach the JVM through a leaked object reference.
 *  - The only host object in scope is one function taking two strings and
 *    returning a string. A script cannot reach an object it was never handed,
 *    and strings carry no reflective surface.
 *
 * Execution is also bounded: the interpreter reports back every few thousand
 * instructions and a script past its deadline is killed, so `while(true)` costs
 * a few milliseconds rather than the battery.
 *
 * Rhino runs interpreted (optimization level -1). That is not a tuning choice:
 * the optimizing path generates JVM bytecode at runtime, which Android does not
 * execute.
 */
class ScriptSandbox {

    /**
     * @param code the skill body. `args` and `host` are in scope; the value it
     *   returns is serialised back as JSON.
     * @param argumentsJson the tool arguments, as a JSON object.
     * @param bridge receives (channel, jsonPayload) and returns a JSON string.
     *   This is the single door to the device.
     */
    fun execute(
        code: String,
        argumentsJson: String,
        timeoutMs: Long,
        bridge: (channel: String, payload: String) -> String,
    ): ScriptResult {
        val factory = DeadlineContextFactory(System.currentTimeMillis() + timeoutMs)
        val context = factory.enterContext()

        return try {
            context.languageVersion = Context.VERSION_ES6
            context.optimizationLevel = -1
            context.instructionObserverThreshold = INSTRUCTION_CHECK_INTERVAL
            context.setClassShutter(DenyAllClasses)

            val scope: ScriptableObject = context.initSafeStandardObjects(null, false)

            ScriptableObject.putProperty(scope, BRIDGE_NAME, BridgeFunction(bridge))

            // The prelude turns the single bridge call into an API worth writing
            // against, and is evaluated before the untrusted code so the skill
            // cannot redefine it for a later call in the same scope.
            context.evaluateString(scope, PRELUDE, "prelude.js", 1, null)

            val factoryFn = context.evaluateString(
                scope,
                "(function(args, host) {\n$code\n})",
                "skill.js",
                1,
                null,
            ) as? Function ?: return ScriptResult.Failure("Le code de la skill n'est pas exécutable")

            val parsedArgs = context.evaluateString(
                scope,
                "(${argumentsJson.ifBlank { "{}" }})",
                "args.js",
                1,
                null,
            )
            val hostObject = ScriptableObject.getProperty(scope, "host")

            val returned = factoryFn.call(context, scope, scope, arrayOf(parsedArgs, hostObject))
            ScriptResult.Success(stringify(context, scope, returned))
        } catch (e: DeadlineExceeded) {
            ScriptResult.Failure("Interrompue après ${timeoutMs} ms")
        } catch (e: RhinoException) {
            ScriptResult.Failure(e.describe() + lineSuffix(e))
        } catch (e: StackOverflowError) {
            ScriptResult.Failure("Récursion infinie")
        } catch (e: Throwable) {
            ScriptResult.Failure(e.message ?: e::class.java.simpleName)
        } finally {
            Context.exit()
        }
    }

    /**
     * Compiles the skill body without running it.
     *
     * Used before a skill is stored, so a syntax error is reported back to the
     * model while the code is still in its context rather than surfacing at the
     * next call, and so a broken skill never reaches the registry.
     */
    fun validate(code: String): ScriptResult {
        val factory = DeadlineContextFactory(System.currentTimeMillis() + VALIDATION_BUDGET_MS)
        val context = factory.enterContext()
        return try {
            context.languageVersion = Context.VERSION_ES6
            context.optimizationLevel = -1
            context.setClassShutter(DenyAllClasses)
            context.compileString("(function(args, host) {\n$code\n})", "skill.js", 1, null)
            ScriptResult.Success("{}")
        } catch (e: RhinoException) {
            ScriptResult.Failure(e.describe() + lineSuffix(e))
        } catch (e: Throwable) {
            ScriptResult.Failure(e.message ?: e::class.java.simpleName)
        } finally {
            Context.exit()
        }
    }

    private fun stringify(context: Context, scope: Scriptable, value: Any?): String = when {
        value == null || value is Undefined -> "null"
        value is CharSequence -> value.toString()
        else -> runCatching {
            val json = ScriptableObject.getProperty(scope, "JSON") as Scriptable
            val fn = ScriptableObject.getProperty(json, "stringify") as Function
            fn.call(context, scope, json, arrayOf(value)) as? String ?: value.toString()
        }.getOrElse { Context.toString(value) }
    }

    private fun RhinoException.describe(): String =
        (this as? org.mozilla.javascript.EcmaError)?.errorMessage
            ?: (this as? org.mozilla.javascript.JavaScriptException)?.value?.toString()
            ?: message
            ?: "Erreur JavaScript"

    private fun lineSuffix(e: RhinoException): String =
        if (e.lineNumber() > 0) " (ligne ${e.lineNumber()})" else ""

    private companion object {
        const val BRIDGE_NAME = "__bridge"

        /**
         * Low enough that a tight loop is caught in single-digit milliseconds,
         * high enough that the check is not itself the bottleneck.
         */
        const val INSTRUCTION_CHECK_INTERVAL = 10_000

        /** Parsing is fast; anything slower than this is a pathological input. */
        const val VALIDATION_BUDGET_MS = 2_000L
    }
}

sealed interface ScriptResult {
    data class Success(val json: String) : ScriptResult
    data class Failure(val message: String) : ScriptResult
}

/** Raised from the instruction observer to unwind a script that ran too long. */
internal class DeadlineExceeded : Error("script deadline exceeded")

private class DeadlineContextFactory(private val deadline: Long) : ContextFactory() {

    override fun observeInstructionCount(cx: Context?, instructionCount: Int) {
        if (System.currentTimeMillis() > deadline) throw DeadlineExceeded()
    }
}

/**
 * Denies every Java class, by name, unconditionally.
 *
 * initSafeStandardObjects already removes the documented entry points; this
 * closes the undocumented ones. There is no allowlist on purpose: a skill that
 * needs to reach the device does it through the bridge, not through a class.
 */
private object DenyAllClasses : ClassShutter {
    override fun visibleToScripts(fullClassName: String): Boolean = false
}

/**
 * The only host object a skill can see: `__bridge(channel, payloadJson)`.
 *
 * Arguments and return value are strings, so nothing crossing this boundary
 * carries a Java identity a script could reflect on.
 */
private class BridgeFunction(
    private val handler: (String, String) -> String,
) : org.mozilla.javascript.BaseFunction() {

    override fun call(
        cx: Context?,
        scope: Scriptable?,
        thisObj: Scriptable?,
        args: Array<out Any?>?,
    ): Any {
        val channel = args?.getOrNull(0)?.let { Context.toString(it) }.orEmpty()
        val payload = args?.getOrNull(1)?.let { Context.toString(it) } ?: "{}"
        return handler(channel, payload)
    }

    override fun getFunctionName(): String = "__bridge"

    override fun getArity(): Int = 2
}
