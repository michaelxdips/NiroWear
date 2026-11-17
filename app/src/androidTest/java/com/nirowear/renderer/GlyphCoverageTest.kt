package com.nirowear.renderer

import android.graphics.Paint
import android.graphics.Typeface
import org.junit.Assert.assertTrue
import org.junit.Test

class GlyphCoverageTest {
    @Test fun requiredCodePointsHaveSystemFallbackGlyphs() {
        val required =
            "α β γ δ ε θ λ μ π σ φ ψ ω Γ Δ Θ Λ Ξ Π Σ Φ Ψ Ω " +
                "± × ÷ √ ∛ ∞ ≈ ≠ ≤ ≥ ∝ ≡ ∂ ∇ ∫ ∬ ∭ ∮ ∑ ∏ " +
                "∈ ∉ ∅ ⊂ ⊆ ⊃ ⊇ ∩ ∪ ∀ ∃ ¬ ∧ ∨ ⇒ ⇔ ⊥ ℏ μ Ω Å ° " +
                "← → ↑ ↓ ↔ ⇐ ⇒ ⇔ 𝐀 𝐁 𝑥 𝑦 𝒇 ℝ ℤ ℕ ℚ ℂ 中文 日本語 한국어 Русский العربية"
        val paint =
            Paint(Paint.ANTI_ALIAS_FLAG).apply {
                typeface = Typeface.DEFAULT
                textSize = 32f
            }
        required.codePoints().distinct().filter { !Character.isWhitespace(it) }.forEach { code ->
            assertTrue("Missing U+${code.toString(16).uppercase()}", paint.hasGlyph(String(Character.toChars(code))))
        }
        // Font fallback evidence only. Actual shaping/baseline/clipping still need screenshots/device.
    }
}
