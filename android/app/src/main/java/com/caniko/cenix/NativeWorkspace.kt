package com.caniko.cenix

import com.caniko.cenix.uniffi.WorkspaceCommand
import com.caniko.cenix.uniffi.WorkspaceSnapshot
import com.caniko.cenix.uniffi.WorkspaceTransition
import com.caniko.cenix.uniffi.applyWorkspaceCommand

object NativeWorkspace {
    @JvmStatic
    fun apply(snapshot: WorkspaceSnapshot, command: WorkspaceCommand): WorkspaceTransition =
        applyWorkspaceCommand(snapshot, command)
}
