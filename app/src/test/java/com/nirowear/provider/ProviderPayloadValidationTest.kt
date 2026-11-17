package com.nirowear.provider

import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Assert.*
import org.junit.Test

class ProviderPayloadValidationTest {
    private val protocolFailure = listOf(StreamEvent.Failure(ProviderError.PROTOCOL))
    private val secret = SecretValue("payload-validation-test-key")
    private val messageStart = """{"type":"message_start","message":{"role":"assistant","content":[]}}"""

    @Test fun openAiFinalVisibleFieldsRequireStringsEvenWhenCoercionMatchesDeltas() {
        for ((type, field) in listOf("output_text" to "text", "refusal" to "refusal")) {
            for (value in listOf("null", "42", "true", "{}", "[]")) {
                val decoder = OpenAiStreamDecoder()
                assertEquals(
                    listOf(StreamEvent.TextDelta(value)),
                    decoder.accept(
                        """{"type":"response.${if (type == "output_text") "output_text" else "refusal"}.delta","delta":${JsonPrimitive(
                            value,
                        )}}""",
                    ),
                )
                assertEquals("$type $value", protocolFailure, decoder.accept(openAiCompleted(type, field, value)))
            }
        }
    }

    @Test fun openAiFinalVisibleFieldsRemainRequired() {
        for ((type, field) in listOf("output_text" to "text", "refusal" to "refusal")) {
            val decoder = OpenAiStreamDecoder()
            decoder.accept("""{"type":"response.output_text.delta","delta":"visible"}""")
            assertEquals(
                protocolFailure,
                decoder.accept(
                    openAiCompleted(type, field, "\"visible\"")
                        .replace(",\"$field\":\"visible\"", ""),
                ),
            )
        }
    }

    @Test fun openAiFinalVisibleStringsAreNotMistakenForJsonPrimitives() {
        for ((type, field) in listOf("output_text" to "text", "refusal" to "refusal")) {
            for (value in listOf("null", "42", "true")) {
                val decoder = OpenAiStreamDecoder()
                decoder.accept("""{"type":"response.output_text.delta","delta":${JsonPrimitive(value)}}""")
                assertEquals(
                    listOf(StreamEvent.Completed),
                    decoder.accept(openAiCompleted(type, field, JsonPrimitive(value).toString())),
                )
            }
        }
    }

    @Test fun anthropicBlockStartTextRequiresString() {
        for (value in listOf("null", "42", "true", "{}", "[]")) {
            val decoder = AnthropicStreamDecoder()
            assertEquals(emptyList<StreamEvent>(), decoder.accept(messageStart))
            assertEquals(value, protocolFailure, decoder.accept(anthropicBlock(value)))
        }
    }

    @Test fun anthropicBlockStartAcceptsLiteralStringsThroughCompletion() {
        for (value in listOf("null", "42", "true")) {
            val decoder = AnthropicStreamDecoder()
            decoder.accept(messageStart)
            assertEquals(
                listOf(StreamEvent.TextDelta(value)),
                decoder.accept(anthropicBlock(JsonPrimitive(value).toString())),
            )
            assertEquals(emptyList<StreamEvent>(), decoder.accept("""{"type":"content_block_stop","index":0}"""))
            assertEquals(
                emptyList<StreamEvent>(),
                decoder.accept("""{"type":"message_delta","delta":{"stop_reason":"end_turn"}}"""),
            )
            assertEquals(listOf(StreamEvent.Completed), decoder.accept("""{"type":"message_stop"}"""))
        }
    }

    @Test fun compatibleRejectsNonstringVisibleFieldsAfterPartialText() {
        for (field in listOf("content", "refusal")) {
            for (value in listOf("42", "true", "{}", "[]")) {
                val decoder = CompatibleStreamDecoder()
                assertEquals(
                    listOf(StreamEvent.TextDelta("partial")),
                    decoder.accept("""{"choices":[{"delta":{"content":"partial"}}]}"""),
                )
                assertEquals(
                    "$field $value",
                    protocolFailure,
                    decoder.accept("""{"choices":[{"delta":{"$field":$value},"finish_reason":"stop"}]}"""),
                )
            }
        }
    }

