package pt.daniel.odbc

import java.sql.ResultSetMetaData
import java.sql.SQLException
import java.sql.SQLFeatureNotSupportedException
import java.sql.Types

/**
 * Representa os metadados de uma única coluna de um ResultSet.
 */
data class ColumnInfo(
    val name: String,
    val dataType: Short,
    val columnSize: Int,
    val decimalDigits: Short,
    val nullable: Short
)

/**
 * Implementação de [ResultSetMetaData] que expõe a informação das colunas
 * obtidas via SQLDescribeCol.
 */
class OdbcResultSetMetaData(
    private val columns: List<ColumnInfo>
) : ResultSetMetaData {

    override fun getColumnCount(): Int = columns.size

    private fun getCol(column: Int): ColumnInfo {
        if (column < 1 || column > columns.size) {
            throw SQLException(pt.daniel.odbc.Messages.get("error.metadata.invalid.column", column, columns.size))
        }
        return columns[column - 1]
    }

    override fun isAutoIncrement(column: Int): Boolean = false
    override fun isCaseSensitive(column: Int): Boolean = true
    override fun isSearchable(column: Int): Boolean = true
    override fun isCurrency(column: Int): Boolean = false
    override fun isNullable(column: Int): Int =
        if (getCol(column).nullable.toInt() == 1) ResultSetMetaData.columnNullable else ResultSetMetaData.columnNoNulls

    override fun isSigned(column: Int): Boolean = true
    override fun getColumnDisplaySize(column: Int): Int = getCol(column).columnSize.coerceAtLeast(10)
    override fun getColumnLabel(column: Int): String = getCol(column).name
    override fun getColumnName(column: Int): String = getCol(column).name
    override fun getSchemaName(column: Int): String = ""
    override fun getPrecision(column: Int): Int = getCol(column).columnSize
    override fun getScale(column: Int): Int = getCol(column).decimalDigits.toInt()
    override fun getTableName(column: Int): String = ""
    override fun getCatalogName(column: Int): String = ""

    override fun getColumnType(column: Int): Int {
        val odbcType = getCol(column).dataType.toInt()
        return when (odbcType) {
            -7 -> Types.BIT
            -6 -> Types.TINYINT
            5 -> Types.SMALLINT
            4 -> Types.INTEGER
            -5 -> Types.BIGINT
            6 -> Types.FLOAT
            7 -> Types.REAL
            8 -> Types.DOUBLE
            2 -> Types.NUMERIC
            3 -> Types.DECIMAL
            1 -> Types.CHAR
            12 -> Types.VARCHAR
            -1 -> Types.LONGVARCHAR
            91, 9 -> Types.DATE // 9 is ODBC 2.0 Date
            92, 10 -> Types.TIME
            93, 11 -> Types.TIMESTAMP
            -2 -> Types.BINARY
            -3 -> Types.VARBINARY
            -4 -> Types.LONGVARBINARY
            -8, -9, -10 -> Types.NVARCHAR
            else -> Types.VARCHAR
        }
    }

    override fun getColumnTypeName(column: Int): String {
        return when (getColumnType(column)) {
            Types.BIT -> "BIT"
            Types.TINYINT -> "TINYINT"
            Types.SMALLINT -> "SMALLINT"
            Types.INTEGER -> "INTEGER"
            Types.BIGINT -> "BIGINT"
            Types.FLOAT -> "FLOAT"
            Types.REAL -> "REAL"
            Types.DOUBLE -> "DOUBLE"
            Types.NUMERIC -> "NUMERIC"
            Types.DECIMAL -> "DECIMAL"
            Types.CHAR -> "CHAR"
            Types.VARCHAR -> "VARCHAR"
            Types.LONGVARCHAR -> "LONGVARCHAR"
            Types.DATE -> "DATE"
            Types.TIME -> "TIME"
            Types.TIMESTAMP -> "TIMESTAMP"
            Types.BINARY -> "BINARY"
            Types.VARBINARY -> "VARBINARY"
            Types.LONGVARBINARY -> "LONGVARBINARY"
            Types.NVARCHAR -> "NVARCHAR"
            else -> "VARCHAR"
        }
    }

    override fun isReadOnly(column: Int): Boolean = true
    override fun isWritable(column: Int): Boolean = false
    override fun isDefinitelyWritable(column: Int): Boolean = false

    override fun getColumnClassName(column: Int): String {
        return when (getColumnType(column)) {
            Types.BIT -> "java.lang.Boolean"
            Types.TINYINT -> "java.lang.Byte"
            Types.SMALLINT -> "java.lang.Short"
            Types.INTEGER -> "java.lang.Integer"
            Types.BIGINT -> "java.lang.Long"
            Types.FLOAT, Types.DOUBLE -> "java.lang.Double"
            Types.REAL -> "java.lang.Float"
            Types.NUMERIC, Types.DECIMAL -> "java.math.BigDecimal"
            Types.DATE -> "java.sql.Date"
            Types.TIME -> "java.sql.Time"
            Types.TIMESTAMP -> "java.sql.Timestamp"
            Types.BINARY, Types.VARBINARY, Types.LONGVARBINARY -> "byte[]"
            else -> "java.lang.String"
        }
    }

    override fun <T : Any?> unwrap(iface: Class<T>?): T = throw SQLFeatureNotSupportedException()
    override fun isWrapperFor(iface: Class<*>?): Boolean = false
}
