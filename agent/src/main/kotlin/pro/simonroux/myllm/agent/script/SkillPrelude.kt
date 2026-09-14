package pro.simonroux.myllm.agent.script

/**
 * The API a skill is written against, defined in JavaScript on top of the single
 * string bridge.
 *
 * Keeping it here rather than in Kotlin means the surface a skill sees is one
 * readable file: when the agent writes a new skill it is shown this contract,
 * and when a call fails the error names a function that exists in this text.
 *
 * Every call is synchronous. Skills are short-lived and run off the main thread,
 * and a promise-based API would need an event loop the sandbox deliberately
 * does not have.
 */
internal val PRELUDE: String = """
var host = (function (bridge) {
    function call(channel, payload) {
        var raw = bridge(channel, JSON.stringify(payload || {}));
        var parsed = JSON.parse(raw);
        if (parsed && parsed.error) {
            throw new Error(parsed.error);
        }
        return parsed;
    }

    function asBody(body) {
        if (body === null || body === undefined) return null;
        return typeof body === 'string' ? body : JSON.stringify(body);
    }

    return {
        /** Writes to the skill's run log, visible in the skill editor. */
        log: function (message) {
            call('log', { message: String(message) });
        },

        http: {
            /** @returns {{status: number, body: string, headers: object}} */
            request: function (options) {
                return call('http', {
                    method: (options.method || 'GET').toUpperCase(),
                    url: String(options.url),
                    headers: options.headers || {},
                    body: asBody(options.body)
                });
            },
            get: function (url, headers) {
                return this.request({ method: 'GET', url: url, headers: headers });
            },
            post: function (url, body, headers) {
                return this.request({ method: 'POST', url: url, body: body, headers: headers });
            },
            /** Convenience wrapper that parses the response body. */
            json: function (url, options) {
                var opts = options || {};
                opts.url = url;
                var response = this.request(opts);
                if (response.status < 200 || response.status >= 300) {
                    throw new Error('HTTP ' + response.status + ': ' + response.body.slice(0, 200));
                }
                return JSON.parse(response.body);
            }
        },

        /** Survives restarts. Scoped to this skill, invisible to the others. */
        memory: {
            get: function (key) { return call('memory.get', { key: String(key) }).value; },
            set: function (key, value) {
                call('memory.set', {
                    key: String(key),
                    value: (value === null || value === undefined) ? null : String(value)
                });
            },
            remove: function (key) { call('memory.set', { key: String(key), value: null }); },
            keys: function () { return call('memory.keys', {}).keys; },
            getJson: function (key, fallback) {
                var raw = this.get(key);
                if (raw === null || raw === undefined) return fallback;
                try { return JSON.parse(raw); } catch (e) { return fallback; }
            },
            setJson: function (key, value) { this.set(key, JSON.stringify(value)); }
        },

        /** The skill's own private directory. Paths are relative and cannot escape it. */
        files: {
            read: function (path) { return call('files.read', { path: String(path) }).content; },
            write: function (path, content) {
                call('files.write', { path: String(path), content: String(content) });
            },
            list: function () { return call('files.list', {}).files; }
        },

        notify: function (title, body) {
            call('notify', { title: String(title), body: String(body || '') });
        },

        /** Clock, locale, battery, connectivity. */
        device: function () { return call('device', {}); },

        /** Runs a sub-completion on the currently selected model. */
        ask: function (prompt, maxTokens) {
            return call('ask', { prompt: String(prompt), maxTokens: maxTokens || 512 }).text;
        }
    };
})(__bridge);

// The bridge is captured in the closure above and then taken out of scope, so a
// skill can neither call it directly nor swap it for its own implementation to
// fake host responses to itself.
try { delete __bridge; } catch (e) { }
__bridge = undefined;

// Freezing keeps a skill from monkeypatching the API in place. Each run gets a
// fresh scope anyway, so this only guards a skill against itself, but a skill
// that silently rewired host.http would be very hard to debug.
if (typeof Object.freeze === 'function') {
    Object.freeze(host.http);
    Object.freeze(host.memory);
    Object.freeze(host.files);
    Object.freeze(host);
}
""".trimIndent()
