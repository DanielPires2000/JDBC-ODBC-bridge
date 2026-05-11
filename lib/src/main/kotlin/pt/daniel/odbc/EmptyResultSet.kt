package pt.daniel.odbc

import java.io.InputStream
import java.io.Reader
import java.math.BigDecimal
import java.net.URL
import java.sql.*
import java.sql.Array
import java.sql.Date
import java.util.*

/**
 * Um [ResultSet] que está sempre vazio — [next] devolve sempre false.
 *
 * Usado quando um método de metadata não é suportado pela fonte de dados
 * mas não deve lançar exceção (ex: getPrimaryKeys num ODBC sem chaves).
 *
 * O DBeaver e outras ferramentas tratam um ResultSet vazio como "sem dados",
 * em vez de mostrar um erro ao utilizador.
 */
class EmptyResultSet : ResultSet {
    private var closed = false

    override fun next(): Boolean = false
    override fun close() { closed = true }
    override fun isClosed(): Boolean = closed
    override fun wasNull(): Boolean = false
    override fun getRow(): Int = 0
    override fun getType(): Int = ResultSet.TYPE_FORWARD_ONLY
    override fun getConcurrency(): Int = ResultSet.CONCUR_READ_ONLY
    override fun getFetchDirection(): Int = ResultSet.FETCH_FORWARD
    override fun getFetchSize(): Int = 0
    override fun getHoldability(): Int = ResultSet.CLOSE_CURSORS_AT_COMMIT
    override fun getStatement(): Statement? = null
    override fun getMetaData(): ResultSetMetaData? = null
    override fun findColumn(columnLabel: String?): Int = throw SQLException("ResultSet vazio — sem colunas")
    override fun getWarnings(): SQLWarning? = null
    override fun clearWarnings() {}
    override fun getCursorName(): String? = null

    // --- Todos os getters devolvem null/0/false ---
    override fun getString(ci: Int): String? = null
    override fun getBoolean(ci: Int): Boolean = false
    override fun getByte(ci: Int): Byte = 0
    override fun getShort(ci: Int): Short = 0
    override fun getInt(ci: Int): Int = 0
    override fun getLong(ci: Int): Long = 0L
    override fun getFloat(ci: Int): Float = 0f
    override fun getDouble(ci: Int): Double = 0.0
    override fun getBytes(ci: Int): ByteArray? = null
    override fun getDate(ci: Int): Date? = null
    override fun getTime(ci: Int): Time? = null
    override fun getTimestamp(ci: Int): Timestamp? = null
    override fun getBigDecimal(ci: Int): BigDecimal? = null
    override fun getObject(ci: Int): Any? = null
    override fun getAsciiStream(ci: Int): InputStream? = null
    override fun getBinaryStream(ci: Int): InputStream? = null
    override fun getCharacterStream(ci: Int): Reader? = null

    override fun getString(cl: String?): String? = null
    override fun getBoolean(cl: String?): Boolean = false
    override fun getByte(cl: String?): Byte = 0
    override fun getShort(cl: String?): Short = 0
    override fun getInt(cl: String?): Int = 0
    override fun getLong(cl: String?): Long = 0L
    override fun getFloat(cl: String?): Float = 0f
    override fun getDouble(cl: String?): Double = 0.0
    override fun getBytes(cl: String?): ByteArray? = null
    override fun getDate(cl: String?): Date? = null
    override fun getTime(cl: String?): Time? = null
    override fun getTimestamp(cl: String?): Timestamp? = null
    override fun getBigDecimal(cl: String?): BigDecimal? = null
    override fun getObject(cl: String?): Any? = null
    override fun getAsciiStream(cl: String?): InputStream? = null
    override fun getBinaryStream(cl: String?): InputStream? = null
    override fun getCharacterStream(cl: String?): Reader? = null

    @Deprecated("Deprecated") override fun getBigDecimal(ci: Int, s: Int): BigDecimal? = null
    @Deprecated("Deprecated") override fun getBigDecimal(cl: String?, s: Int): BigDecimal? = null
    @Deprecated("Deprecated") override fun getUnicodeStream(ci: Int): InputStream? = null
    @Deprecated("Deprecated") override fun getUnicodeStream(cl: String?): InputStream? = null

