package app.heidimobile.db

/** How binary column values appear in the grid. */
object ValueFormat {
    /**
     * Binary values that are really text (e.g. MySQL reports SHOW output as BLOB) are shown as text;
     * everything else becomes "[BLOB n B]" like HeidiSQL's grid does.
     */
    fun bytes(b: ByteArray?): String? {
        if (b == null) return null
        if (b.size <= 65536) {
            val s = try {
                Charsets.UTF_8.newDecoder()
                    .onMalformedInput(java.nio.charset.CodingErrorAction.REPORT)
                    .onUnmappableCharacter(java.nio.charset.CodingErrorAction.REPORT)
                    .decode(java.nio.ByteBuffer.wrap(b)).toString()
            } catch (e: java.nio.charset.CharacterCodingException) { null }
            if (s != null && s.none { it.code < 32 && it != '\n' && it != '\r' && it != '\t' }) return s
        }
        return "[BLOB ${b.size} B]"
    }
}
