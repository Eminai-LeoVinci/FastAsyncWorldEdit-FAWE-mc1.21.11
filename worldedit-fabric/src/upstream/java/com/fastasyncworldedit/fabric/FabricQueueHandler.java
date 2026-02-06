package com.fastasyncworldedit.fabric;

import com.fastasyncworldedit.core.queue.implementation.QueueHandler;

public class FabricQueueHandler extends QueueHandler {

    @Override
    public void startUnsafe(boolean parallel) {
        // No-op on Fabric for now.
    }

    @Override
    public void endUnsafe(boolean parallel) {
        // No-op on Fabric for now.
    }
}
