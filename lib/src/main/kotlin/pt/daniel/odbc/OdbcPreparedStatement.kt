package pt.daniel.odbc

import com.sun.jna.Memory
import com.sun.jna.Pointer
import com.sun.jna.ptr.IntByReference
import com.sun.jna.ptr.ShortByReference
import pt.daniel.odbc.interop.OdbcApi
import pt.daniel.odbc.interop.OdbcDiagnostics
import java.io.InputStream
import java.io.Reader
import java.math.BigDecimal
import java.net.URL
import java.sql.*
import java.sql.Array
import java.sql.Date
import java.util.*

/**
 * Implementação de [PreparedStatement] via ODBC (SQLPrepare + SQLBindParameter + SQLExecute).
 *
 * Os parâmetros são todos enviados como strings (SQL_C_CHAR) e convertidos pelo driver ODBC.
 * Isto simplifica a implementação e funciona com a maioria das fontes de dados.
 */
class OdbcPreparedStatement(
    private val connection: Connection,
    private val api: OdbcApi,
    private val stmtHandle: Pointer?,
    private val sql: String,
    private val charset: java.nio.charset.Charset
) : PreparedStatement {

    private var closed = false
    private var lastResultSet: ResultSet? = null
    private var lastUpdateCount: Int = -1

    /** Parâmetros guardados como (índice -> valor string). Null = SQL NULL. */
    private val parameters = mutableMapOf<Int, String?>()

    /** Referências à memória nativa alocada — mantemos viva até ao execute. */
    private val nativeBuffers = mutableListOf<Memory>()
    private val nativeIndicators = mutableListOf<Memory>()

    init {
        val result = api.SQLPrepare(stmtHandle, sql, sql.length)
        if (!OdbcApi.isSuccess(result)) {
            val diag = OdbcDiagnostics.getDiagMessage(api, OdbcApi.SQL_HANDLE_STMT.toShort(), stmtHandle)
            throw SQLException("Erro ao preparar SQL: $diag", "42000")
        }
    }

    // --- Definição de parâmetros ---

    override fun setString(parameterIndex: Int, x: String?) {
        parameters[parameterIndex] = x
    }

    override fun setInt(parameterIndex: Int, x: Int) {
        parameters[parameterIndex] = x.toString()
    }

    override fun setLong(parameterIndex: Int, x: Long) {
        parameters[parameterIndex] = x.toString()
    }

    override fun setDouble(parameterIndex: Int, x: Double) {
        parameters[parameterIndex] = x.toString()
    }

    override fun setFloat(parameterIndex: Int, x: Float) {
        parameters[parameterIndex] = x.toString()
    }

    override fun setShort(parameterIndex: Int, x: Short) {
        parameters[parameterIndex] = x.toString()
    }

    override fun setBoolean(parameterIndex: Int, x: Boolean) {
        parameters[parameterIndex] = if (x) "1" else "0"
    }

    override fun setBigDecimal(parameterIndex: Int, x: BigDecimal?) {
        parameters[parameterIndex] = x?.toPlainString()
    }

    override fun setNull(parameterIndex: Int, sqlType: Int) {
        parameters[parameterIndex] = null
    }

    override fun setNull(parameterIndex: Int, sqlType: Int, typeName: String?) {
        parameters[parameterIndex] = null
    }

    override fun setObject(parameterIndex: Int, x: Any?) {
        parameters[parameterIndex] = x?.toString()
    }

    override fun setObject(parameterIndex: Int, x: Any?, targetSqlType: Int) {
        setObject(parameterIndex, x)
    }

    override fun setObject(parameterIndex: Int, x: Any?, targetSqlType: Int, scaleOrLength: Int) {
        setObject(parameterIndex, x)
    }

    override fun setByte(parameterIndex: Int, x: Byte) {
        parameters[parameterIndex] = x.toString()
    }

    override fun setBytes(parameterIndex: Int, x: ByteArray?) {
        parameters[parameterIndex] = x?.let { String(it) }
    }

    override fun setDate(parameterIndex: Int, x: Date?) {
        parameters[parameterIndex] = x?.toString()
    }

    override fun setDate(parameterIndex: Int, x: Date?, cal: Calendar?) {
        setDate(parameterIndex, x)
    }

    override fun setTime(parameterIndex: Int, x: Time?) {
        parameters[parameterIndex] = x?.toString()
    }

    override fun setTime(parameterIndex: Int, x: Time?, cal: Calendar?) {
        setTime(parameterIndex, x)
    }

    override fun setTimestamp(parameterIndex: Int, x: Timestamp?) {
        parameters[parameterIndex] = x?.toString()
    }

    override fun setTimestamp(parameterIndex: Int, x: Timestamp?, cal: Calendar?) {
        setTimestamp(parameterIndex, x)
    }

    override fun clearParameters() {
        parameters.clear()
        freeNativeBuffers()
    }

    // --- Execução ---

    override fun executeQuery(): ResultSet {
        checkNotClosed()
        bindAllParameters()
        executePrepared()
        val rs = OdbcResultSet(this, api, stmtHandle, charset)
        lastResultSet = rs
        lastUpdateCount = -1
        return rs
    }

    override fun executeUpdate(): Int {
        checkNotClosed()
        bindAllParameters()
        executePrepared()
        lastResultSet = null
        lastUpdateCount = getRowCount()
        return lastUpdateCount
    }

    override fun execute(): Boolean {
        checkNotClosed()
        bindAllParameters()
        executePrepared()
        val colCount = ShortByReference()
        api.SQLNumResultCols(stmtHandle, colCount)
        return if (colCount.value > 0) {
            lastResultSet = OdbcResultSet(this, api, stmtHandle, charset)
            lastUpdateCount = -1
            true
        } else {
            lastResultSet = null
            lastUpdateCount = getRowCount()
            false
        }
    }

    override fun close() {
        if (!closed) {
            try {
                lastResultSet?.close()
                freeNativeBuffers()
                api.SQLFreeHandle(OdbcApi.SQL_HANDLE_STMT.toShort(), stmtHandle)
            } finally { closed = true }
        }
    }

    override fun isClosed(): Boolean = closed
    override fun getConnection(): Connection = connection
    override fun getResultSet(): ResultSet? = lastResultSet
    override fun getUpdateCount(): Int = lastUpdateCount

    // --- Helpers privados ---

    private fun bindAllParameters() {
        freeNativeBuffers()
        for ((index, value) in parameters) {
            if (value == null) {
                // SQL NULL
                val indicator = Memory(8)
                indicator.setLong(0, OdbcApi.SQL_NULL_DATA.toLong())
                nativeIndicators.add(indicator)

                val result = api.SQLBindParameter(
                    stmtHandle, index.toShort(),
                    OdbcApi.SQL_PARAM_INPUT.toShort(),
                    OdbcApi.SQL_C_CHAR.toShort(), OdbcApi.SQL_VARCHAR.toShort(),
                    0L, 0,
                    null, 0L, indicator
                )
                if (!OdbcApi.isSuccess(result)) {
                    val diag = OdbcDiagnostics.getDiagMessage(api, OdbcApi.SQL_HANDLE_STMT.toShort(), stmtHandle)
                    throw SQLException("Erro ao ligar parâmetro NULL #$index: $diag")
                }
            } else {
                val bytes = value.toByteArray(charset)
                val buffer = Memory((bytes.size + 1).toLong())
                buffer.write(0, bytes, 0, bytes.size)
                buffer.setByte(bytes.size.toLong(), 0) // null terminator
                nativeBuffers.add(buffer)

                val indicator = Memory(8)
                indicator.setLong(0, bytes.size.toLong())
                nativeIndicators.add(indicator)

                val result = api.SQLBindParameter(
                    stmtHandle, index.toShort(),
                    OdbcApi.SQL_PARAM_INPUT.toShort(),
                    OdbcApi.SQL_C_CHAR.toShort(), OdbcApi.SQL_VARCHAR.toShort(),
                    bytes.size.coerceAtLeast(1).toLong(), 0,
                    buffer, (bytes.size + 1).toLong(), indicator
                )
                if (!OdbcApi.isSuccess(result)) {
                    val diag = OdbcDiagnostics.getDiagMessage(api, OdbcApi.SQL_HANDLE_STMT.toShort(), stmtHandle)
                    throw SQLException("Erro ao ligar parâmetro #$index ('$value'): $diag")
                }
            }
        }
    }

    private fun executePrepared() {
        val result = api.SQLExecute(stmtHandle)
        if (!OdbcApi.isSuccess(result)) {
            val diag = OdbcDiagnostics.getDiagMessage(api, OdbcApi.SQL_HANDLE_STMT.toShort(), stmtHandle)
            throw SQLException("Erro ao executar prepared statement: $diag", "HY000")
        }
    }

    private fun getRowCount(): Int {
        val rowCount = com.sun.jna.ptr.LongByReference()
        val result = api.SQLRowCount(stmtHandle, rowCount)
        return if (OdbcApi.isSuccess(result)) rowCount.value.toInt() else 0
    }

    private fun freeNativeBuffers() {
        nativeBuffers.clear()
        nativeIndicators.clear()
    }

    private fun checkNotClosed() {
        if (closed) throw SQLException("PreparedStatement já foi fechado")
    }

    // --- Métodos de Statement herdados ---

    override fun executeQuery(sql: String?): ResultSet = throw SQLException("Use executeQuery() sem parâmetros num PreparedStatement")
    override fun executeUpdate(sql: String?): Int = throw SQLException("Use executeUpdate() sem parâmetros num PreparedStatement")
    override fun execute(sql: String?): Boolean = throw SQLException("Use execute() sem parâmetros num PreparedStatement")

    override fun getMetaData(): ResultSetMetaData? = null
    override fun getParameterMetaData(): ParameterMetaData = throw SQLFeatureNotSupportedException()
    override fun addBatch() {}

    // --- Streams (não suportados) ---
    override fun setAsciiStream(pi: Int, x: InputStream?, l: Int) = throw SQLFeatureNotSupportedException()
    @Deprecated("Deprecated") override fun setUnicodeStream(pi: Int, x: InputStream?, l: Int) = throw SQLFeatureNotSupportedException()
    override fun setBinaryStream(pi: Int, x: InputStream?, l: Int) = throw SQLFeatureNotSupportedException()
    override fun setAsciiStream(pi: Int, x: InputStream?, l: Long) = throw SQLFeatureNotSupportedException()
    override fun setBinaryStream(pi: Int, x: InputStream?, l: Long) = throw SQLFeatureNotSupportedException()
    override fun setCharacterStream(pi: Int, x: Reader?, l: Int) = throw SQLFeatureNotSupportedException()
    override fun setCharacterStream(pi: Int, x: Reader?, l: Long) = throw SQLFeatureNotSupportedException()
    override fun setAsciiStream(pi: Int, x: InputStream?) = throw SQLFeatureNotSupportedException()
    override fun setBinaryStream(pi: Int, x: InputStream?) = throw SQLFeatureNotSupportedException()
    override fun setCharacterStream(pi: Int, x: Reader?) = throw SQLFeatureNotSupportedException()
    override fun setNCharacterStream(pi: Int, x: Reader?, l: Long) = throw SQLFeatureNotSupportedException()
    override fun setNCharacterStream(pi: Int, x: Reader?) = throw SQLFeatureNotSupportedException()
    override fun setClob(pi: Int, x: Clob?) = throw SQLFeatureNotSupportedException()
    override fun setClob(pi: Int, x: Reader?, l: Long) = throw SQLFeatureNotSupportedException()
    override fun setClob(pi: Int, x: Reader?) = throw SQLFeatureNotSupportedException()
    override fun setBlob(pi: Int, x: Blob?) = throw SQLFeatureNotSupportedException()
    override fun setBlob(pi: Int, x: InputStream?, l: Long) = throw SQLFeatureNotSupportedException()
    override fun setBlob(pi: Int, x: InputStream?) = throw SQLFeatureNotSupportedException()
    override fun setNClob(pi: Int, x: NClob?) = throw SQLFeatureNotSupportedException()
    override fun setNClob(pi: Int, x: Reader?, l: Long) = throw SQLFeatureNotSupportedException()
    override fun setNClob(pi: Int, x: Reader?) = throw SQLFeatureNotSupportedException()
    override fun setSQLXML(pi: Int, x: SQLXML?) = throw SQLFeatureNotSupportedException()
    override fun setRef(pi: Int, x: Ref?) = throw SQLFeatureNotSupportedException()
    override fun setArray(pi: Int, x: Array?) = throw SQLFeatureNotSupportedException()
    override fun setURL(pi: Int, x: URL?) = throw SQLFeatureNotSupportedException()
    override fun setRowId(pi: Int, x: RowId?) = throw SQLFeatureNotSupportedException()
    override fun setNString(pi: Int, x: String?) { setString(pi, x) }

    // --- Stubs herdados de Statement ---
    override fun getMaxFieldSize(): Int = 0
    override fun setMaxFieldSize(max: Int) {}
    override fun getMaxRows(): Int = 0
    override fun setMaxRows(max: Int) {}
    override fun setEscapeProcessing(enable: Boolean) {}
    override fun getQueryTimeout(): Int = 0
    override fun setQueryTimeout(seconds: Int) {}
    override fun cancel() {}
    override fun getWarnings(): SQLWarning? = null
    override fun clearWarnings() {}
    override fun setCursorName(name: String?) {}
    override fun getMoreResults(): Boolean = false
    override fun setFetchDirection(direction: Int) {}
    override fun getFetchDirection(): Int = ResultSet.FETCH_FORWARD
    override fun setFetchSize(rows: Int) {}
    override fun getFetchSize(): Int = 0
    override fun getResultSetConcurrency(): Int = ResultSet.CONCUR_READ_ONLY
    override fun getResultSetType(): Int = ResultSet.TYPE_FORWARD_ONLY
    override fun addBatch(sql: String?) {}
    override fun clearBatch() {}
    override fun executeBatch(): IntArray = intArrayOf()
    override fun getMoreResults(current: Int): Boolean = false
    override fun getGeneratedKeys(): ResultSet? = null
    override fun executeUpdate(sql: String?, a: Int): Int { throw SQLException("Use executeUpdate() sem parâmetros") }
    override fun executeUpdate(sql: String?, a: IntArray?): Int { throw SQLException("Use executeUpdate() sem parâmetros") }
    override fun executeUpdate(sql: String?, a: kotlin.Array<out String>?): Int { throw SQLException("Use executeUpdate() sem parâmetros") }
    override fun execute(sql: String?, a: Int): Boolean { throw SQLException("Use execute() sem parâmetros") }
    override fun execute(sql: String?, a: IntArray?): Boolean { throw SQLException("Use execute() sem parâmetros") }
    override fun execute(sql: String?, a: kotlin.Array<out String>?): Boolean { throw SQLException("Use execute() sem parâmetros") }
    override fun getResultSetHoldability(): Int = ResultSet.CLOSE_CURSORS_AT_COMMIT
    override fun setPoolable(poolable: Boolean) {}
    override fun isPoolable(): Boolean = false
    override fun closeOnCompletion() {}
    override fun isCloseOnCompletion(): Boolean = false
    override fun <T : Any?> unwrap(iface: Class<T>?): T = throw SQLFeatureNotSupportedException()
    override fun isWrapperFor(iface: Class<*>?): Boolean = false
}
