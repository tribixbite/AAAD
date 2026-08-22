package com.legs.appsforaa.data

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Covers the one branch the on-device debug hook cannot reach.
 *
 * The other four ([SelfUpdateChecker.Result.NoRelease], `UpToDate`, `Available`, `Failed`) are
 * exercised against live GitHub through `DEBUG_UPDATE_CHECK`, which is the stronger test — it
 * proves the real API still answers the way this code assumes. `Disabled` cannot be reached that
 * way because `adb shell` drops a blank argument, and it is the branch that must never make a
 * network call, so it is worth pinning here.
 */
class SelfUpdateCheckerTest {

    /** The real resolver is passed deliberately: if this branch ever calls it, the test hangs. */
    private fun checkerWithRepo(repo: String) = SelfUpdateChecker(
        currentVersion = "2.1",
        repo = repo,
        currentPackage = "sksa.aa.customapps",
    )

    @Test
    fun `blank repo disables the check`() = runBlocking {
        assertEquals(SelfUpdateChecker.Result.Disabled, checkerWithRepo("").check())
    }

    @Test
    fun `whitespace repo disables the check`() = runBlocking {
        // A build configured with UPDATE_REPO=" " is configured with nothing; treating it as a
        // repo name would send a request for a repository that cannot exist.
        assertEquals(SelfUpdateChecker.Result.Disabled, checkerWithRepo("   ").check())
    }
}
