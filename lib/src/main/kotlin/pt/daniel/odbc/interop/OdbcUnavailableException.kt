package pt.daniel.odbc.interop

/**
 * Lançada quando a biblioteca nativa ODBC não está disponível no sistema.
 * Permite distinguir entre "ODBC não instalado" e outros erros de SQL.
 */
class OdbcUnavailableException(message: String, cause: Throwable? = null)
    : RuntimeException(message, cause)
