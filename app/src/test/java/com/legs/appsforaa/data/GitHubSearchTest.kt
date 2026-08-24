package com.legs.appsforaa.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class GitHubSearchTest {

    private val search = GitHubSearch()

    @Test
    fun `accepts common GitHub repository references`() {
        assertEquals("owner/repo", search.parseRepoReference("owner/repo"))
        assertEquals("owner/repo", search.parseRepoReference("https://github.com/owner/repo"))
        assertEquals("owner/repo", search.parseRepoReference("https://www.github.com/owner/repo.git"))
    }

    @Test
    fun `normalizes deeper GitHub links to their repository`() {
        assertEquals("owner/repo", search.parseRepoReference("github.com/owner/repo/releases"))
        assertEquals("owner/repo", search.parseRepoReference("https://github.com/owner/repo/tree/main"))
        assertEquals("owner/repo", search.parseRepoReference("https://github.com/owner/repo/"))
    }

    @Test
    fun `rejects foreign and credentialed URLs`() {
        assertNull(search.parseRepoReference("https://gitlab.com/owner/repo"))
        assertNull(search.parseRepoReference("https://example.com/owner/repo"))
        assertNull(search.parseRepoReference("https://user@github.com/owner/repo"))
        assertNull(search.parseRepoReference("https://github.com:443/owner/repo"))
    }

    @Test
    fun `rejects malformed repository names`() {
        assertNull(search.parseRepoReference("owner"))
        assertNull(search.parseRepoReference("-owner/repo"))
        assertNull(search.parseRepoReference("owner-/repo"))
        assertNull(search.parseRepoReference("owner/repo name"))
    }

    @Test
    fun `repository descriptions discard page scripts and stay bounded`() {
        assertEquals(
            "A useful app",
            search.sanitizeDescription(
                "A useful app window.alert = function() { } document.write('<script>')"
            ),
        )
        assertEquals(280, search.sanitizeDescription("x".repeat(400)).length)
    }
}
