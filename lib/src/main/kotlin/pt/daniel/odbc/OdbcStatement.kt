package pt.daniel.odbc

import com.sun.jna.Pointer
import java.sql.*
import pt.daniel.odbc.interop.OdbcApi
import pt.daniel.odbc.interop.OdbcDiagnostics

/** Implementação de [Statement] que executa SQL diretamente via ODBC. */
class OdbcStatement(
        private val connection: OdbcConnection,
        private val api: OdbcApi,
        private val stmtHandle: Pointer?,
        private val charset: java.nio.charset.Charset
) : Statement {

    private var closed = false
    private var lastResultSet: ResultSet? = null
    private var lastUpdateCount: Int = -1

    override fun executeQuery(sql: String?): ResultSet {
        checkNotClosed()
        execDirect(sql)
        val rs = OdbcResultSet(this, api, stmtHandle, charset)
        lastResultSet = rs
        lastUpdateCount = -1
        return rs
    }

    override fun executeUpdate(sql: String?): Int {
        checkNotClosed()
        execDirect(sql)
        lastResultSet = null
        lastUpdateCount = getRowCount()
        return lastUpdateCount
    }

    override fun execute(sql: String?): Boolean {
        checkNotClosed()
        execDirect(sql)
        val colCount = com.sun.jna.ptr.ShortByReference()
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
                api.SQLFreeHandle(OdbcApi.SQL_HANDLE_STMT.toShort(), stmtHandle)
            } finally {
                closed = true
            }
        }
    }

    override fun isClosed(): Boolean = closed
    override fun getConnection(): Connection = connection
    override fun getResultSet(): ResultSet? = lastResultSet
    override fun getUpdateCount(): Int = lastUpdateCount

    private fun execDirect(sql: String?) {
        requireNotNull(sql) { "O SQL não pode ser nulo" }
        val result =
                try {
                    api.SQLExecDirect(stmtHandle, sql, sql.length)
                } catch (e: Exception) {
                    connection.markConnectionDead()
                    throw SQLException(
                            "Erro de comunicação ao executar SQL via ODBC: ${e.message}",
                            "08S01",
                            e
                    )
                }
        if (!OdbcApi.isSuccess(result)) {
            val diagMsg =
                    OdbcDiagnostics.getDiagMessage(
                            api,
                            OdbcApi.SQL_HANDLE_STMT.toShort(),
                            stmtHandle
                    )
            val sqlState =
                    OdbcDiagnostics.getSqlState(
                            api,
                            OdbcApi.SQL_HANDLE_STMT.toShort(),
                            stmtHandle
                    ) ?: "HY000"

            // Classe 08 = erros de comunicação (08S01, 08001, 08004, etc.)
            if (sqlState.startsWith("08")) {
                connection.markConnectionDead()
            }

            throw SQLException("Erro ao executar SQL (Código: $result). $diagMsg", sqlState)
        }
    }

    private fun getRowCount(): Int {
        val rowCount = com.sun.jna.ptr.LongByReference()
        val result = api.SQLRowCount(stmtHandle, rowCount)
        return if (OdbcApi.isSuccess(result)) rowCount.value.toInt() else 0
    }

    private fun checkNotClosed() {
        if (closed) throw SQLException("Statement já foi fechado")
    }

    // --- Stubs ---
    override fun getMaxFieldSize(): Int = 0
    override fun setMaxFieldSize(max: Int) {}
    override fun getMaxRows(): Int = 0
    override fun setMaxRows(max: Int) {}
    override fun setEscapeProcessing(enable: Boolean) {}
    override fun getQueryTimeout(): Int = 0
    override fun setQueryTimeout(seconds: Int) {}
    override fun cancel() {
        if (!closed) {
            val result = api.SQLCancel(stmtHandle)
            if (!OdbcApi.isSuccess(result)) {
                // Não falhamos ruidosamente aqui, apenas registamos ou ignoramos,
                // pois o comando pode já ter terminado.
            }
        }
    }
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
    override fun executeUpdate(sql: String?, a: Int): Int = executeUpdate(sql)
    override fun executeUpdate(sql: String?, a: IntArray?): Int = executeUpdate(sql)
    override fun executeUpdate(sql: String?, a: Array<out String>?): Int = executeUpdate(sql)
    override fun execute(sql: String?, a: Int): Boolean = execute(sql)
    override fun execute(sql: String?, a: IntArray?): Boolean = execute(sql)
    override fun execute(sql: String?, a: Array<out String>?): Boolean = execute(sql)
    override fun getResultSetHoldability(): Int = ResultSet.CLOSE_CURSORS_AT_COMMIT
    override fun setPoolable(poolable: Boolean) {}
    override fun isPoolable(): Boolean = false
    override fun closeOnCompletion() {}
    override fun isCloseOnCompletion(): Boolean = false
    override fun <T : Any?> unwrap(iface: Class<T>?): T = throw SQLFeatureNotSupportedException()
    override fun isWrapperFor(iface: Class<*>?): Boolean = false
}
