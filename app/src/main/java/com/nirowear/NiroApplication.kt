package com.nirowear

import android.app.Application
import androidx.room.Room
import com.nirowear.data.ChatDatabase
import com.nirowear.data.ProviderSettings
import com.nirowear.provider.AnthropicProvider
import com.nirowear.provider.GeminiProvider
import com.nirowear.provider.LlmProvider
import com.nirowear.provider.OpenAiCompatibleProvider
import com.nirowear.provider.OpenAiProvider
import com.nirowear.provider.ProviderType
import com.nirowear.security.SecretStore

class NiroApplication : Application() {
    val secrets by lazy { SecretStore(this) }
    val settings by lazy { ProviderSettings(this, secrets) }
    val database by lazy {
        Room
            .databaseBuilder(
                this,
                ChatDatabase::class.java,
                "chats.db",
            ).addMigrations(ChatDatabase.MIGRATION_1_2)
            .build()
    }
    private val compatible by lazy { OpenAiCompatibleProvider(allowLocalHttp = BuildConfig.DEBUG) }
    private val openAi by lazy { OpenAiProvider(allowLocalHttp = BuildConfig.DEBUG) }
    private val gemini by lazy { GeminiProvider(allowLocalHttp = BuildConfig.DEBUG) }
    private val anthropic by lazy { AnthropicProvider(allowLocalHttp = BuildConfig.DEBUG) }

    fun provider(type: ProviderType): LlmProvider? =
        when (type) {
            ProviderType.OPENAI_COMPATIBLE -> compatible
            ProviderType.OPENAI -> openAi
            ProviderType.GEMINI -> gemini
            ProviderType.ANTHROPIC -> anthropic
        }
}
