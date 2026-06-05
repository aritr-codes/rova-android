package com.aritr.rova.service.export

import com.aritr.rova.data.VaultState
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Test

class VaultMoverTest {

    private class Recorder {
        val ops = mutableListOf<String>()
        var state = VaultState.PUBLIC
    }

    @Test fun moveIn_orderingCopyThenVaultingThenDeleteThenVaulted() = runBlocking {
        val r = Recorder()
        val mover = VaultMover(
            copyToPrivate = { r.ops += "copy" },
            deletePublic = { r.ops += "deletePublic" },
            publishExisting = { error("not used") },
            setVaulting = { r.ops += "state:VAULTING"; r.state = VaultState.VAULTING },
            setVaulted = { r.ops += "state:VAULTED"; r.state = VaultState.VAULTED },
            setUnvaulting = { error("not used") },
            setPublic = { error("not used") },
        )
        mover.moveIn("s1")
        assertEquals(listOf("copy", "state:VAULTING", "deletePublic", "state:VAULTED"), r.ops)
        assertEquals(VaultState.VAULTED, r.state)
    }

    @Test fun moveOut_orderingUnvaultingThenPublishThenPublic() = runBlocking {
        val r = Recorder()
        val mover = VaultMover(
            copyToPrivate = { error("not used") },
            deletePublic = { error("not used") },
            publishExisting = { r.ops += "publish" },
            setVaulting = { error("not used") },
            setVaulted = { error("not used") },
            setUnvaulting = { r.ops += "state:UNVAULTING"; r.state = VaultState.UNVAULTING },
            setPublic = { r.ops += "state:PUBLIC"; r.state = VaultState.PUBLIC },
        )
        mover.moveOut("s1")
        assertEquals(listOf("state:UNVAULTING", "publish", "state:PUBLIC"), r.ops)
        assertEquals(VaultState.PUBLIC, r.state)
    }

    @Test fun moveIn_crashAfterVaulting_resumeDeletesThenVaulted() = runBlocking {
        val r = Recorder().also { it.state = VaultState.VAULTING }
        val mover = VaultMover(
            copyToPrivate = { error("private copy already exists; must NOT re-copy") },
            deletePublic = { r.ops += "deletePublic" },
            publishExisting = { error("not used") },
            setVaulting = { error("not used") },
            setVaulted = { r.ops += "state:VAULTED"; r.state = VaultState.VAULTED },
            setUnvaulting = { error("not used") },
            setPublic = { error("not used") },
        )
        mover.finishVaulting("s1")   // recovery resume entry
        assertEquals(listOf("deletePublic", "state:VAULTED"), r.ops)
    }

    @Test fun moveOut_crashAfterUnvaulting_resumePublishesThenPublic() = runBlocking {
        val r = Recorder().also { it.state = VaultState.UNVAULTING }
        val mover = VaultMover(
            copyToPrivate = { error("not used") },
            deletePublic = { error("not used") },
            publishExisting = { r.ops += "publish" },
            setVaulting = { error("not used") },
            setVaulted = { error("not used") },
            setUnvaulting = { error("not used") },
            setPublic = { r.ops += "state:PUBLIC"; r.state = VaultState.PUBLIC },
        )
        mover.finishUnvaulting("s1")   // recovery resume entry
        assertEquals(listOf("publish", "state:PUBLIC"), r.ops)
    }
}
