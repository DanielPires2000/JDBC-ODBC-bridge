package pt.daniel.odbc.interop

import com.sun.jna.Library
import com.sun.jna.Native
import com.sun.jna.Pointer
import com.sun.jna.ptr.IntByReference
import com.sun.jna.ptr.PointerByReference
import com.sun.jna.ptr.ShortByReference

/**
 * Contrato JNA com a biblioteca nativa ODBC.
 * Usando uma interface permite substituí-la por um mock nos testes.
 */
interface OdbcApi : Library {

    companion object {
        // Constantes ODBC Standard (ISO/IEC 9075 & MSDN)
        const val SQL_HANDLE_ENV  = 1
        const val SQL_HANDLE_DBC  = 2
        const val SQL_HANDLE_STMT = 3

        const val SQL_SUCCESS           = 0
        const val SQL_SUCCESS_WITH_INFO = 1
        const val SQL_NO_DATA           = 100
        const val SQL_ERROR             = -1
        const val SQL_INVALID_HANDLE    = -2

        const val SQL_OV_ODBC3         = 3
        const val SQL_ATTR_ODBC_VERSION = 200
        const val SQL_DRIVER_NOPROMPT  = 0

        // Tipos SQL para mapeamento no ResultSet
        const val SQL_CHAR      = 1
        const val SQL_VARCHAR   = 12
        const val SQL_INTEGER   = 4
        const val SQL_SMALLINT  = 5
        const val SQL_FLOAT     = 6
        const val SQL_DOUBLE    = 8
        const val SQL_DATE      = 9
        const val SQL_TIMESTAMP = 11
        const val SQL_BIGINT    = -5
        const val SQL_NUMERIC   = 2
        const val SQL_DECIMAL   = 3

        // SQL_C types para SQLGetData / SQLBindParameter
        const val SQL_C_CHAR    = 1
        const val SQL_C_LONG    = 4
        const val SQL_C_SHORT   = 5
        const val SQL_C_DOUBLE  = 8
        const val SQL_C_DEFAULT = 99

        // Null indicator
        const val SQL_NULL_DATA = -1
        const val SQL_NTS       = -3 // Null-terminated string

        // Transaction constants
        const val SQL_COMMIT   = 0
        const val SQL_ROLLBACK = 1

        // AutoCommit attribute
        const val SQL_ATTR_AUTOCOMMIT    = 102
        const val SQL_AUTOCOMMIT_OFF     = 0
        const val SQL_AUTOCOMMIT_ON      = 1

        // Connection dead detection (ODBC 3.x)
        const val SQL_ATTR_CONNECTION_DEAD = 1209
        const val SQL_CD_TRUE  = 1
        const val SQL_CD_FALSE = 0

        // SQLGetInfo identifiers
        const val SQL_DBMS_NAME    = 17
        const val SQL_DBMS_VER     = 18
        const val SQL_DRIVER_NAME  = 6
        const val SQL_DRIVER_VER   = 7
        const val SQL_DATABASE_NAME = 16
        const val SQL_USER_NAME    = 47
        const val SQL_IDENTIFIER_QUOTE_CHAR = 29
        const val SQL_CATALOG_TERM = 42
        const val SQL_SCHEMA_TERM  = 39
        const val SQL_MAX_SCHEMA_NAME_LEN = 32
        const val SQL_MAX_CATALOG_NAME_LEN = 34
        const val SQL_MAX_TABLE_NAME_LEN = 35
        const val SQL_MAX_COLUMN_NAME_LEN = 30

        // Parameter direction
        const val SQL_PARAM_INPUT  = 1

        // SQLTables / SQLColumns nullable indicator
        const val SQL_NULLABLE     = 1
        const val SQL_NO_NULLS     = 0
        const val SQL_NULLABLE_UNKNOWN = 2

        /**
         * Tries to load the native ODBC library.
         * Throws [OdbcUnavailableException] if the DLL/SO is not present.
         */
        fun load(): OdbcApi {
            val libName = if (System.getProperty("os.name").contains("Windows", ignoreCase = true))
                "odbc32" else "odbc"
            return try {
                Native.load(libName, OdbcApi::class.java)
            } catch (e: UnsatisfiedLinkError) {
                throw OdbcUnavailableException(
                    pt.daniel.odbc.Messages.get("error.odbc.load", libName), e
                )
            }
        }

        /**
         * Verifica se um código de retorno ODBC indica sucesso.
         */
        fun isSuccess(returnCode: Short): Boolean =
            returnCode.toInt() in listOf(SQL_SUCCESS, SQL_SUCCESS_WITH_INFO)
    }

