// SPDX-License-Identifier: Apache-2.0
package app.grom.server.superserver

/**
 * The fixed set of plugins the public data plane can serve (D39, S5-R020).
 *
 * Registration happens in code at start time. There is no class loading, no
 * script directory and no network-installed plugin: a compromised request can
 * call the plugins that exist and nothing else.
 */
class PluginRegistry(plugins: List<Plugin>) {

    private val byId: Map<String, Plugin> = plugins.associateBy { it.id }

    init {
        require(byId.size == plugins.size) { "duplicate plugin id" }
        for (plugin in plugins) {
            require(PLUGIN_ID.matches(plugin.id)) { "invalid plugin id: ${plugin.id}" }
        }
    }

    fun all(): List<Plugin> = byId.values.toList()

    fun find(id: String): Plugin? = byId[id]

    fun ids(): List<String> = byId.keys.toList()

    private companion object {
        val PLUGIN_ID = Regex("^[a-z][a-z0-9-]{1,31}$")
    }
}
