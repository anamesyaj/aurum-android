package ai.aurum.personal;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public final class BackendConfigTest {
    @Test
    public void acceptsHttpsAndNormalizesTrailingSlash() {
        BackendConfig.Validation validation = BackendConfig.validate("https://aurum.example.com/");
        assertTrue(validation.valid);
        assertEquals("https://aurum.example.com", validation.normalized);
        assertFalse(validation.privateHttp);
    }

    @Test
    public void rejectsPublicPlainHttp() {
        BackendConfig.Validation validation = BackendConfig.validate("http://example.com:3200");
        assertFalse(validation.valid);
    }

    @Test
    public void acceptsPrivateLanPlainHttpForDevelopment() {
        BackendConfig.Validation validation = BackendConfig.validate("http://192.168.1.20:3200/");
        assertTrue(validation.valid);
        assertEquals("http://192.168.1.20:3200", validation.normalized);
        assertTrue(validation.privateHttp);
    }

    @Test
    public void acceptsPrivateOverlayAddressForDevelopment() {
        BackendConfig.Validation validation = BackendConfig.validate("http://100.90.1.2:3200");
        assertTrue(validation.valid);
        assertTrue(validation.privateHttp);
    }

    @Test
    public void rejectsCredentialsInUrl() {
        BackendConfig.Validation validation = BackendConfig.validate("https://user:secret@aurum.example.com");
        assertFalse(validation.valid);
    }

    @Test
    public void rejectsQueryOrFragmentInBaseUrl() {
        assertFalse(BackendConfig.validate("https://aurum.example.com?token=secret").valid);
        assertFalse(BackendConfig.validate("https://aurum.example.com/#settings").valid);
    }
}
