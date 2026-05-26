package pt.daniel.odbc.interop

import com.sun.jna.Pointer
import com.sun.jna.ptr.IntByReference
import com.sun.jna.ptr.ShortByReference

/**
 * Utilitário para extrair mensagens de diagnóstico ODBC detalhadas.
 *
 * Quando uma chamada ODBC devolve SQL_ERROR ou SQL_SUCCESS_WITH_INFO,
 * este helper usa [OdbcApi.SQLGetDiagRec] para obter a mensagem real
 * do driver nativo (ex: "[28000] Login failed for user 'admin'").
 *
 * Exemplo de uso:
 * ```kotlin
 * val msg = OdbcDiagnostics.getDiagMessage(api, SQL_HANDLE_DBC, hdbc)
 * throw SQLException("Conexão falhou. $msg")
 * ```
 */
object OdbcDiagnostics {

    private const val MAX_MESSAGE_LENGTH: Short = 1024
    private const val MAX_SQLSTATE_LENGTH = 6 // 5 chars + null terminator

    /**
     * Extrai a primeira mensagem de diagnóstico ODBC para o handle dado.
     *
     * @param api        API nativa ODBC.
     * @param handleType Tipo de handle (SQL_HANDLE_ENV, SQL_HANDLE_DBC, SQL_HANDLE_STMT).
     * @param handle     O handle ODBC de onde extrair o diagnóstico.
     * @return Mensagem formatada com SQLSTATE e texto, ou mensagem genérica se não houver diagnóstico.
     */
    fun getDiagMessage(api: OdbcApi, handleType: Short, handle: Pointer?): String {
        if (handle == null) return pt.daniel.odbc.Messages.get("error.diag.handle.null")

        val sqlState = ByteArray(MAX_SQLSTATE_LENGTH)
        val nativeError = IntByReference()
        val messageText = ByteArray(MAX_MESSAGE_LENGTH.toInt())
        val textLength = ShortByReference()

        val result = try {
            api.SQLGetDiagRec(
                handleType, handle,
                1, // primeiro registo de diagnóstico
                sqlState, nativeError,
                messageText, MAX_MESSAGE_LENGTH,
                textLength
            )
        } catch (e: Exception) {
            return pt.daniel.odbc.Messages.get("error.diag.failed", e.message)
        }

        if (!OdbcApi.isSuccess(result)) {
            return pt.daniel.odbc.Messages.get("error.diag.unavailable")
        }

        val charset = java.nio.charset.Charset.forName("windows-1252")
        val state = String(sqlState, charset).trim('\u0000')
        val message = String(messageText, 0, textLength.value.toInt().coerceAtLeast(0), charset).trim('\u0000')
        val errorCode = nativeError.value

        return "[$state] (Native Error: $errorCode) $message"
    }

    /**
     * Extrai apenas o SQLSTATE do primeiro registo de diagnóstico ODBC.
     *
     * @return O SQLSTATE (ex: "08S01", "HY000") ou null se não houver diagnóstico.
     */
    fun getSqlState(api: OdbcApi, handleType: Short, handle: Pointer?): String? {
        if (handle == null) return null

        val sqlState = ByteArray(MAX_SQLSTATE_LENGTH)
        val nativeError = IntByReference()
        val messageText = ByteArray(MAX_MESSAGE_LENGTH.toInt())
        val textLength = ShortByReference()

        val result = try {
            api.SQLGetDiagRec(
                handleType, handle,
                1,
                sqlState, nativeError,
                messageText, MAX_MESSAGE_LENGTH,
                textLength
            )
        } catch (_: Exception) {
            return null
        }

        if (!OdbcApi.isSuccess(result)) return null

        return String(sqlState, Charsets.US_ASCII).trim('\u0000').ifBlank { null }
    }

    /**
     * Extrai todas as mensagens de diagnóstico ODBC disponíveis.
     * Útil para logging detalhado.
     *
     * @return Lista de mensagens formatadas, pode estar vazia.
     */
    fun getAllDiagMessages(api: OdbcApi, handleType: Short, handle: Pointer?): List<String> {
        if (handle == null) return emptyList()

        val messages = mutableListOf<String>()
        var recNumber: Short = 1

        while (true) {
            val sqlState = ByteArray(MAX_SQLSTATE_LENGTH)
            val nativeError = IntByReference()
            val messageText = ByteArray(MAX_MESSAGE_LENGTH.toInt())
            val textLength = ShortByReference()

            val result = try {
                api.SQLGetDiagRec(
                    handleType, handle,
                    recNumber,
                    sqlState, nativeError,
                    messageText, MAX_MESSAGE_LENGTH,
                    textLength
                )
            } catch (_: Exception) {
                break
            }

            if (!OdbcApi.isSuccess(result)) break

            val charset = java.nio.charset.Charset.forName("windows-1252")
            val state = String(sqlState, charset).trim('\u0000')
            val message = String(messageText, 0, textLength.value.toInt().coerceAtLeast(0), charset).trim('\u0000')
            val errorCode = nativeError.value

            messages.add("[$state] (Native Error: $errorCode) $message")
            recNumber++

            // Limite de segurança para evitar loops infinitos
            if (recNumber > 25) break
        }

        return messages
    }
}