    // --- Handle management ---
    fun SQLAllocHandle(handleType: Short, inputHandle: Pointer?, outputHandlePtr: PointerByReference): Short
    fun SQLFreeHandle(handleType: Short, handle: Pointer?): Short

    // --- Environment ---
    fun SQLSetEnvAttr(envHandle: Pointer?, attribute: Int, valuePtr: Pointer?, stringLength: Int): Short

    // --- Connection ---
    fun SQLDriverConnect(
        hdbc: Pointer?, hwnd: Pointer?,
        inConnStr: String?, inConnStrLen: Short,
        outConnStr: Pointer?, outConnStrLen: Short,
        outConnStrLenPtr: Pointer?, completion: Short
    ): Short
    fun SQLDisconnect(hdbc: Pointer?): Short
    fun SQLSetConnectAttr(hdbc: Pointer?, attribute: Int, valuePtr: Pointer?, stringLength: Int): Short
    fun SQLGetConnectAttr(hdbc: Pointer?, attribute: Int, valuePtr: Pointer?, bufferLength: Int, stringLengthPtr: IntByReference?): Short
    fun SQLGetInfo(hdbc: Pointer?, infoType: Short, infoValue: ByteArray?, bufferLength: Short, stringLengthPtr: ShortByReference?): Short

    // --- Transaction ---
    fun SQLEndTran(handleType: Short, handle: Pointer?, completionType: Short): Short

    // --- Statement execution ---
    fun SQLExecDirect(hstmt: Pointer?, statementText: String?, textLength: Int): Short

    // --- Prepared Statement ---
    fun SQLPrepare(hstmt: Pointer?, statementText: String?, textLength: Int): Short
    fun SQLExecute(hstmt: Pointer?): Short
    fun SQLBindParameter(
        hstmt: Pointer?, parameterNumber: Short,
        inputOutputType: Short, valueType: Short, parameterType: Short,
        columnSize: Long, decimalDigits: Short,
        parameterValuePtr: Pointer?, bufferLength: Long,
        strLenOrIndPtr: Pointer?
    ): Short
    fun SQLCloseCursor(hstmt: Pointer?): Short
    fun SQLCancel(hstmt: Pointer?): Short

    // --- Result Set ---
    fun SQLFetch(hstmt: Pointer?): Short
    fun SQLGetData(
        hstmt: Pointer?, colNumber: Short,
        targetType: Short, targetValue: ByteArray?,
        bufferLength: Long, strLenOrInd: com.sun.jna.ptr.LongByReference?
    ): Short
    fun SQLNumResultCols(hstmt: Pointer?, columnCount: ShortByReference?): Short
    fun SQLDescribeCol(
        hstmt: Pointer?, colNumber: Short,
        colName: ByteArray?, bufferLength: Short,
        nameLength: ShortByReference?,
        dataType: ShortByReference?,
        columnSize: com.sun.jna.ptr.LongByReference?,
        decimalDigits: ShortByReference?,
        nullable: ShortByReference?
    ): Short
    fun SQLRowCount(hstmt: Pointer?, rowCount: com.sun.jna.ptr.LongByReference?): Short

    // --- Catalog ---
    fun SQLTables(
        hstmt: Pointer?,
        catalogName: String?, catalogNameLen: Short,
        schemaName: String?, schemaNameLen: Short,
        tableName: String?, tableNameLen: Short,
        tableType: String?, tableTypeLen: Short
    ): Short
    fun SQLColumns(
        hstmt: Pointer?,
        catalogName: String?, catalogNameLen: Short,
        schemaName: String?, schemaNameLen: Short,
        tableName: String?, tableNameLen: Short,
        columnName: String?, columnNameLen: Short
    ): Short

    // --- Diagnostics ---
    fun SQLGetDiagRec(
        handleType: Short, handle: Pointer?,
        recNumber: Short,
        sqlState: ByteArray?,
        nativeError: IntByReference?,
        messageText: ByteArray?,
        bufferLength: Short,
        textLength: ShortByReference?
    ): Short
}