package com.audioviz.runtime.install;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.audioviz.runtime.install.RuntimeInstaller.InstallOutcome;
import com.audioviz.runtime.install.RuntimeInstaller.InstallRequest;
import com.audioviz.runtime.install.RuntimeInstaller.InstallResult;
import com.audioviz.runtime.install.RuntimeInstaller.SignedManifestBytes;
import com.audioviz.runtime.release.ReleaseDescriptor;
import com.audioviz.runtime.release.RuntimePlatform;
import java.net.URI;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;

class RuntimeInstallValueTest {
    private static final ReleaseDescriptor DESCRIPTOR = new ReleaseDescriptor(
        URI.create("https://releases.mcav.live/manifest.json"),
        Map.of(),
        Set.of("releases.mcav.live"),
        1,
        1
    );

    @Test
    void signedManifestBytesAreDefensivelyCopied() {
        byte[] payload = {1, 2};
        byte[] signature = {3, 4};
        SignedManifestBytes signed = new SignedManifestBytes(payload, signature);
        payload[0] = 9;
        signature[0] = 9;
        byte[] returnedPayload = signed.payload();
        byte[] returnedSignature = signed.signature();
        returnedPayload[1] = 9;
        returnedSignature[1] = 9;

        assertArrayEquals(new byte[]{1, 2}, signed.payload());
        assertArrayEquals(new byte[]{3, 4}, signed.signature());
        assertThrows(NullPointerException.class, () -> new SignedManifestBytes(null, new byte[0]));
        assertThrows(NullPointerException.class, () -> new SignedManifestBytes(new byte[0], null));
    }

    @Test
    void installValueTypesRejectImpossibleStates() {
        assertThrows(
            IllegalArgumentException.class,
            () -> new InstallResult(InstallOutcome.CANCELLED, fakeRuntime())
        );
        assertThrows(
            IllegalArgumentException.class,
            () -> new InstallResult(InstallOutcome.INSTALLED, null)
        );
        assertThrows(NullPointerException.class, () -> new InstallResult(null, null));
        assertThrows(
            IllegalArgumentException.class,
            () -> new InstallRequest(DESCRIPTOR, RuntimePlatform.LINUX_X86_64, 0, 1)
        );
        assertThrows(
            IllegalArgumentException.class,
            () -> new InstallRequest(DESCRIPTOR, RuntimePlatform.LINUX_X86_64, 1, -1)
        );
        assertThrows(
            NullPointerException.class,
            () -> new InstallRequest(null, RuntimePlatform.LINUX_X86_64, 1, 1)
        );
        assertThrows(
            NullPointerException.class,
            () -> new InstallRequest(DESCRIPTOR, null, 1, 1)
        );
    }

    @Test
    void eventAndCancellationValuesAreBounded() {
        RuntimeInstallEvent event = new RuntimeInstallEvent(
            RuntimeInstallEvent.Stage.CHECKING,
            "checking_1",
            7
        );
        assertEquals("checking_1", event.code());
        assertThrows(
            NullPointerException.class,
            () -> new RuntimeInstallEvent(null, "checking", 1)
        );
        for (String code : new String[]{null, "", "UPPER", "has-dash", "x".repeat(65)}) {
            assertThrows(
                IllegalArgumentException.class,
                () -> new RuntimeInstallEvent(RuntimeInstallEvent.Stage.CHECKING, code, 1)
            );
        }
        assertThrows(
            IllegalArgumentException.class,
            () -> new RuntimeInstallEvent(RuntimeInstallEvent.Stage.CHECKING, "checking", -1)
        );
        assertThrows(IllegalArgumentException.class, () -> new CancellationToken(-1));
        CancellationToken token = new CancellationToken(7);
        assertEquals(7, token.generation());
        assertEquals(false, token.isCancelled());
        token.cancel();
        assertEquals(true, token.isCancelled());
    }

    private static com.audioviz.runtime.store.RuntimeStore.InstalledRuntime fakeRuntime() {
        return org.mockito.Mockito.mock(
            com.audioviz.runtime.store.RuntimeStore.InstalledRuntime.class
        );
    }
}
