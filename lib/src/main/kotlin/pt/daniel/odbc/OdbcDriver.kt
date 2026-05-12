package pt.daniel.odbc

import pt.daniel.odbc.interop.OdbcApi
import pt.daniel.odbc.interop.OdbcDiagnostics
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
 * A API nativa é carregada de forma lazy — o driver regista-se sempre no DriverManager,
 * mesmo que o ODBC não esteja instalado. A falha só é reportada no momento do connect(),
 * permitindo que ferramentas como o DBeaver mostrem a mensagem de erro ao utilizador.
 *
 * Para testes, pode-se injetar uma API mock via o construtor interno.
 */
class OdbcDriver internal constructor(
    private val apiProvider: () -> OdbcApi
) : Driver {

    /**
     * Construtor público usado pelo DriverManager e ServiceLoader.
     * Não carrega a DLL nativa imediatamente — apenas no primeiro connect().
     */
    constructor() : this({ OdbcApi.load() })

    /**
     * API nativa carregada de forma lazy.
     * Se a DLL não existir, a exceção só é lançada quando se tenta usar.
     */
    private val api: OdbcApi by lazy { apiProvider() }

    companion object {
        private const val URL_PREFIX = "jdbc:odbc:"
        private val log: Logger = Logger.getLogger(OdbcDriver::class.java.name)

        private val VERSION_STR: String by lazy {
            runCatching {
                val props = java.util.Properties()
                OdbcDriver::class.java.getResourceAsStream("version.properties")?.use { 
                    props.load(it)
                }
                props.getProperty("version") ?: "0.0.1-SNAPSHOT"
            }.getOrElse { "0.0.1-SNAPSHOT" }
        }

        private val VERSION_MAJOR: Int by lazy {
            VERSION_STR.split(".").getOrNull(0)?.toIntOrNull() ?: 0
        }

        private val VERSION_MINOR: Int by lazy {
            VERSION_STR.split(".").getOrNull(1)?.toIntOrNull() ?: 0
        }

        init {
            DriverManager.registerDriver(OdbcDriver())
            log.fine("OdbcDriver registado no DriverManager (Versao $VERSION_STR)")
        }
    }

    // --- Implementação de java.sql.Driver ---

    override fun connect(url: String?, info: Properties?): Connection? {
        if (url == null || !acceptsURL(url)) return null

        val charsetName = info?.getProperty("charset") ?: "windows-1252"
        val charset = runCatching { java.nio.charset.Charset.forName(charsetName) }
            .getOrElse { java.nio.charset.Charset.defaultCharset() }

        var connectionString = url.substring(URL_PREFIX.length).trim()
        if (connectionString.isBlank()) {
            throw SQLException("A string de conexão ODBC não pode ser vazia (URL: $url)")
        }

        // Se a string não contiver '=', assume que é apenas o nome do DSN
        if (!connectionString.contains("=")) {
            connectionString = "DSN=$connectionString"
        }

        // Junta as propriedades passadas (ex: pelo DBeaver) como UID, PWD, etc.
        if (info != null) {
            val user = info.getProperty("user")
            if (user != null && !connectionString.contains("UID=", ignoreCase = true)) {
                connectionString += ";UID=$user"
            }
            val password = info.getProperty("password")
            if (password != null && !connectionString.contains("PWD=", ignoreCase = true)) {
                connectionString += ";PWD=$password"
            }
            
            // Adiciona outras propriedades se houver
            for (key in info.stringPropertyNames()) {
                if (key.equals("user", ignoreCase = true) || key.equals("password", ignoreCase = true)) continue
                connectionString += ";$key=${info.getProperty(key)}"
            }
        }

        // Tenta aceder à API nativa. Se o ODBC não estiver instalado,
        // lança uma SQLException com mensagem clara para o utilizador.
        val odbcApi = try {
            api
        } catch (e: OdbcUnavailableException) {
            throw SQLException(
                "O driver ODBC nativo não está disponível neste sistema. " +
                "Verifique se o ODBC está instalado. Detalhe: ${e.message}",
                "08001", // SQLSTATE: connection exception
                e
            )
        }

        val envHandle = allocateEnvironment(odbcApi)
        val connectionHandle = try {
            allocateConnection(odbcApi, envHandle, connectionString)
        } catch (e: SQLException) {
            // Se falhar na conexão, limpa o ambiente antes de relançar
            odbcApi.SQLFreeHandle(OdbcApi.SQL_HANDLE_ENV.toShort(), envHandle)
            throw e
        }

        return OdbcConnection(odbcApi, envHandle, connectionHandle, charset)
    }

    override fun acceptsURL(url: String?): Boolean =
        url?.startsWith(URL_PREFIX) ?: false

    override fun getPropertyInfo(url: String?, info: Properties?): Array<DriverPropertyInfo> {
        val charsetProp = DriverPropertyInfo("charset", info?.getProperty("charset") ?: "windows-1252")
        charsetProp.description = "Codificação de caracteres usada pelo driver nativo ODBC (ex: windows-1252, UTF-8, ISO-8859-1)"
        charsetProp.required = false
        charsetProp.choices = arrayOf("windows-1252", "UTF-8", "ISO-8859-1", "US-ASCII")

        return arrayOf(charsetProp)
    }

    override fun getMajorVersion(): Int = VERSION_MAJOR
    override fun getMinorVersion(): Int = VERSION_MINOR
    override fun jdbcCompliant(): Boolean = false

    override fun getParentLogger(): Logger = log


    // --- Helpers privados com tratamento de erros ---

    /**
     * Aloca e configura um Handle de Ambiente ODBC.
     */
    private fun allocateEnvironment(odbcApi: OdbcApi): com.sun.jna.Pointer {
        val envPtr = com.sun.jna.ptr.PointerByReference()

        val allocResult = runCatching {
            odbcApi.SQLAllocHandle(OdbcApi.SQL_HANDLE_ENV.toShort(), null, envPtr)
        }.getOrElse {
            throw SQLException("Falha de comunicação ao alocar ambiente ODBC: ${it.message}", it)
        }

        if (allocResult.toInt() !in listOf(OdbcApi.SQL_SUCCESS, OdbcApi.SQL_SUCCESS_WITH_INFO)) {
            throw SQLException("ODBC não conseguiu alocar ambiente (Código: $allocResult)")
        }

        val env = envPtr.value

        // Define a versão ODBC 3.x (obrigatório)
        runCatching {
            odbcApi.SQLSetEnvAttr(
                env,
                OdbcApi.SQL_ATTR_ODBC_VERSION,
                com.sun.jna.Pointer(OdbcApi.SQL_OV_ODBC3.toLong()),
                0
            )
        }.getOrElse {
            odbcApi.SQLFreeHandle(OdbcApi.SQL_HANDLE_ENV.toShort(), env)
            throw SQLException("Falha ao definir versão ODBC: ${it.message}", it)
        }

        return env
    }

    /**
     * Aloca um Handle de Conexão e estabelece a ligação com [connectionString].
     */
    private fun allocateConnection(
        odbcApi: OdbcApi,
        env: com.sun.jna.Pointer,
        connectionString: String
    ): com.sun.jna.Pointer {
        val connPtr = com.sun.jna.ptr.PointerByReference()

        val allocResult = runCatching {
            odbcApi.SQLAllocHandle(OdbcApi.SQL_HANDLE_DBC.toShort(), env, connPtr)
        }.getOrElse {
            throw SQLException("Falha de comunicação ao alocar conexão ODBC: ${it.message}", it)
        }

        if (allocResult.toInt() !in listOf(OdbcApi.SQL_SUCCESS, OdbcApi.SQL_SUCCESS_WITH_INFO)) {
            throw SQLException("ODBC não conseguiu alocar conexão (Código: $allocResult)")
        }

        val hdbc = connPtr.value

        val connectResult = runCatching {
            odbcApi.SQLDriverConnect(
                hdbc, null,
                connectionString, connectionString.length.toShort(),
                null, 0, null,
                OdbcApi.SQL_DRIVER_NOPROMPT.toShort()
            )
        }.getOrElse {
            odbcApi.SQLFreeHandle(OdbcApi.SQL_HANDLE_DBC.toShort(), hdbc)
            throw SQLException("Falha de comunicação ao ligar ao ODBC: ${it.message}", it)
        }

        if (connectResult.toInt() !in listOf(OdbcApi.SQL_SUCCESS, OdbcApi.SQL_SUCCESS_WITH_INFO)) {
            // Extrai mensagem de diagnóstico ODBC detalhada
            val diagMsg = OdbcDiagnostics.getDiagMessage(
                odbcApi, OdbcApi.SQL_HANDLE_DBC.toShort(), hdbc
            )
            odbcApi.SQLFreeHandle(OdbcApi.SQL_HANDLE_DBC.toShort(), hdbc)
            throw SQLException(
                "ODBC recusou a conexão (Código: $connectResult). $diagMsg",
                "08001"
            )
        }

        return hdbc
    }
}