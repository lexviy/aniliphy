package com.aniliphy.japanese

/** Telling kana and kanji apart. */
object Kana {
    fun isHiragana(c: Char) = c in 'ぁ'..'ゟ'
    fun isKatakana(c: Char) = c in 'ァ'..'ヿ' || c in 'ㇰ'..'ㇿ' || c in 'ｦ'..'ﾝ'
    fun isKana(c: Char) = isHiragana(c) || isKatakana(c)
    fun isKanji(c: Char) = c in '一'..'鿿' || c in '㐀'..'䶿' || c in '豈'..'﫿' || c == '々' || c == 'ヶ' || c == '〆'

    /** Kana or kanji: something a word can be made of. */
    fun isWordChar(c: Char) = isKana(c) || isKanji(c) || c == 'ー' || c in '０'..'９' || c in 'Ａ'..'ｚ'

    fun hasKanji(text: String) = text.any(::isKanji)

    fun hasJapanese(text: String) = text.any { isKana(it) || isKanji(it) }

    /** カタカナ → かたかな (ー and the rest stay). */
    fun toHiragana(text: String): String = buildString(text.length) {
        for (c in text) append(if (c in 'ァ'..'ヶ') c - 0x60 else c)
    }
}
