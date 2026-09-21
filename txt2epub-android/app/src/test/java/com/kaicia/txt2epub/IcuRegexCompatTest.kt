package com.kaicia.txt2epub

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * 앱 소스의 정규식이 안드로이드에서도 컴파일되는지 본다.
 *
 * 안드로이드는 ICU 정규식 엔진을 쓰고, 데스크톱 JVM(java.util.regex)보다 엄격하다.
 * 짝이 없는 `}` 나 `]` 를 JVM은 그냥 글자로 받아주지만 ICU는 문법 오류로 막는다.
 * 그래서 `Regex("""\{(\w+)}""")` 같은 패턴이 시험을 전부 통과하고도 기기에서만
 * ExceptionInInitializerError 로 앱을 죽였다. 실제로 그렇게 죽었다.
 *
 * 시험 JVM에서는 ICU 엔진을 부를 수 없으니, 소스의 정규식 문자열을 직접 읽어
 * ICU가 거부하는 모양이 있는지 본다.
 */
class IcuRegexCompatTest {

    private fun sourceDir(): File {
        System.getProperty("txt2epub.src")?.let { return File(it) }
        var d: File? = File(System.getProperty("user.dir"))
        while (d != null) {
            File(d, "src/main/java").let { if (it.isDirectory) return it }
            File(d, "app/src/main/java").let { if (it.isDirectory) return it }
            d = d.parentFile
        }
        throw IllegalStateException("소스 디렉터리를 찾지 못했습니다: ${System.getProperty("user.dir")}")
    }

    /** `Regex("""...""")` 와 `Regex("...")` 안의 패턴을 뽑는다. */
    private val RAW = Regex("""Regex\("{3}(.*?)"{3}\)""", RegexOption.DOT_MATCHES_ALL)
    private val QUOTED = Regex("""Regex\("((?:[^"\\]|\\.)*)"\)""")

    /** 문자열 템플릿은 값이 들어갈 자리라 검사에서 뺀다. */
    private val TEMPLATE = Regex("""\$\{[^{}]*\}|\$[A-Za-z_][A-Za-z0-9_]*""")

    /**
     * ICU가 거부하는 모양을 찾는다. 찾으면 이유, 없으면 null.
     *
     * 문자 클래스 밖에서 `{`는 `{n}` `{n,}` `{n,m}` 수량자일 때만,
     * `}`는 그 수량자를 닫을 때만 쓸 수 있다. `]`는 언제나 escape 해야 한다.
     */
    private fun icuProblem(p: String): String? {
        var i = 0
        var inClass = false
        while (i < p.length) {
            val c = p[i]
            when {
                c == '\\' -> i++                       // escape: 다음 글자는 건너뛴다
                inClass && c == ']' -> inClass = false
                inClass -> { /* 클래스 안에서는 브레이스가 글자다 */ }
                c == '[' -> inClass = true
                c == ']' -> return "짝 없는 ] (index $i) — \\] 로 escape 하세요"
                c == '{' -> {
                    val m = Regex("""^\{\d+(,\d*)?}""").find(p.substring(i))
                        ?: return "수량자가 아닌 { (index $i) — \\{ 로 escape 하세요"
                    i += m.value.length - 1
                }
                c == '}' -> return "짝 없는 } (index $i) — \\} 로 escape 하세요"
            }
            i++
        }
        if (inClass) return "문자 클래스가 닫히지 않았습니다"
        return null
    }

    @Test
    fun `앱의 모든 정규식이 ICU에서도 컴파일된다`() {
        val dir = sourceDir()
        val files = dir.walkTopDown().filter { it.extension == "kt" }.toList()
        assertTrue("소스를 찾지 못했습니다: $dir", files.isNotEmpty())

        val bad = ArrayList<String>()
        var checked = 0
        for (f in files) {
            val text = f.readText()
            for (m in (RAW.findAll(text) + QUOTED.findAll(text))) {
                val pattern = TEMPLATE.replace(m.groupValues[1], "X")
                checked++
                icuProblem(pattern)?.let { bad.add("${f.name}: [$pattern] → $it") }
            }
        }
        println("정규식 ${checked}개 검사")
        assertTrue("정규식을 하나도 못 찾았습니다", checked >= 20)
        assertTrue("ICU에서 거부되는 정규식:\n" + bad.joinToString("\n"), bad.isEmpty())
    }

    @Test
    fun `검사기가 실제로 문제를 잡아낸다`() {
        // 기기에서 앱을 죽였던 바로 그 패턴
        assertTrue(icuProblem("""\{(\w+)}""")!!.contains("}"))
        assertTrue(icuProblem("""\[\d+]""")!!.contains("]"))
        assertTrue(icuProblem("""a{2,}""") == null)          // 정상 수량자
        assertTrue(icuProblem("""[{}]""") == null)           // 클래스 안은 괜찮다
        assertTrue(icuProblem("""\{(\w+)\}""") == null)      // 고친 모양
        assertTrue(icuProblem("""[\])>]""") == null)
    }
}
