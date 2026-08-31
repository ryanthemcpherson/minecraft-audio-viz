package com.audioviz.runtime.supervisor;

public enum SupervisorState {
    DISABLED,
    CHECKING,
    DOWNLOADING,
    VERIFYING,
    STAGING,
    STARTING,
    READY,
    DEGRADED,
    BACKING_OFF,
    ROLLING_BACK,
    FAILED,
    STOPPING
}