    override fun getObject(ci: Int, map: MutableMap<String, Class<*>>?): Any? = null
    override fun getObject(cl: String?, map: MutableMap<String, Class<*>>?): Any? = null
    override fun <T : Any?> getObject(ci: Int, type: Class<T>?): T? = null
    override fun <T : Any?> getObject(cl: String?, type: Class<T>?): T? = null

    override fun getRef(ci: Int): Ref? = null
    override fun getBlob(ci: Int): Blob? = null
    override fun getClob(ci: Int): Clob? = null
    override fun getArray(ci: Int): Array? = null
    override fun getRef(cl: String?): Ref? = null
    override fun getBlob(cl: String?): Blob? = null
    override fun getClob(cl: String?): Clob? = null
    override fun getArray(cl: String?): Array? = null
    override fun getURL(ci: Int): URL? = null
    override fun getURL(cl: String?): URL? = null
    override fun getRowId(ci: Int): RowId? = null
    override fun getRowId(cl: String?): RowId? = null
    override fun getNClob(ci: Int): NClob? = null
    override fun getNClob(cl: String?): NClob? = null
    override fun getSQLXML(ci: Int): SQLXML? = null
    override fun getSQLXML(cl: String?): SQLXML? = null
    override fun getNString(ci: Int): String? = null
    override fun getNString(cl: String?): String? = null
    override fun getNCharacterStream(ci: Int): Reader? = null
    override fun getNCharacterStream(cl: String?): Reader? = null

    override fun getDate(ci: Int, cal: Calendar?): Date? = null
    override fun getTime(ci: Int, cal: Calendar?): Time? = null
    override fun getTimestamp(ci: Int, cal: Calendar?): Timestamp? = null
    override fun getDate(cl: String?, cal: Calendar?): Date? = null
    override fun getTime(cl: String?, cal: Calendar?): Time? = null
    override fun getTimestamp(cl: String?, cal: Calendar?): Timestamp? = null

    // --- Navegação (forward-only, já no fim) ---
    override fun isBeforeFirst(): Boolean = false
    override fun isAfterLast(): Boolean = true
    override fun isFirst(): Boolean = false
    override fun isLast(): Boolean = false
    override fun beforeFirst() {}
    override fun afterLast() {}
    override fun first(): Boolean = false
    override fun last(): Boolean = false
    override fun absolute(row: Int): Boolean = false
    override fun relative(rows: Int): Boolean = false
    override fun previous(): Boolean = false
    override fun setFetchDirection(direction: Int) {}
    override fun setFetchSize(rows: Int) {}

    // --- Updates (read-only, tudo no-op) ---
    override fun rowUpdated(): Boolean = false
    override fun rowInserted(): Boolean = false
    override fun rowDeleted(): Boolean = false
    override fun updateNull(ci: Int) {}
    override fun updateBoolean(ci: Int, x: Boolean) {}
    override fun updateByte(ci: Int, x: Byte) {}
    override fun updateShort(ci: Int, x: Short) {}
    override fun updateInt(ci: Int, x: Int) {}
    override fun updateLong(ci: Int, x: Long) {}
    override fun updateFloat(ci: Int, x: Float) {}
    override fun updateDouble(ci: Int, x: Double) {}
    override fun updateBigDecimal(ci: Int, x: BigDecimal?) {}
    override fun updateString(ci: Int, x: String?) {}
    override fun updateBytes(ci: Int, x: ByteArray?) {}
    override fun updateDate(ci: Int, x: Date?) {}
    override fun updateTime(ci: Int, x: Time?) {}
    override fun updateTimestamp(ci: Int, x: Timestamp?) {}
    override fun updateAsciiStream(ci: Int, x: InputStream?, l: Int) {}
    override fun updateBinaryStream(ci: Int, x: InputStream?, l: Int) {}
    override fun updateCharacterStream(ci: Int, x: Reader?, l: Int) {}
    override fun updateObject(ci: Int, x: Any?, s: Int) {}
    override fun updateObject(ci: Int, x: Any?) {}
    override fun updateNull(cl: String?) {}
    override fun updateBoolean(cl: String?, x: Boolean) {}
    override fun updateByte(cl: String?, x: Byte) {}
    override fun updateShort(cl: String?, x: Short) {}
    override fun updateInt(cl: String?, x: Int) {}
    override fun updateLong(cl: String?, x: Long) {}
    override fun updateFloat(cl: String?, x: Float) {}
    override fun updateDouble(cl: String?, x: Double) {}
    override fun updateBigDecimal(cl: String?, x: BigDecimal?) {}
    override fun updateString(cl: String?, x: String?) {}
    override fun updateBytes(cl: String?, x: ByteArray?) {}
    override fun updateDate(cl: String?, x: Date?) {}
    override fun updateTime(cl: String?, x: Time?) {}
    override fun updateTimestamp(cl: String?, x: Timestamp?) {}
    override fun updateAsciiStream(cl: String?, x: InputStream?, l: Int) {}
    override fun updateBinaryStream(cl: String?, x: InputStream?, l: Int) {}
    override fun updateCharacterStream(cl: String?, x: Reader?, l: Int) {}
    override fun updateObject(cl: String?, x: Any?, s: Int) {}
    override fun updateObject(cl: String?, x: Any?) {}
    override fun insertRow() {}
    override fun updateRow() {}
    override fun deleteRow() {}
    override fun refreshRow() {}
    override fun cancelRowUpdates() {}
    override fun moveToInsertRow() {}
    override fun moveToCurrentRow() {}