    @Test fun compatibleValidVisibleFieldCannotMaskMalformedSibling() {
        for ((field, other) in listOf("content" to "refusal", "refusal" to "content")) {
            for (value in listOf("42", "true", "{}", "[]")) {
                assertEquals(
                    "$field $value",
                    protocolFailure,
                    CompatibleStreamDecoder().accept(
                        """{"choices":[{"delta":{"$field":$value,"$other":"visible"},"finish_reason":"stop"}]}""",
                    ),
                )
            }
        }
    }

    @Test fun compatibleAllowsNullVisibleFieldsAndOptionalIndex() {
        for (index in listOf("", "\"index\":0,")) {
            val decoder = CompatibleStreamDecoder()
            assertEquals(
                emptyList<StreamEvent>(),
                decoder.accept(
                    """{"choices":[{$index"delta":{"content":null,"refusal":null},"finish_reason":null}]}""",
                ),
            )
            assertEquals(
                listOf(StreamEvent.TextDelta("null")),
                decoder.accept("""{"choices":[{$index"delta":{"content":null,"refusal":"null"}}]}"""),
            )
            assertEquals(
                listOf(StreamEvent.Completed),
                decoder.accept(
                    """{"choices":[{$index"delta":{"content":null,"refusal":null},"finish_reason":"stop"}]}""",
                ),
            )
        }
        assertEquals(
            emptyList<StreamEvent>(),
            CompatibleStreamDecoder().accept("""{"choices":[],"usage":{"total_tokens":1}}"""),
        )
    }

    @Test fun compatibleRendersOnlyIndexZeroAndIgnoresOtherChoices() {
        assertEquals(
            listOf(StreamEvent.TextDelta("first"), StreamEvent.Completed),
            CompatibleStreamDecoder().accept(
                """{"choices":[{"index":1,"delta":{"content":"second"},"finish_reason":"stop"},{"index":0,"delta":{"content":"first"},"finish_reason":"stop"}]}""",
            ),
        )
        assertEquals(
            listOf(StreamEvent.TextDelta("only")),
            CompatibleStreamDecoder().accept("""{"choices":[{"index":0,"delta":{"content":"only"}}]}"""),
        )
    }

    @Test fun compatibleIndexMustBeUnquotedIntegerWhenPresent() {
        for (index in listOf("\"0\"", "true", "0.5", "{}", "[]")) {
            assertEquals(
                index,
                protocolFailure,
                CompatibleStreamDecoder().accept(
                    """{"choices":[{"index":$index,"delta":{"content":"visible"},"finish_reason":"stop"}]}""",
                ),
            )
        }
    }

    @Test fun compatibleRejectsNonstringFinishReason() {
        for (finish in listOf("42", "true", "{}", "[]")) {
            val decoder = CompatibleStreamDecoder()
            decoder.accept("""{"choices":[{"delta":{"content":"partial"}}]}""")
            assertEquals(
                finish,
                protocolFailure,
                decoder.accept("""{"choices":[{"delta":{},"finish_reason":$finish}]}"""),
            )
        }
    }

    @Test fun compatibleDiscoverySkipsNonstringIdsWithoutDiscardingValidSiblings() =
        runBlocking {
            MockWebServer().use { server ->
                val provider = OpenAiCompatibleProvider(allowLocalHttp = true)
                val profile = profile(ProviderType.OPENAI_COMPATIBLE, server)
                for (id in listOf("null", "42", "true", "{}", "[]")) {
                    server.enqueue(MockResponse().setBody("""{"data":[{"id":"beta"},{"id":$id},{"id":"alpha"}]}"""))
                    assertEquals(
                        id,
                        ModelDiscoveryResult.Models(listOf("alpha", "beta")),
                        provider.listModels(profile, secret),
                    )
                }
            }
        }

    @Test fun compatibleDiscoverySkipsNonobjectsWithoutDiscardingValidSiblings() =
        runBlocking {
            MockWebServer().use { server ->
                val provider = OpenAiCompatibleProvider(allowLocalHttp = true)
                val profile = profile(ProviderType.OPENAI_COMPATIBLE, server)
                for (entry in listOf("null", "42", "true", "[]", "\"not an object\"")) {
                    server.enqueue(MockResponse().setBody("""{"data":[{"id":"beta"},$entry,{"id":"alpha"}]}"""))
                    assertEquals(
                        entry,
                        ModelDiscoveryResult.Models(listOf("alpha", "beta")),
                        provider.listModels(profile, secret),
                    )
                }
            }
        }

