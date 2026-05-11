package pt.daniel.odbc

import com.sun.jna.Pointer
import com.sun.jna.ptr.IntByReference
import com.sun.jna.ptr.ShortByReference
import pt.daniel.odbc.interop.OdbcApi
import java.io.InputStream
import java.io.Reader
import java.math.BigDecimal
import java.net.URL
import java.sql.*
import java.sql.Array
import java.sql.Date
import java.util.*

/**
 * Implementação de [ResultSet] que lê dados via ODBC (SQLFetch + SQLGetData).
 *
 * Suporta apenas cursor forward-only e read-only.
 * Todos os valores são lidos como strings via SQL_C_CHAR e convertidos para o tipo pedido.
 */
class OdbcResultSet(
    private val statement: Statement,
    private val api: OdbcApi,
    private val stmtHandle: Pointer?,
    private val charset: java.nio.charset.Charset
) : ResultSet {

    private var closed = false
    private var wasNull = false
    private var currentRow = 0
    private var currentRowData: kotlin.Array<String?>? = null

    // Cache da metadata (carregado uma vez)
    private val columnCount: Int by lazy { fetchColumnCount() }
    private val columnMetadata: List<ColumnInfo> by lazy { fetchColumnMetadata() }
    private val columnNames: Map<String, Int> by lazy { 
        columnMetadata.mapIndexed { index, col -> col.name.uppercase() to (index + 1) }.toMap()
    }

    private companion object {
        const val DATA_BUFFER_SIZE = 4096
    }

    // --- Navegação ---

    override fun next(): Boolean {
        checkNotClosed()
        val result = api.SQLFetch(stmtHandle)
        return when (result.toInt()) {
            OdbcApi.SQL_SUCCESS, OdbcApi.SQL_SUCCESS_WITH_INFO -> {
                currentRow++
                fetchCurrentRow()
                true
            }
            OdbcApi.SQL_NO_DATA -> false
            else -> {
                throw SQLException("Erro ao avançar cursor ODBC (Código: $result)")
            }
        }
    }

    private fun fetchCurrentRow() {
        val row = arrayOfNulls<String>(columnCount)
        val buffer = ByteArray(DATA_BUFFER_SIZE)
        val indicator = com.sun.jna.ptr.LongByReference()

        for (i in 1..columnCount) {
            val res = api.SQLGetData(
                stmtHandle, i.toShort(),
                OdbcApi.SQL_C_CHAR.toShort(),
                buffer, DATA_BUFFER_SIZE.toLong(), indicator.pointer
            )

            if (res.toInt() == OdbcApi.SQL_NO_DATA) {
                row[i - 1] = "" // empty data
                continue
            }

            if (!OdbcApi.isSuccess(res)) {
                // Ignore errors for individual columns to prevent complete failure
                row[i - 1] = null
                continue
            }

            if (indicator.value.toLong() == OdbcApi.SQL_NULL_DATA.toLong()) {
                row[i - 1] = null
            } else {
                val len = indicator.value.toInt().coerceAtMost(DATA_BUFFER_SIZE - 1).coerceAtLeast(0)
                row[i - 1] = String(buffer, 0, len, charset)
            }
        }
        currentRowData = row
    }

    override fun close() {
        closed = true
    }

    // --- Leitura de dados por índice de coluna ---

    override fun getString(columnIndex: Int): String? {
        checkNotClosed()
        val data = currentRowData ?: throw SQLException("Nenhum dado na linha atual")
        if (columnIndex < 1 || columnIndex > columnCount) throw SQLException("Índice de coluna inválido: $columnIndex")
        
        val value = data[columnIndex - 1]
        wasNull = (value == null)
        return value
    }

    override fun getInt(columnIndex: Int): Int {
        val s = getString(columnIndex) ?: return 0
        return s.trim().toIntOrNull() ?: 0
    }

    override fun getLong(columnIndex: Int): Long {
        val s = getString(columnIndex) ?: return 0L
        return s.trim().toLongOrNull() ?: 0L
    }

    override fun getDouble(columnIndex: Int): Double {
        val s = getString(columnIndex) ?: return 0.0
        return s.trim().toDoubleOrNull() ?: 0.0
    }

    override fun getFloat(columnIndex: Int): Float {
        val s = getString(columnIndex) ?: return 0f
        return s.trim().toFloatOrNull() ?: 0f
    }

    override fun getShort(columnIndex: Int): Short {
        val s = getString(columnIndex) ?: return 0
        return s.trim().toShortOrNull() ?: 0
    }

    override fun getBoolean(columnIndex: Int): Boolean {
        val s = getString(columnIndex) ?: return false
        return s.trim() == "1" || s.trim().equals("true", ignoreCase = true)
    }

    override fun getBigDecimal(columnIndex: Int): BigDecimal? {
        val s = getString(columnIndex) ?: return null
        return s.trim().toBigDecimalOrNull()
    }

    override fun getObject(columnIndex: Int): Any? {
        val str = getString(columnIndex) ?: return null
        val odbcType = columnMetadata[columnIndex - 1].dataType.toInt()
        return try {
            when (odbcType) {
                -7 -> getBoolean(columnIndex)
                -6 -> getByte(columnIndex)
                5 -> getShort(columnIndex)
                4 -> getInt(columnIndex)
                -5 -> getLong(columnIndex)
                6, 8 -> getDouble(columnIndex)
                7 -> getFloat(columnIndex)
                2, 3 -> getBigDecimal(columnIndex)
                91, 9 -> getDate(columnIndex)
                92, 10 -> getTime(columnIndex)
                93, 11 -> getTimestamp(columnIndex)
                else -> str
            }
        } catch (e: Exception) {
            str // Fallback seguro
        }
    }

    // --- Leitura de dados por nome de coluna ---

    override fun getString(columnLabel: String?): String? = getString(findColumn(columnLabel))
    override fun getInt(columnLabel: String?): Int = getInt(findColumn(columnLabel))
    override fun getLong(columnLabel: String?): Long = getLong(findColumn(columnLabel))
    override fun getDouble(columnLabel: String?): Double = getDouble(findColumn(columnLabel))
    override fun getFloat(columnLabel: String?): Float = getFloat(findColumn(columnLabel))
    override fun getShort(columnLabel: String?): Short = getShort(findColumn(columnLabel))
    override fun getBoolean(columnLabel: String?): Boolean = getBoolean(findColumn(columnLabel))
    override fun getBigDecimal(columnLabel: String?): BigDecimal? = getBigDecimal(findColumn(columnLabel))
    override fun getObject(columnLabel: String?): Any? = getObject(findColumn(columnLabel))

    // --- Metadata ---

    override fun findColumn(columnLabel: String?): Int {
        requireNotNull(columnLabel) { "Column label não pode ser nulo" }
        return columnNames[columnLabel.uppercase()] ?:
            throw SQLException("Coluna '$columnLabel' não encontrada")
    }

    override fun getMetaData(): ResultSetMetaData = OdbcResultSetMetaData(columnMetadata)

    override fun wasNull(): Boolean = wasNull
    override fun isClosed(): Boolean = closed
    override fun getStatement(): Statement = statement
    override fun getRow(): Int = currentRow
    override fun getType(): Int = ResultSet.TYPE_FORWARD_ONLY
    override fun getConcurrency(): Int = ResultSet.CONCUR_READ_ONLY
    override fun getFetchDirection(): Int = ResultSet.FETCH_FORWARD
    override fun getFetchSize(): Int = 0
    override fun getHoldability(): Int = ResultSet.CLOSE_CURSORS_AT_COMMIT

    // --- Helpers privados ---

    private fun checkNotClosed() {
        if (closed) throw SQLException("ResultSet já foi fechado")
    }

    private fun fetchColumnCount(): Int {
        val count = ShortByReference()
        api.SQLNumResultCols(stmtHandle, count)
        return count.value.toInt()
    }

    private fun fetchColumnMetadata(): List<ColumnInfo> {
        val list = mutableListOf<ColumnInfo>()
        for (i in 1..columnCount) {
            val nameBuffer = ByteArray(256)
            val nameLen = ShortByReference()
            val dataType = ShortByReference()
            val colSize = com.sun.jna.ptr.LongByReference()
            val decDigits = ShortByReference()
            val nullable = ShortByReference()

            api.SQLDescribeCol(
                stmtHandle, i.toShort(),
                nameBuffer, 256,
                nameLen, dataType, colSize, decDigits, nullable
            )
            
            val name = String(nameBuffer, 0, nameLen.value.toInt(), charset).trim('\u0000')
            
            list.add(ColumnInfo(name, dataType.value, colSize.value.toInt(), decDigits.value, nullable.value))
        }
        return list
    }

    // --- Stubs obrigatórios (não suportados) ---

    override fun getByte(ci: Int): Byte = (getInt(ci) and 0xFF).toByte()
    override fun getBytes(ci: Int): ByteArray? = getString(ci)?.toByteArray()
    override fun getDate(ci: Int): Date? {
        val s = getString(ci) ?: return null
        return runCatching { Date.valueOf(s.substringBefore(' ')) }.getOrNull()
    }
    override fun getTime(ci: Int): Time? {
        val s = getString(ci) ?: return null
        return runCatching { Time.valueOf(s) }.getOrNull()
    }
    override fun getTimestamp(ci: Int): Timestamp? {
        val s = getString(ci) ?: return null
        return runCatching { Timestamp.valueOf(s) }.getOrNull()
    }

    override fun getByte(cl: String?): Byte = getByte(findColumn(cl))
    override fun getBytes(cl: String?): ByteArray? = getBytes(findColumn(cl))
    override fun getDate(cl: String?): Date? = getDate(findColumn(cl))
    override fun getTime(cl: String?): Time? = getTime(findColumn(cl))
    override fun getTimestamp(cl: String?): Timestamp? = getTimestamp(findColumn(cl))

    @Deprecated("Use getBigDecimal without scale", ReplaceWith("getBigDecimal(ci)"))
    override fun getBigDecimal(ci: Int, scale: Int): BigDecimal? = getBigDecimal(ci)
    @Deprecated("Use getBigDecimal without scale", ReplaceWith("getBigDecimal(cl)"))
    override fun getBigDecimal(cl: String?, scale: Int): BigDecimal? = getBigDecimal(cl)

    override fun getAsciiStream(ci: Int): InputStream? = null
    @Deprecated("Deprecated in JDBC") override fun getUnicodeStream(ci: Int): InputStream? = null
    override fun getBinaryStream(ci: Int): InputStream? = null
    override fun getAsciiStream(cl: String?): InputStream? = null
    @Deprecated("Deprecated in JDBC") override fun getUnicodeStream(cl: String?): InputStream? = null
    override fun getBinaryStream(cl: String?): InputStream? = null
    override fun getWarnings(): SQLWarning? = null
    override fun clearWarnings() {}
    override fun getCursorName(): String? = null

    override fun getObject(ci: Int, map: MutableMap<String, Class<*>>?): Any? = getObject(ci)
    override fun getObject(cl: String?, map: MutableMap<String, Class<*>>?): Any? = getObject(cl)
    override fun <T : Any?> getObject(ci: Int, type: Class<T>?): T = throw SQLFeatureNotSupportedException()
    override fun <T : Any?> getObject(cl: String?, type: Class<T>?): T = throw SQLFeatureNotSupportedException()

    override fun getRef(ci: Int): Ref? = null
    override fun getBlob(ci: Int): Blob? = null
    override fun getClob(ci: Int): Clob? = null
    override fun getArray(ci: Int): Array? = null
    override fun getRef(cl: String?): Ref? = null
    override fun getBlob(cl: String?): Blob? = null
    override fun getClob(cl: String?): Clob? = null
    override fun getArray(cl: String?): Array? = null

    override fun getDate(ci: Int, cal: Calendar?): Date? = null
    override fun getTime(ci: Int, cal: Calendar?): Time? = null
    override fun getTimestamp(ci: Int, cal: Calendar?): Timestamp? = null
    override fun getDate(cl: String?, cal: Calendar?): Date? = null
    override fun getTime(cl: String?, cal: Calendar?): Time? = null
    override fun getTimestamp(cl: String?, cal: Calendar?): Timestamp? = null

    override fun getURL(ci: Int): URL? = null
    override fun getURL(cl: String?): URL? = null
    override fun getNClob(ci: Int): NClob? = null
    override fun getNClob(cl: String?): NClob? = null
    override fun getSQLXML(ci: Int): SQLXML? = null
    override fun getSQLXML(cl: String?): SQLXML? = null
    override fun getNString(ci: Int): String? = getString(ci)
    override fun getNString(cl: String?): String? = getString(cl)
    override fun getNCharacterStream(ci: Int): Reader? = null
    override fun getNCharacterStream(cl: String?): Reader? = null
    override fun getCharacterStream(ci: Int): Reader? = null
    override fun getCharacterStream(cl: String?): Reader? = null
    override fun getRowId(ci: Int): RowId? = null
    override fun getRowId(cl: String?): RowId? = null

    override fun isBeforeFirst(): Boolean = currentRow == 0
    override fun isAfterLast(): Boolean = false
    override fun isFirst(): Boolean = currentRow == 1
    override fun isLast(): Boolean = false
    override fun beforeFirst() = throw SQLFeatureNotSupportedException()
    override fun afterLast() = throw SQLFeatureNotSupportedException()
    override fun first(): Boolean = throw SQLFeatureNotSupportedException()
    override fun last(): Boolean = throw SQLFeatureNotSupportedException()
    override fun absolute(row: Int): Boolean = throw SQLFeatureNotSupportedException()
    override fun relative(rows: Int): Boolean = throw SQLFeatureNotSupportedException()
    override fun previous(): Boolean = throw SQLFeatureNotSupportedException()

    override fun setFetchDirection(direction: Int) {}
    override fun setFetchSize(rows: Int) {}

    // --- Update stubs (read-only) ---
    override fun rowUpdated(): Boolean = false
    override fun rowInserted(): Boolean = false
    override fun rowDeleted(): Boolean = false
    override fun updateNull(ci: Int) = throw SQLFeatureNotSupportedException()
    override fun updateBoolean(ci: Int, x: Boolean) = throw SQLFeatureNotSupportedException()
    override fun updateByte(ci: Int, x: Byte) = throw SQLFeatureNotSupportedException()
    override fun updateShort(ci: Int, x: Short) = throw SQLFeatureNotSupportedException()
    override fun updateInt(ci: Int, x: Int) = throw SQLFeatureNotSupportedException()
    override fun updateLong(ci: Int, x: Long) = throw SQLFeatureNotSupportedException()
    override fun updateFloat(ci: Int, x: Float) = throw SQLFeatureNotSupportedException()
    override fun updateDouble(ci: Int, x: Double) = throw SQLFeatureNotSupportedException()
    override fun updateBigDecimal(ci: Int, x: BigDecimal?) = throw SQLFeatureNotSupportedException()
    override fun updateString(ci: Int, x: String?) = throw SQLFeatureNotSupportedException()
    override fun updateBytes(ci: Int, x: ByteArray?) = throw SQLFeatureNotSupportedException()
    override fun updateDate(ci: Int, x: Date?) = throw SQLFeatureNotSupportedException()
    override fun updateTime(ci: Int, x: Time?) = throw SQLFeatureNotSupportedException()
    override fun updateTimestamp(ci: Int, x: Timestamp?) = throw SQLFeatureNotSupportedException()
    override fun updateAsciiStream(ci: Int, x: InputStream?, l: Int) = throw SQLFeatureNotSupportedException()
    override fun updateBinaryStream(ci: Int, x: InputStream?, l: Int) = throw SQLFeatureNotSupportedException()
    override fun updateCharacterStream(ci: Int, x: Reader?, l: Int) = throw SQLFeatureNotSupportedException()
    override fun updateObject(ci: Int, x: Any?, s: Int) = throw SQLFeatureNotSupportedException()
    override fun updateObject(ci: Int, x: Any?) = throw SQLFeatureNotSupportedException()
    override fun updateNull(cl: String?) = throw SQLFeatureNotSupportedException()
    override fun updateBoolean(cl: String?, x: Boolean) = throw SQLFeatureNotSupportedException()
    override fun updateByte(cl: String?, x: Byte) = throw SQLFeatureNotSupportedException()
    override fun updateShort(cl: String?, x: Short) = throw SQLFeatureNotSupportedException()
    override fun updateInt(cl: String?, x: Int) = throw SQLFeatureNotSupportedException()
    override fun updateLong(cl: String?, x: Long) = throw SQLFeatureNotSupportedException()
    override fun updateFloat(cl: String?, x: Float) = throw SQLFeatureNotSupportedException()
    override fun updateDouble(cl: String?, x: Double) = throw SQLFeatureNotSupportedException()
    override fun updateBigDecimal(cl: String?, x: BigDecimal?) = throw SQLFeatureNotSupportedException()
    override fun updateString(cl: String?, x: String?) = throw SQLFeatureNotSupportedException()
    override fun updateBytes(cl: String?, x: ByteArray?) = throw SQLFeatureNotSupportedException()
    override fun updateDate(cl: String?, x: Date?) = throw SQLFeatureNotSupportedException()
    override fun updateTime(cl: String?, x: Time?) = throw SQLFeatureNotSupportedException()
    override fun updateTimestamp(cl: String?, x: Timestamp?) = throw SQLFeatureNotSupportedException()
    override fun updateAsciiStream(cl: String?, x: InputStream?, l: Int) = throw SQLFeatureNotSupportedException()
    override fun updateBinaryStream(cl: String?, x: InputStream?, l: Int) = throw SQLFeatureNotSupportedException()
    override fun updateCharacterStream(cl: String?, x: Reader?, l: Int) = throw SQLFeatureNotSupportedException()
    override fun updateObject(cl: String?, x: Any?, s: Int) = throw SQLFeatureNotSupportedException()
    override fun updateObject(cl: String?, x: Any?) = throw SQLFeatureNotSupportedException()
    override fun insertRow() = throw SQLFeatureNotSupportedException()
    override fun updateRow() = throw SQLFeatureNotSupportedException()
    override fun deleteRow() = throw SQLFeatureNotSupportedException()
    override fun refreshRow() = throw SQLFeatureNotSupportedException()
    override fun cancelRowUpdates() = throw SQLFeatureNotSupportedException()
    override fun moveToInsertRow() = throw SQLFeatureNotSupportedException()
    override fun moveToCurrentRow() = throw SQLFeatureNotSupportedException()

    override fun updateRef(ci: Int, x: Ref?) = throw SQLFeatureNotSupportedException()
    override fun updateRef(cl: String?, x: Ref?) = throw SQLFeatureNotSupportedException()
    override fun updateBlob(ci: Int, x: Blob?) = throw SQLFeatureNotSupportedException()
    override fun updateBlob(cl: String?, x: Blob?) = throw SQLFeatureNotSupportedException()
    override fun updateClob(ci: Int, x: Clob?) = throw SQLFeatureNotSupportedException()
    override fun updateClob(cl: String?, x: Clob?) = throw SQLFeatureNotSupportedException()
    override fun updateArray(ci: Int, x: Array?) = throw SQLFeatureNotSupportedException()
    override fun updateArray(cl: String?, x: Array?) = throw SQLFeatureNotSupportedException()
    override fun updateRowId(ci: Int, x: RowId?) = throw SQLFeatureNotSupportedException()
    override fun updateRowId(cl: String?, x: RowId?) = throw SQLFeatureNotSupportedException()
    override fun updateNString(ci: Int, x: String?) = throw SQLFeatureNotSupportedException()
    override fun updateNString(cl: String?, x: String?) = throw SQLFeatureNotSupportedException()
    override fun updateNClob(ci: Int, x: NClob?) = throw SQLFeatureNotSupportedException()
    override fun updateNClob(cl: String?, x: NClob?) = throw SQLFeatureNotSupportedException()
    override fun updateSQLXML(ci: Int, x: SQLXML?) = throw SQLFeatureNotSupportedException()
    override fun updateSQLXML(cl: String?, x: SQLXML?) = throw SQLFeatureNotSupportedException()
    override fun updateNCharacterStream(ci: Int, x: Reader?, l: Long) = throw SQLFeatureNotSupportedException()
    override fun updateNCharacterStream(cl: String?, x: Reader?, l: Long) = throw SQLFeatureNotSupportedException()
    override fun updateAsciiStream(ci: Int, x: InputStream?, l: Long) = throw SQLFeatureNotSupportedException()
    override fun updateAsciiStream(cl: String?, x: InputStream?, l: Long) = throw SQLFeatureNotSupportedException()
    override fun updateBinaryStream(ci: Int, x: InputStream?, l: Long) = throw SQLFeatureNotSupportedException()
    override fun updateBinaryStream(cl: String?, x: InputStream?, l: Long) = throw SQLFeatureNotSupportedException()
    override fun updateCharacterStream(ci: Int, x: Reader?, l: Long) = throw SQLFeatureNotSupportedException()
    override fun updateCharacterStream(cl: String?, x: Reader?, l: Long) = throw SQLFeatureNotSupportedException()
    override fun updateBlob(ci: Int, x: InputStream?, l: Long) = throw SQLFeatureNotSupportedException()
    override fun updateBlob(cl: String?, x: InputStream?, l: Long) = throw SQLFeatureNotSupportedException()
    override fun updateClob(ci: Int, x: Reader?, l: Long) = throw SQLFeatureNotSupportedException()
    override fun updateClob(cl: String?, x: Reader?, l: Long) = throw SQLFeatureNotSupportedException()
    override fun updateNClob(ci: Int, x: Reader?, l: Long) = throw SQLFeatureNotSupportedException()
    override fun updateNClob(cl: String?, x: Reader?, l: Long) = throw SQLFeatureNotSupportedException()
    override fun updateNCharacterStream(ci: Int, x: Reader?) = throw SQLFeatureNotSupportedException()
    override fun updateNCharacterStream(cl: String?, x: Reader?) = throw SQLFeatureNotSupportedException()
    override fun updateAsciiStream(ci: Int, x: InputStream?) = throw SQLFeatureNotSupportedException()
    override fun updateAsciiStream(cl: String?, x: InputStream?) = throw SQLFeatureNotSupportedException()
    override fun updateBinaryStream(ci: Int, x: InputStream?) = throw SQLFeatureNotSupportedException()
    override fun updateBinaryStream(cl: String?, x: InputStream?) = throw SQLFeatureNotSupportedException()
    override fun updateCharacterStream(ci: Int, x: Reader?) = throw SQLFeatureNotSupportedException()
    override fun updateCharacterStream(cl: String?, x: Reader?) = throw SQLFeatureNotSupportedException()
    override fun updateBlob(ci: Int, x: InputStream?) = throw SQLFeatureNotSupportedException()
    override fun updateBlob(cl: String?, x: InputStream?) = throw SQLFeatureNotSupportedException()
    override fun updateClob(ci: Int, x: Reader?) = throw SQLFeatureNotSupportedException()
    override fun updateClob(cl: String?, x: Reader?) = throw SQLFeatureNotSupportedException()
    override fun updateNClob(ci: Int, x: Reader?) = throw SQLFeatureNotSupportedException()
    override fun updateNClob(cl: String?, x: Reader?) = throw SQLFeatureNotSupportedException()

    override fun <T : Any?> unwrap(iface: Class<T>?): T = throw SQLFeatureNotSupportedException()
    override fun isWrapperFor(iface: Class<*>?): Boolean = false
}
