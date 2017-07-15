package svenmeier.coxswain.heart;

import android.support.annotation.Nullable;

import svenmeier.coxswain.Heart;

public interface ConnectionStatusListener {
    void onConnectionStatusChange(final Heart impl, final ConnectionStatus newStatus, final @Nullable String deviceName, final @Nullable String message);
}