    @Test fun compatibleDiscoveryDoesNotInventModelsFromMalformedEntries() =
        runBlocking {
            MockWebServer().use { server ->
                server.enqueue(MockResponse().setBody("""{"data":[{"id":null},{"id":42},{"id":true},null,{},[]]}"""))
                assertEquals(
                    ModelDiscoveryResult.Unavailable(ProviderError.PROTOCOL),
                    OpenAiCompatibleProvider(
                        allowLocalHttp = true,
                    ).listModels(profile(ProviderType.OPENAI_COMPATIBLE, server), secret),
                )
            }
        }

    @Test fun geminiDiscoverySkipsMalformedCapabilitiesWithoutDiscardingValidSiblings() =
        runBlocking {
            MockWebServer().use { server ->
                val provider = GeminiProvider(allowLocalHttp = true)
                val profile = profile(ProviderType.GEMINI, server)
                for (methods in listOf(
                    "null",
                    "{}",
                    "42",
                    "true",
                    "\"generateContent\"",
                    "[null,\"generateContent\"]",
                    "[{},\"generateContent\"]",
                )) {
                    server.enqueue(
                        MockResponse().setBody(
                            """{"models":[{"name":"models/beta","supportedGenerationMethods":["generateContent"]},{"name":"models/bad","supportedGenerationMethods":$methods},{"name":"models/alpha","supportedGenerationMethods":["generateContent"]}]}""",
                        ),
                    )
                    assertEquals(
                        methods,
                        ModelDiscoveryResult.Models(listOf("alpha", "beta")),
                        provider.listModels(profile, secret),
                    )
                }
            }
        }

    @Test fun discoveryCatalogLimitRejectsWholeResultInsteadOfTruncating() =
        runBlocking {
            for (type in ProviderType.entries) {
                MockWebServer().use { server ->
                    server.enqueue(MockResponse().setBody(catalog(type, 0..10_000)))
                    assertEquals(
                        type.name,
                        ModelDiscoveryResult.Unavailable(ProviderError.PROTOCOL),
                        provider(type).listModels(profile(type, server), secret),
                    )
                }
            }
        }

    @Test fun discoveryCatalogLimitAllowsBoundaryAndDeduplicates() =
        runBlocking {
            for (type in ProviderType.entries) {
                MockWebServer().use { server ->
                    val ids = (0 until 10_000).map { "model-$it" }
                    server.enqueue(MockResponse().setBody(catalog(type, (0 until 10_000).toList() + 0)))
                    assertEquals(
                        type.name,
                        ModelDiscoveryResult.Models(ids.sorted()),
                        provider(type).listModels(profile(type, server), secret),
                    )
                }
            }
        }

    @Test fun nativeDiscoveryCapsAggregateAcrossPages() =
        runBlocking {
            for (type in listOf(ProviderType.ANTHROPIC, ProviderType.GEMINI)) {
                MockWebServer().use { server ->
                    server.enqueue(MockResponse().setBody(catalog(type, 0 until 6_000, "next")))
                    server.enqueue(MockResponse().setBody(catalog(type, 6_000..10_000)))
                    assertEquals(
                        type.name,
                        ModelDiscoveryResult.Unavailable(ProviderError.PROTOCOL),
                        provider(type).listModels(profile(type, server), secret),
                    )
                    assertEquals(2, server.requestCount)
                }
            }
        }

    @Test fun anthropicPositiveHasMoreRequiresUsableBoundedCursor() =
        runBlocking {
            MockWebServer().use { server ->
                val provider = AnthropicProvider(allowLocalHttp = true)
                val profile = profile(ProviderType.ANTHROPIC, server)
                val cursors =
                    listOf(
                        "null",
                        "42",
                        "true",
                        "{}",
                        "[]",
                        "\"\"",
                        "\" \"",
                        JsonPrimitive("x".repeat(4097)).toString(),
                    )
                for (cursor in cursors + null) {
                    val field = cursor?.let { ",\"last_id\":$it" }.orEmpty()
                    server.enqueue(MockResponse().setBody("""{"data":[{"id":"valid"}],"has_more":true$field}"""))
                    assertEquals(
                        cursor ?: "missing",
                        ModelDiscoveryResult.Unavailable(ProviderError.PROTOCOL),
                        provider.listModels(profile, secret),
                    )
                }
                assertEquals(cursors.size + 1, server.requestCount)
            }
        }

