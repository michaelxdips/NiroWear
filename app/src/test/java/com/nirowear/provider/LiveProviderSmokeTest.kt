package com.nirowear.provider

import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assume
import org.junit.Test

/**
 * TEMPORARY live check, not committed: reads the endpoint, model and key from environment variables
 * so no credential appears in a command line, a file in the repo, or a log line.
 */
class LiveProviderSmokeTest {
    private fun report(line: String) {
        println(line)
        runCatching {
            java.io.File(System.getProperty("java.io.tmpdir"), "nirowear-live.txt")
                .appendText(line + "\n")
        }
    }
    @Test fun streamsFromConfiguredEndpoint() =
        runBlocking {
            val base = System.getenv("NIROWEAR_LIVE_BASE").orEmpty()
            val key = System.getenv("NIROWEAR_LIVE_KEY").orEmpty()
            Assume.assumeTrue("live env not set", base.isNotBlank() && key.isNotBlank())

            val provider = OpenAiCompatibleProvider()
            val profile =
                ProviderProfile(
                    "live",
                    ProviderType.OPENAI_COMPATIBLE,
                    "Live",
                    base,
                    "pending",
                    "live-ref",
                )
            val secret = SecretValue(key)

            val discovery =
                withTimeout(60_000) {
                    provider.listModels(profile, secret)
                }
            val ids =
                when (discovery) {
                    is ModelDiscoveryResult.Models -> discovery.ids
                    is ModelDiscoveryResult.Unavailable -> {
                        println("LIVE discovery unavailable: ${discovery.error}")
                        emptyList()
                    }
                }
            report("LIVE models=${ids.size} sample=${ids.take(12)}")
            val chosen =
                System.getenv("NIROWEAR_LIVE_MODEL").orEmpty().ifBlank {
                    ids.firstOrNull { it.contains("luna", ignoreCase = true) }.orEmpty()
                }
            check(chosen.isNotBlank()) { "no model id matched 'luna'; see LIVE models output" }
            report("LIVE model=$chosen")

            val runProfile = profile.copy(selectedModelId = chosen)
            val text = StringBuilder()
            var terminal = "NONE"
            try {
                withTimeout(90_000) {
                    provider
                        .streamChat(
                            ChatRequest(messages = listOf(ChatMessage(ChatRole.USER, "Reply with exactly: pong"))),
                            runProfile,
                            secret,
                        ).collect { event ->
                            when (event) {
                                is StreamEvent.TextDelta -> text.append(event.text)
                                StreamEvent.Completed -> terminal = "COMPLETED"
                                StreamEvent.Truncated -> terminal = "TRUNCATED"
                                is StreamEvent.Failure -> terminal = "FAILURE:${event.error}"
                                else -> Unit
                            }
                        }
                }
            } catch (t: Throwable) {
                terminal = "THROW:${t::class.simpleName}"
            }
            report("LIVE chars=${text.length} head=${text.toString().take(40).replace("\n", " ")} terminal=$terminal")
            check(text.isNotEmpty()) { "no text deltas arrived" }
            check(terminal == "COMPLETED" || terminal == "TRUNCATED") { "terminal=$terminal" }
        }
}
