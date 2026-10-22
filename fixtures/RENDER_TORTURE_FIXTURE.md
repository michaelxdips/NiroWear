# Render Torture Fixture

This fixture exists to break the NiroWear renderer before users do.

| Position | Marker | Meaning |
|---|---|---|
| Top | TOP-01 | First row remains associated |
| Top | TOP-02 | Every cell survives vertical reading |

## Long prose

A smartwatch display is physically small, but the information shown on it does not have to be shallow. NiroWear should preserve useful detail while making every paragraph, equation, list, table, and code block readable through a stable vertical reading experience.

## Formatting

**Bold text**, *italic text*, and `inline_code()` should remain visually distinct without becoming noisy.

> A blockquote should be readable and should not consume excessive horizontal space.

- First bullet
- Second bullet with a longer sentence that wraps across multiple lines on a round display
- Third bullet

1. First numbered item
2. Second numbered item
3. Third numbered item

## Unicode and symbols

Greek:
α β γ δ ε θ λ μ π σ φ ψ ω Γ Δ Θ Λ Ξ Π Σ Φ Ψ Ω

Operators:
± × ÷ √ ∛ ∞ ≈ ≠ ≤ ≥ ∝ ≡ ≜ ⊕ ⊗ ⊥

Calculus:
∂ ∇ ∫ ∬ ∭ ∮ ∑ ∏

Sets:
∈ ∉ ∅ ⊂ ⊆ ⊃ ⊇ ∩ ∪ ℕ ℤ ℚ ℝ ℂ

Logic:
∀ ∃ ¬ ∧ ∨ ⇒ ⇔ ⊥ ∴ ∵

Arrows:
← → ↑ ↓ ↔ ⇐ ⇒ ⇔

Physics/science:
ℏ μ Ω Å ° ′ ″

Math alphabets:
𝐀 𝐁 𝑥 𝑦 𝒇 𝔸 ℝ ℤ ℕ ℚ ℂ

CJK:
中文 日本語 한국어

Cyrillic:
Русский текст

Arabic:
العربية

Emoji:
✓ ✕ ⚠ ★ →

## Inline math

Einstein's equation is $E = mc^2$ and the circle identity is $x^2+y^2=r^2$.

Long fraction: $\frac{a_1+a_2+a_3+a_4+a_5+a_6+a_7+a_8}{b_1+b_2+b_3+b_4+b_5+b_6+b_7+b_8}$ stays readable; surrounding prose keeps normal size.

Nested radicals: $\sqrt{a+\sqrt{b+\sqrt{c+\sqrt{d+\sqrt{e+\sqrt{f+\sqrt{g+h+i+j+k}}}}}}}$ keeps natural size.

Long indices: $X_{i_1,i_2,i_3,i_4,i_5,i_6,i_7,i_8}^{n_1+n_2+n_3+n_4+n_5+n_6+n_7+n_8}$ never shrinks paragraph.

Vector equation: $\mathbf{F}_{\mathrm{total}}=\mathbf{F}_{\mathrm{gravity}}+\mathbf{F}_{\mathrm{electric}}+\mathbf{F}_{\mathrm{magnetic}}+\mathbf{F}_{\mathrm{friction}}$ promotes when too wide.

Function signature: $f(x_1,x_2,x_3,x_4,x_5,x_6,x_7,x_8,x_9,x_{10})=x_1+x_2+x_3+x_4+x_5$ supports horizontal pan.

Wide matrix: $\begin{bmatrix}11&12&13&14&15&16&17&18\\21&22&23&24&25&26&27&28\\31&32&33&34&35&36&37&38\end{bmatrix}$ retains every column.

## Display math

\[
\frac{-b \pm \sqrt{b^2-4ac}}{2a}
\]

\[
\int_{-\infty}^{\infty}e^{-x^2}\,dx=\sqrt{\pi}
\]

\[
\lim_{x\to0}\frac{\sin x}{x}=1
\]

\[
\nabla\times\mathbf{E}
=
-\frac{\partial\mathbf{B}}{\partial t}
\]

## Matrix

\[
\begin{bmatrix}
1 & 2 & 3 \\
4 & 5 & 6 \\
7 & 8 & 9
\end{bmatrix}
\]

## Cases

\[
f(x)=
\begin{cases}
x^2 & x\ge 0\\
-x & x<0
\end{cases}
\]

## Long formula

\[
f(x)=\frac{1}{\sigma\sqrt{2\pi}}\exp\left(-\frac{1}{2}\left(\frac{x-\mu}{\sigma}\right)^2\right)
\]

## Aligned equations

\[
\begin{aligned}a&=b+c\\d&=e-f\end{aligned}
\]

## Code

```kotlin
val extremelyLongLineForHorizontalPan = "START-OF-LINE: indentation and monospace stay intact while conversation scrolls vertically; reach this final marker: END-OF-LINE"
suspend fun streamChat(prompt: String): Flow<String> = flow {
    client.newCall(request(prompt)).execute().use { response ->
        require(response.isSuccessful)
        response.body?.source()?.let { source ->
            while (!source.exhausted()) {
                emit(source.readUtf8Line() ?: break)
            }
        }
    }
}
```

## Table

| Model | Provider | Speed | Context |
|---|---|---|---|
| Model A | Provider X | Fast | 128k |
| Model B | Provider Y | Medium | 200k |
| Model C | Custom | Variable | Custom |

## Very long URL

https://example.com/documentation/some/really/long/path/that/should/not/destroy/the/layout/on/a/round/watch/display

## Streaming-incomplete examples

These strings are test inputs and may be fed progressively by the fake provider:

- `**unfinished bold`
- `` `unfinished code ``
- `\\[\\frac{1`
- `\\begin{bmatrix}1 & 2 \\\\ 3`

## Final reading position

[Fixture link](https://example.com/reader) stays reachable without changing navigation state. 中文 日本語 한국어 and العربية stay readable alongside α β ∇ ∫. This final paragraph follows tables, code, wide math, matrices and cases. Scroll every line above input controls, leave through History, then return to this same reading position. FINAL-READER-MARKER.
