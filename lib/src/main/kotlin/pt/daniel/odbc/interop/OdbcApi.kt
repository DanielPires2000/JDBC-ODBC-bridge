package pt.daniel.odbc.interop

import com.sun.jna.Library
import com.sun.jna.Native
import com.sun.jna.Pointer
import com.sun.jna.ptr.PointerByReference

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
        const val SQL_ERROR             = -1
        const val SQL_INVALID_HANDLE    = -2

        const val SQL_OV_ODBC3         = 3
        const val SQL_ATTR_ODBC_VERSION = 200
        const val SQL_DRIVER_NOPROMPT  = 0

        /**
         * Tenta carregar a biblioteca nativa.
         * Lança [OdbcUnavailableException] se a DLL/SO não estiver presente.
         */
        fun load(): OdbcApi {
            val libName = if (System.getProperty("os.name").contains("Windows", ignoreCase = true))
                "odbc32" else "odbc"
            return try {
                Native.load(libName, OdbcApi::class.java)
            } catch (e: UnsatisfiedLinkError) {
                throw OdbcUnavailableException(
                    "Não foi possível carregar a biblioteca ODBC ('$libName'). " +
                    "Verifique se o ODBC está instalado no sistema.", e
                )
            }
        }
    }

    fun SQLAllocHandle(handleType: Short, inputHandle: Pointer?, outputHandlePtr: PointerByReference): Short
    fun SQLFreeHandle(handleType: Short, handle: Pointer?): Short
    fun SQLSetEnvAttr(envHandle: Pointer?, attribute: Int, valuePtr: Pointer?, stringLength: Int): Short
    fun SQLDriverConnect(
        hdbc: Pointer?, hwnd: Pointer?,
        inConnStr: String?, inConnStrLen: Short,
        outConnStr: Pointer?, outConnStrLen: Short,
        outConnStrLenPtr: Pointer?, completion: Short
    ): Short
    fun SQLDisconnect(hdbc: Pointer?): Short
    fun SQLExecDirect(hstmt: Pointer?, statementText: String?, textLength: Int): Short
}