    @Test fun anthropicMalformedHasMoreCannotSilentlyReturnPartialCatalog() =
        runBlocking {
            MockWebServer().use { server ->
                val provider = AnthropicProvider(allowLocalHttp = true)
                val profile = profile(ProviderType.ANTHROPIC, server)
                for (hasMore in listOf("null", "42", "\"false\"", "{}", "[]")) {
                    server.enqueue(MockResponse().setBody("""{"data":[{"id":"valid"}],"has_more":$hasMore}"""))
                    assertEquals(
                        hasMore,
                        ModelDiscoveryResult.Unavailable(ProviderError.PROTOCOL),
                        provider.listModels(profile, secret),
                    )
                }
            }
        }

    @Test fun geminiMalformedCursorCannotSilentlyReturnPartialCatalog() =
        runBlocking {
            MockWebServer().use { server ->
                val provider = GeminiProvider(allowLocalHttp = true)
                val profile = profile(ProviderType.GEMINI, server)
                val cursors = listOf("42", "true", "{}", "[]", "\" \"", JsonPrimitive("x".repeat(4097)).toString())
                for (cursor in cursors) {
                    server.enqueue(
                        MockResponse().setBody(
                            """{"models":[{"name":"models/valid","supportedGenerationMethods":["generateContent"]}],"nextPageToken":$cursor}""",
                        ),
                    )
                    assertEquals(
                        cursor,
                        ModelDiscoveryResult.Unavailable(ProviderError.PROTOCOL),
                        provider.listModels(profile, secret),
                    )
                }
                assertEquals(cursors.size, server.requestCount)
            }
        }

    @Test fun geminiNullOrEmptyCursorRemainsValidTerminalPage() =
        runBlocking {
            MockWebServer().use { server ->
                val provider = GeminiProvider(allowLocalHttp = true)
                val profile = profile(ProviderType.GEMINI, server)
                for (cursor in listOf("null", "\"\"")) {
                    server.enqueue(
                        MockResponse().setBody(
                            """{"models":[{"name":"models/valid","supportedGenerationMethods":["generateContent"]}],"nextPageToken":$cursor}""",
                        ),
                    )
                    assertEquals(ModelDiscoveryResult.Models(listOf("valid")), provider.listModels(profile, secret))
                }
            }
        }

    private fun openAiCompleted(
        type: String,
        field: String,
        value: String,
    ) =
        """{"type":"response.completed","response":{"status":"completed","output":[{"type":"message","role":"assistant","status":"completed","content":[{"type":"$type","$field":$value}]}]}}"""

    private fun anthropicBlock(value: String) =
        """{"type":"content_block_start","index":0,"content_block":{"type":"text","text":$value}}"""

    private fun profile(
        type: ProviderType,
        server: MockWebServer,
    ) = ProviderProfile(
        "id",
        type,
        "Test",
        server
            .url(
                if (type ==
                    ProviderType.GEMINI
                ) {
                    "/v1beta"
                } else {
                    "/v1"
                },
            ).toString(),
        "selected",
        "ref",
    )

    private fun provider(type: ProviderType): LlmProvider =
        when (type) {
            ProviderType.OPENAI_COMPATIBLE -> OpenAiCompatibleProvider(allowLocalHttp = true)
            ProviderType.OPENAI -> OpenAiProvider(allowLocalHttp = true)
            ProviderType.GEMINI -> GeminiProvider(allowLocalHttp = true)
            ProviderType.ANTHROPIC -> AnthropicProvider(allowLocalHttp = true)
        }

    private fun catalog(
        type: ProviderType,
        indices: Iterable<Int>,
        cursor: String? = null,
    ) = buildJsonObject {
        put(
            if (type == ProviderType.GEMINI) "models" else "data",
            buildJsonArray {
                indices.forEach { index ->
                    add(
                        buildJsonObject {
                            if (type == ProviderType.GEMINI) {
                                put("name", "models/model-$index")
                                put("supportedGenerationMethods", buildJsonArray { add("generateContent") })
                            } else {
                                put("id", "model-$index")
                            }
                        },
                    )
                }
            },
        )
        if (type == ProviderType.ANTHROPIC) {
            put("has_more", cursor != null)
            cursor?.let { put("last_id", it) }
        } else if (type == ProviderType.GEMINI) {
            cursor?.let { put("nextPageToken", it) }
        }
    }.toString()
}