    override fun updateRef(ci: Int, x: Ref?) {}
    override fun updateRef(cl: String?, x: Ref?) {}
    override fun updateBlob(ci: Int, x: Blob?) {}
    override fun updateBlob(cl: String?, x: Blob?) {}
    override fun updateClob(ci: Int, x: Clob?) {}
    override fun updateClob(cl: String?, x: Clob?) {}
    override fun updateArray(ci: Int, x: Array?) {}
    override fun updateArray(cl: String?, x: Array?) {}
    override fun updateRowId(ci: Int, x: RowId?) {}
    override fun updateRowId(cl: String?, x: RowId?) {}
    override fun updateNString(ci: Int, x: String?) {}
    override fun updateNString(cl: String?, x: String?) {}
    override fun updateNClob(ci: Int, x: NClob?) {}
    override fun updateNClob(cl: String?, x: NClob?) {}
    override fun updateSQLXML(ci: Int, x: SQLXML?) {}
    override fun updateSQLXML(cl: String?, x: SQLXML?) {}
    override fun updateNCharacterStream(ci: Int, x: Reader?, l: Long) {}
    override fun updateNCharacterStream(cl: String?, x: Reader?, l: Long) {}
    override fun updateAsciiStream(ci: Int, x: InputStream?, l: Long) {}
    override fun updateAsciiStream(cl: String?, x: InputStream?, l: Long) {}
    override fun updateBinaryStream(ci: Int, x: InputStream?, l: Long) {}
    override fun updateBinaryStream(cl: String?, x: InputStream?, l: Long) {}
    override fun updateCharacterStream(ci: Int, x: Reader?, l: Long) {}
    override fun updateCharacterStream(cl: String?, x: Reader?, l: Long) {}
    override fun updateBlob(ci: Int, x: InputStream?, l: Long) {}
    override fun updateBlob(cl: String?, x: InputStream?, l: Long) {}
    override fun updateClob(ci: Int, x: Reader?, l: Long) {}
    override fun updateClob(cl: String?, x: Reader?, l: Long) {}
    override fun updateNClob(ci: Int, x: Reader?, l: Long) {}
    override fun updateNClob(cl: String?, x: Reader?, l: Long) {}
    override fun updateNCharacterStream(ci: Int, x: Reader?) {}
    override fun updateNCharacterStream(cl: String?, x: Reader?) {}
    override fun updateAsciiStream(ci: Int, x: InputStream?) {}
    override fun updateAsciiStream(cl: String?, x: InputStream?) {}
    override fun updateBinaryStream(ci: Int, x: InputStream?) {}
    override fun updateBinaryStream(cl: String?, x: InputStream?) {}
    override fun updateCharacterStream(ci: Int, x: Reader?) {}
    override fun updateCharacterStream(cl: String?, x: Reader?) {}
    override fun updateBlob(ci: Int, x: InputStream?) {}
    override fun updateBlob(cl: String?, x: InputStream?) {}
    override fun updateClob(ci: Int, x: Reader?) {}
    override fun updateClob(cl: String?, x: Reader?) {}
    override fun updateNClob(ci: Int, x: Reader?) {}
    override fun updateNClob(cl: String?, x: Reader?) {}

    override fun <T : Any?> unwrap(iface: Class<T>?): T? = null
    override fun isWrapperFor(iface: Class<*>?): Boolean = false
}
