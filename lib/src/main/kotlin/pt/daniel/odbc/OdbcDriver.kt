package pt.daniel.odbc

import pt.daniel.odbc.interop.OdbcApi
import pt.daniel.odbc.interop.OdbcUnavailableException
import java.sql.*
import java.util.Properties
import java.util.logging.Logger

/**
 * Ponto de entrada do driver JDBC-ODBC.
 *
 * O [DriverManager] do Java descobre este driver através do ficheiro
 * META-INF/services/java.sql.Driver (ServiceLoader), ou carregando a classe explicitamente.
 *
 * O URL de conexão esperado é: jdbc:odbc:<odbc_connection_string>
 * Exemplo: jdbc:odbc:DSN=MeuBanco;UID=user;PWD=pass
 *
 * @param api  API nativa ODBC. Por defeito carrega a biblioteca do sistema.
 *             Pode ser injetada num teste para usar um mock.
 */
class OdbcDriver(
    private val api: OdbcApi = loadApiSafely()
) : Driver {

    companion object {
        private const val URL_PREFIX = "jdbc:odbc:"

        /**
         * Auto-registo no DriverManager quando a classe é carregada (padrão JDBC).
         * Usa try-catch para não crashar a JVM se o ODBC não estiver disponível.
         */
        init {
            try {
                DriverManager.registerDriver(OdbcDriver())
            } catch (e: OdbcUnavailableException) {
                // ODBC não está instalado: regista no log mas não lança exceção.
                // O DriverManager simplesmente não vai ter este driver disponível.
                Logger.getLogger(OdbcDriver::class.java.name).warning(
                    "Driver ODBC não registado: ${e.message}"
                )
            }
        }

        /**
         * Tenta carregar a biblioteca nativa. Retorna null se não estiver disponível.
         * Utilizado internamente para evitar que o bloco init crashe a aplicação.
         */
        private fun loadApiSafely(): OdbcApi = OdbcApi.load()
    }

    // --- Implementação de java.sql.Driver ---

    override fun connect(url: String?, info: Properties?): Connection? {
        if (url == null || !acceptsURL(url)) return null

        val connectionString = url.substring(URL_PREFIX.length)
        if (connectionString.isBlank()) {
            throw SQLException("A string de conexão ODBC não pode ser vazia (URL: $url)")
        }

        val envHandle = allocateEnvironment()
        val connectionHandle = try {
            allocateConnection(envHandle, connectionString)
        } catch (e: SQLException) {
            // Se falhar na conexão, limpa o ambiente antes de relançar
            api.SQLFreeHandle(OdbcApi.SQL_HANDLE_ENV.toShort(), envHandle)
            throw e
        }

        return OdbcConnection(api, envHandle, connectionHandle)
    }

    override fun acceptsURL(url: String?): Boolean =
        url?.startsWith(URL_PREFIX) ?: false

    override fun getPropertyInfo(url: String?, info: Properties?): Array<DriverPropertyInfo> =
        emptyArray()

    override fun getMajorVersion(): Int = 1
    override fun getMinorVersion(): Int = 0
    override fun jdbcCompliant(): Boolean = false

    override fun getParentLogger(): Logger =
        throw SQLFeatureNotSupportedException("java.util.logging não suportado")

    // --- Helpers privados com tratamento de erros ---

    /**
     * Aloca e configura um Handle de Ambiente ODBC.
     */
    private fun allocateEnvironment(): com.sun.jna.Pointer {
        val envPtr = com.sun.jna.ptr.PointerByReference()

        val allocResult = runCatching {
            api.SQLAllocHandle(OdbcApi.SQL_HANDLE_ENV.toShort(), null, envPtr)
        }.getOrElse {
            throw SQLException("Falha de comunicação ao alocar ambiente ODBC: ${it.message}", it)
        }

        if (allocResult.toInt() !in listOf(OdbcApi.SQL_SUCCESS, OdbcApi.SQL_SUCCESS_WITH_INFO)) {
            throw SQLException("ODBC não conseguiu alocar ambiente (Código: $allocResult)")
        }

        val env = envPtr.value

        // Define a versão ODBC 3.x (obrigatório)
        runCatching {
            api.SQLSetEnvAttr(
                env,
                OdbcApi.SQL_ATTR_ODBC_VERSION,
                com.sun.jna.Pointer(OdbcApi.SQL_OV_ODBC3.toLong()),
                0
            )
        }.getOrElse {
            api.SQLFreeHandle(OdbcApi.SQL_HANDLE_ENV.toShort(), env)
            throw SQLException("Falha ao definir versão ODBC: ${it.message}", it)
        }

        return env
    }

    /**
     * Aloca um Handle de Conexão e estabelece a ligação com [connectionString].
     */
    private fun allocateConnection(
        env: com.sun.jna.Pointer,
        connectionString: String
    ): com.sun.jna.Pointer {
        val connPtr = com.sun.jna.ptr.PointerByReference()

        val allocResult = runCatching {
            api.SQLAllocHandle(OdbcApi.SQL_HANDLE_DBC.toShort(), env, connPtr)
        }.getOrElse {
            throw SQLException("Falha de comunicação ao alocar conexão ODBC: ${it.message}", it)
        }

        if (allocResult.toInt() !in listOf(OdbcApi.SQL_SUCCESS, OdbcApi.SQL_SUCCESS_WITH_INFO)) {
            throw SQLException("ODBC não conseguiu alocar conexão (Código: $allocResult)")
        }

        val hdbc = connPtr.value

        val connectResult = runCatching {
            api.SQLDriverConnect(
                hdbc, null,
                connectionString, connectionString.length.toShort(),
                null, 0, null,
                OdbcApi.SQL_DRIVER_NOPROMPT.toShort()
            )
        }.getOrElse {
            api.SQLFreeHandle(OdbcApi.SQL_HANDLE_DBC.toShort(), hdbc)
            throw SQLException("Falha de comunicação ao ligar ao ODBC: ${it.message}", it)
        }

        if (connectResult.toInt() !in listOf(OdbcApi.SQL_SUCCESS, OdbcApi.SQL_SUCCESS_WITH_INFO)) {
            api.SQLFreeHandle(OdbcApi.SQL_HANDLE_DBC.toShort(), hdbc)
            throw SQLException(
                "ODBC recusou a conexão para '$connectionString' (Código: $connectResult). " +
                "Verifique se o DSN existe e as credenciais estão corretas."
            )
        }

        return hdbc
    }
}