package pro.simonroux.myllm.agent.script

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The sandbox runs code nobody read. These tests are the reason that is
 * acceptable, so each one names the escape it closes.
 */
class ScriptSandboxTest {

    private val sandbox = ScriptSandbox()

    /** Records what crossed the bridge, so a test can assert on it. */
    private val calls = mutableListOf<Pair<String, String>>()

    private fun run(code: String, args: String = "{}", timeoutMs: Long = 2_000): ScriptResult =
        sandbox.execute(code, args, timeoutMs) { channel, payload ->
            calls += channel to payload
            "{}"
        }

    @Test
    fun `returns a JSON value`() {
        val result = run("return {sum: args.a + args.b};", """{"a":2,"b":3}""")
        assertTrue(result is ScriptResult.Success)
        assertEquals("""{"sum":5}""", (result as ScriptResult.Success).json)
    }

    @Test
    fun `modern syntax is available`() {
        val result = run("const doubled = [1,2,3].map(n => n * 2); return doubled.join('-');")
        assertEquals("2-4-6", (result as ScriptResult.Success).json)
    }

    @Test
    fun `an infinite loop is killed rather than hanging the app`() {
        val startedAt = System.currentTimeMillis()
        val result = run("while (true) {}", timeoutMs = 300)
        val elapsed = System.currentTimeMillis() - startedAt

        assertTrue("expected a failure, got $result", result is ScriptResult.Failure)
        assertTrue("took ${elapsed}ms, deadline was 300ms", elapsed < 5_000)
    }

    @Test
    fun `java packages are not reachable`() {
        val result = run("return java.lang.System.getProperty('user.home');")
        assertTrue(result is ScriptResult.Failure)
    }

    @Test
    fun `Packages is not reachable`() {
        val result = run("return Packages.java.io.File.listRoots().length;")
        assertTrue(result is ScriptResult.Failure)
    }

    @Test
    fun `getClass cannot be used to walk back into the JVM`() {
        val result = run("return ({}).getClass().getName();")
        assertTrue(result is ScriptResult.Failure)
    }

    @Test
    fun `a host call reaches the bridge with its channel and payload`() {
        val result = run("host.log('bonjour'); return 'ok';")

        assertTrue("script failed: $result", result is ScriptResult.Success)
        assertEquals(1, calls.size)
        assertEquals("log", calls.single().first)
        assertTrue(calls.single().second.contains("bonjour"))
    }

    @Test
    fun `the raw bridge is out of scope once the prelude has run`() {
        val result = run("return typeof __bridge;")

        // A plain string comes back as-is rather than JSON-quoted.
        assertEquals("undefined", (result as ScriptResult.Success).json)
    }

    @Test
    fun `replacing the bridge does not intercept host calls`() {
        // The prelude captured the real bridge in a closure, so reassigning the
        // global cannot make a skill feed itself fake host responses.
        val result = run("__bridge = function () { return '{}'; }; host.log('x'); return 'ok';")

        assertTrue("script failed: $result", result is ScriptResult.Success)
        assertEquals(1, calls.size)
        assertEquals("log", calls.single().first)
    }

    @Test
    fun `the host API cannot be monkeypatched in place`() {
        val result = run("host.log = function () {}; host.log('x'); return 'ok';")

        assertTrue(result is ScriptResult.Success)
        assertEquals("the replacement took effect", 1, calls.size)
    }

    @Test
    fun `a syntax error is reported without executing anything`() {
        val result = sandbox.validate("return (;")
        assertTrue(result is ScriptResult.Failure)
    }

    @Test
    fun `valid code passes validation`() {
        assertTrue(sandbox.validate("return args.x * 2;") is ScriptResult.Success)
    }

    @Test
    fun `a thrown error is reported with its message`() {
        val result = run("throw new Error('boom');")
        assertTrue(result is ScriptResult.Failure)
        assertTrue((result as ScriptResult.Failure).message.contains("boom"))
    }

    @Test
    fun `an error raised by the bridge surfaces in the script`() {
        val denying = ScriptSandbox()
        val result = denying.execute("host.notify('a','b'); return 'unreachable';", "{}", 2_000) { _, _ ->
            """{"error":"Permission NOTIFY non accordee"}"""
        }
        assertTrue(result is ScriptResult.Failure)
        assertTrue((result as ScriptResult.Failure).message.contains("NOTIFY"))
    }
}
