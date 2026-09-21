package com.kaicia.txt2epub.core

/**
 * 본문 전체를 문자 배열 하나로 들고, 줄은 시작·끝 위치로만 기억한다.
 *
 * 줄마다 String 객체를 만들면 5.4MB 파일이 힙 42MB를 먹는다. 글자 자체는 11MB인데
 * 나머지가 객체 머리말과 참조다. 32만 줄이면 그 부담이 본문보다 커진다.
 * 그래서 글자는 한 벌만 두고, 줄은 [from]·[to]로 가리키기만 한다. 같은 파일이 14MB로 떨어진다.
 *
 * [List] 그대로라서 쓰는 쪽은 바뀌지 않는다. `lines[i]`를 할 때 그 자리에서 String을 만든다.
 * 한 번 훑고 버리는 용도라 부담이 없고, 판정은 아래 [charAt] 로 아예 만들지 않고 읽는다.
 */
class TextLines internal constructor(
    @JvmField internal val chars: CharArray,
    @JvmField internal val from: IntArray,
    @JvmField internal val to: IntArray,
    private val count: Int
) : AbstractList<String>(), RandomAccess {

    override val size: Int get() = count

    override fun get(index: Int): String {
        if (index < 0 || index >= count) throw IndexOutOfBoundsException("줄 $index / $count")
        return String(chars, from[index], to[index] - from[index])
    }

    /** String을 만들지 않고 글자만 본다. */
    internal fun charAt(line: Int, offset: Int): Char = chars[from[line] + offset]

    internal fun lengthOf(line: Int): Int = to[line] - from[line]
}
