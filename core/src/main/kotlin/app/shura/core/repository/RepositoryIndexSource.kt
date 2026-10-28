package app.shura.core.repository

fun interface RepositoryIndexSource {
    fun fetch(repository: RepositoryAddress): String
}
