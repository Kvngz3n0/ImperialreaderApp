package com.imperialreader.data.source

interface CreatorSearchSource {
    suspend fun searchCreators(query: String, page: Int): MangasPage
}
