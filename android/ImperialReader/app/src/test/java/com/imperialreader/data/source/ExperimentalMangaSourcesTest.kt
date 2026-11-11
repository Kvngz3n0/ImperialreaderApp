package com.imperialreader.data.source

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import okio.Buffer
import java.io.IOException
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec

class ExperimentalMangaSourcesTest {
    @Test
    fun parsesToonkotsuCatalogAndChapters() {
        val catalog = JSONObject("""{"items":[{"code":"abc123","slug":"example","title":"Example","adult":false,"cover":"/cover.webp"},{"code":"bad","slug":"adult","title":"Adult","adult":true}],"hasNextPage":true}""")
        val page = ToonkotsuSource.parseCatalog(catalog, 1)
        assertEquals("https://toonkotsu.com/title/abc123-example", page.mangas.single().url)
        assertTrue(page.hasNextPage)
        val chapters = ToonkotsuSource.parseChapters(JSONArray("""[{"id":10,"n":1,"label":"Chapter 1"},{"id":11,"n":2,"label":"Chapter 2"}]"""), page.mangas.single().url)
        assertEquals(listOf("Chapter 2", "Chapter 1"), chapters.map { it.name })
        assertEquals("https://toonkotsu.com/title/abc123-example/11-chapter-2", chapters.first().url)
    }

    @Test(expected = IOException::class)
    fun rejectsIncompleteToonkotsuEncryptionMetadata() {
        ToonkotsuSource.parsePages(JSONObject("""{"pages":[{"src":"/cdn/e/image","k":"key"}]}"""))
    }

    @Test(expected = IOException::class)
    fun rejectsEncryptedPagesFromOtherHosts() {
        ToonkotsuSource.parsePages(JSONObject("""{"pages":[{"src":"https://other.example/cdn/e/image","k":"key","n":"nonce"}]}"""))
    }

    @Test(expected = IOException::class)
    fun rejectsInvalidToonkotsuKeys() {
        ToonkotsuSource.pageCipher("!!!", "AQ")
    }

    @Test
    fun parsesAndDecryptsToonkotsuPages() {
        val key = ByteArray(16) { it.toByte() }
        val nonce = ByteArray(12) { (it + 1).toByte() }
        val encoder = Base64.getUrlEncoder().withoutPadding()
        val encodedKey = encoder.encodeToString(key)
        val encodedNonce = encoder.encodeToString(nonce)
        val pages = ToonkotsuSource.parsePages(JSONObject("""{"pages":[{"src":"/cdn/e/image","k":"$encodedKey","n":"$encodedNonce"},{"src":"/plain.webp"}]}"""))
        assertEquals("https://toonkotsu.com/cdn/e/image", pages[0].url)
        assertEquals(null, pages[1].key)
        val plain = "reader page".toByteArray()
        val encrypted = Cipher.getInstance("AES/CTR/NoPadding").run {
            init(Cipher.ENCRYPT_MODE, SecretKeySpec(key, "AES"), IvParameterSpec(nonce.copyOf(16)))
            doFinal(plain)
        }
        assertArrayEquals(plain, ToonkotsuSource.pageCipher(pages[0].key!!, pages[0].nonce!!).doFinal(encrypted))
    }

    @Test
    fun mangaballChapterRequestHasBareJsonContentType() {
        val body = MangaBallSource.chapterRequestBody("example-id")
        val buffer = Buffer()
        body.writeTo(buffer)
        assertEquals("application/json", body.contentType().toString())
        assertEquals("example-id", JSONObject(buffer.readUtf8()).getString("title_id"))
    }

    @Test
    fun parsesMangaBallCatalogAndEnglishChapters() {
        val catalog = JSONObject("""{"data":[{"id":"abc","name":"Example","image":{"file":"https://cdn.example/cover.jpg"}}],"pagination":{"page":1,"total_pages":2}}""")
        val page = MangaBallSource.parseCatalog(catalog)
        assertEquals("https://mangaball.com/title-detail/abc", page.mangas.single().url)
        assertTrue(page.hasNextPage)
        val chapters = JSONArray("""[{"id":"first","number":1,"lang":"en"},{"id":"second","number":2,"lang":"en"},{"id":"third","number":3,"lang":"fr"}]""")
        assertEquals(listOf("Chapter 2", "Chapter 1"), MangaBallSource.parseChapters(chapters).map { it.name })
    }
}
