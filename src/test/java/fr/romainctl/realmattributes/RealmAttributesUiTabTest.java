/*
 * SPDX-License-Identifier: Apache-2.0
 */
package fr.romainctl.realmattributes;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Proxy;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.keycloak.component.ComponentModel;
import org.keycloak.models.RealmModel;

/** Unit tests for the JSON parser, seeding and ComponentModel helpers used by RealmAttributesUiTab. */
class RealmAttributesUiTabTest {

    // --- parseAttrs(String) (JSON parser) ---------------------------------

    @Test
    void parses_empty_inputs_as_empty_map() {
        assertTrue(RealmAttributesUiTab.parseAttrs((String) null).isEmpty());
        assertTrue(RealmAttributesUiTab.parseAttrs("").isEmpty());
        assertTrue(RealmAttributesUiTab.parseAttrs("   ").isEmpty());
    }

    @Test
    void parses_typical_payload() {
        Map<String, String> out = RealmAttributesUiTab.parseAttrs(
                "[{\"key\":\"foo\",\"value\":\"42\"},"
                        + " {\"key\":\"bar\",\"value\":\"hello\"}]");
        assertEquals("42", out.get("foo"));
        assertEquals("hello", out.get("bar"));
        assertEquals(2, out.size());
    }

    @Test
    void parseAttrs_rejects_reserved_keys() {
        // The reserved route-param names ("realm", "tab") are no longer
        // silently dropped: the parser now raises so a misconfigured form
        // is surfaced instead of losing data.
        assertThrows(IllegalArgumentException.class,
                () -> RealmAttributesUiTab.parseAttrs(
                        "[{\"key\":\"tab\",\"value\":\"attributes\"},"
                                + " {\"key\":\"realm\",\"value\":\"master\"}]"));
        assertThrows(IllegalArgumentException.class,
                () -> RealmAttributesUiTab.parseAttrs(
                        "[{\"key\":\"realm\",\"value\":\"master\"}]"));
        assertThrows(IllegalArgumentException.class,
                () -> RealmAttributesUiTab.parseAttrs(
                        "[{\"key\":\"tab\",\"value\":\"attributes\"}]"));
    }

    @Test
    void parseAttrs_rejects_blank_keys() {
        // Empty keys were silently dropped before; now the parser raises so
        // the admin UI surfaces a clear validation error.
        assertThrows(IllegalArgumentException.class,
                () -> RealmAttributesUiTab.parseAttrs(
                        "[{\"key\":\"\",\"value\":\"ignored\"}]"));
        // A valid key alongside a blank key still aborts the whole parse
        // (the parser does not skip-and-continue).
        assertThrows(IllegalArgumentException.class,
                () -> RealmAttributesUiTab.parseAttrs(
                        "[{\"key\":\"\",\"value\":\"ignored\"},"
                                + " {\"key\":\"real\",\"value\":\"kept\"}]"));
    }

    @Test
    void rejects_non_array_root() {
        assertThrows(RuntimeException.class,
                () -> RealmAttributesUiTab.parseAttrs("{\"foo\":\"bar\"}"));
    }

    @Test
    void rejects_malformed_json() {
        assertThrows(RuntimeException.class,
                () -> RealmAttributesUiTab.parseAttrs("not-json"));
    }

    // --- parseAttrs(ComponentModel) ---------------------------------------

    @Test
    void parseAttrs_component_handles_null() {
        assertTrue(RealmAttributesUiTab.parseAttrs((ComponentModel) null).isEmpty());
    }

    @Test
    void parseAttrs_component_handles_empty_config() {
        // ComponentModel with no config at all is treated as "no attributes".
        ComponentModel model = new ComponentModel();
        assertTrue(RealmAttributesUiTab.parseAttrs(model).isEmpty());
    }

    @Test
    void parseAttrs_component_reads_attributes_config() {
        ComponentModel model = new ComponentModel();
        model.put(RealmAttributesUiTab.ATTRS_KEY,
                "[{\"key\":\"foo\",\"value\":\"42\"},"
                        + " {\"key\":\"bar\",\"value\":\"hello\"}]");
        Map<String, String> out = RealmAttributesUiTab.parseAttrs(model);
        assertEquals("42", out.get("foo"));
        assertEquals("hello", out.get("bar"));
        assertEquals(2, out.size());
    }

    @Test
    void parseAttrs_component_wraps_underlying_exception() {
        ComponentModel model = new ComponentModel();
        // Reserved key "realm" inside the component config must surface as an
        // IllegalArgumentException, not be silently filtered.
        model.put(RealmAttributesUiTab.ATTRS_KEY,
                "[{\"key\":\"realm\",\"value\":\"master\"}]");
        assertThrows(IllegalArgumentException.class,
                () -> RealmAttributesUiTab.parseAttrs(model));
    }

    // --- isSeedableKey ---------------------------------------------------

    @Test
    void isSeedableKey_accepts_normal_keys() {
        assertTrue(RealmAttributesUiTab.isSeedableKey("foo"));
        assertTrue(RealmAttributesUiTab.isSeedableKey("user_id"));
        assertTrue(RealmAttributesUiTab.isSeedableKey("_internal-looking"));
        assertTrue(RealmAttributesUiTab.isSeedableKey("with spaces"));
    }

    @Test
    void isSeedableKey_rejects_null_and_empty() {
        // Both null and empty keys are filtered out (not seedable); the
        // helper is null-safe so callers do not need to guard first.
        assertTrue(!RealmAttributesUiTab.isSeedableKey(null));
        assertTrue(!RealmAttributesUiTab.isSeedableKey(""));
    }

    @Test
    void isSeedableKey_rejects_reserved_route_params() {
        assertTrue(!RealmAttributesUiTab.isSeedableKey("realm"));
        assertTrue(!RealmAttributesUiTab.isSeedableKey("tab"));
    }

    // --- toConsoleJson ----------------------------------------------------

    @Test
    void toConsoleJson_emits_empty_array_for_null_or_empty() {
        assertEquals("[]", RealmAttributesUiTab.toConsoleJson(null));
        assertEquals("[]", RealmAttributesUiTab.toConsoleJson(new LinkedHashMap<>()));
    }

    @Test
    void toConsoleJson_roundtrips_through_parseAttrs() {
        Map<String, String> in = new LinkedHashMap<>();
        in.put("foo", "42");
        in.put("bar", "hello");
        String json = RealmAttributesUiTab.toConsoleJson(in);
        assertEquals(in, RealmAttributesUiTab.parseAttrs(json));
    }

    @Test
    void toConsoleJson_escapes_special_characters() {
        Map<String, String> in = new LinkedHashMap<>();
        in.put("weird\"key\\with\ncontrol", "value\"with\"quotes");
        String json = RealmAttributesUiTab.toConsoleJson(in);
        // Must round-trip cleanly through the console's JSON parser.
        assertEquals(in, RealmAttributesUiTab.parseAttrs(json));
        // Sanity: special characters are properly escaped on the wire.
        assertTrue(json.contains("\\\""));
        assertTrue(json.contains("\\\\"));
    }

    @Test
    void toConsoleJson_serializes_null_values_as_json_null() {
        Map<String, String> in = new LinkedHashMap<>();
        in.put("k", null);
        assertEquals("[{\"key\":\"k\",\"value\":null}]",
                RealmAttributesUiTab.toConsoleJson(in));
    }

    // --- filterSeedAttrs --------------------------------------------------

    @Test
    void filterSeedAttrs_sorts_keys_deterministically() {
        Map<String, String> raw = new HashMap<>();
        raw.put("zeta", "z");
        raw.put("alpha", "a");
        raw.put("mike", "m");
        Map<String, String> out = RealmAttributesUiTab.filterSeedAttrs(raw);
        String json = RealmAttributesUiTab.toConsoleJson(out);
        // Sorted by key: alpha, mike, zeta.
        int a = json.indexOf("alpha");
        int m = json.indexOf("mike");
        int z = json.indexOf("zeta");
        assertTrue(a < m && m < z, "Expected sorted order; got: " + json);
    }

    @Test
    void filterSeedAttrs_returns_empty_for_null_or_empty() {
        assertTrue(RealmAttributesUiTab.filterSeedAttrs(null).isEmpty());
        assertTrue(RealmAttributesUiTab.filterSeedAttrs(new LinkedHashMap<>()).isEmpty());
    }

    @Test
    void filterSeedAttrs_drops_reserved_keys() {
        Map<String, String> raw = new LinkedHashMap<>();
        raw.put("user_id", "u1");
        raw.put("realm", "master");
        raw.put("tab", "attributes");
        raw.put("kept", "yes");
        Map<String, String> out = RealmAttributesUiTab.filterSeedAttrs(raw);
        assertEquals(2, out.size());
        assertEquals("u1", out.get("user_id"));
        assertEquals("yes", out.get("kept"));
        assertTrue(!out.containsKey("realm"));
        assertTrue(!out.containsKey("tab"));
    }

    // --- collectSeedAttrs(RealmModel) -------------------------------------

    @Test
    void collectSeedAttrs_returns_empty_for_null_realm() {
        assertTrue(RealmAttributesUiTab.collectSeedAttrs(null).isEmpty());
    }

    @Test
    void collectSeedAttrs_delegates_to_filterSeedAttrs() {
        Map<String, String> attrs = new LinkedHashMap<>();
        attrs.put("realm", "master");
        attrs.put("tab", "attributes");
        attrs.put("kept", "yes");
        RealmModel realm = stubRealm("realm-id", attrs);
        Map<String, String> out = RealmAttributesUiTab.collectSeedAttrs(realm);
        assertEquals(1, out.size());
        assertEquals("yes", out.get("kept"));
        assertTrue(!out.containsKey("realm"));
        assertTrue(!out.containsKey("tab"));
    }

    @Test
    void collectSeedAttrs_handles_realm_with_null_attributes() {
        RealmModel realm = stubRealm("realm-id", null);
        assertTrue(RealmAttributesUiTab.collectSeedAttrs(realm).isEmpty());
    }

    // --- buildSeedComponentModel -----------------------------------------

    @Test
    void buildSeedComponentModel_populates_required_fields() {
        Map<String, String> attrs = new LinkedHashMap<>();
        attrs.put("foo", "42");
        RealmModel realm = stubRealm("realm-id-123", attrs);

        ComponentModel model = RealmAttributesUiTab.buildSeedComponentModel(realm);

        assertNotNull(model);
        assertEquals(RealmAttributesUiTab.ID, model.getName());
        assertEquals(RealmAttributesUiTab.ID, model.getProviderId());
        assertEquals(RealmAttributesUiTab.TAB_PROVIDER_TYPE, model.getProviderType());
        assertEquals("realm-id-123", model.getParentId());
        // The ATTRS_KEY must hold the console-shaped JSON serialization of the
        // realm's seedable attributes.
        String json = model.get(RealmAttributesUiTab.ATTRS_KEY);
        assertNotNull(json);
        assertEquals(attrs, RealmAttributesUiTab.parseAttrs(json));
    }

    @Test
    void buildSeedComponentModel_serializes_empty_attrs_when_realm_has_none() {
        RealmModel realm = stubRealm("realm-id", new LinkedHashMap<>());
        ComponentModel model = RealmAttributesUiTab.buildSeedComponentModel(realm);
        assertEquals("[]", model.get(RealmAttributesUiTab.ATTRS_KEY));
    }

    // --- helpers ----------------------------------------------------------

    /**
     * Builds a {@link RealmModel} stub via a dynamic {@link Proxy}. Only
     * {@code getId()} and {@code getAttributes()} return the values supplied
     * here; every other method (RealmModel declares hundreds of abstract
     * members through RoleContainerModel, etc.) returns Java defaults via
     * {@link InvocationHandler#invoke}. Keeping the stub Proxy-based avoids
     * pulling in a mocking library and keeps the test focused on the few
     * behaviour paths the production code actually exercises.
     */
    private static RealmModel stubRealm(String id, Map<String, String> attrs) {
        InvocationHandler handler = (proxy, method, args) -> {
            if ("getId".equals(method.getName()) && method.getParameterCount() == 0) {
                return id;
            }
            if ("getAttributes".equals(method.getName()) && method.getParameterCount() == 0) {
                return attrs;
            }
            // Default values for primitives, null for everything else -- good
            // enough because the code under test only touches getId and
            // getAttributes.
            if (method.getReturnType() == boolean.class) {
                return Boolean.FALSE;
            }
            if (method.getReturnType() == int.class) {
                return 0;
            }
            if (method.getReturnType() == long.class) {
                return 0L;
            }
            return null;
        };
        return (RealmModel) Proxy.newProxyInstance(
                RealmAttributesUiTabTest.class.getClassLoader(),
                new Class<?>[]{RealmModel.class},
                handler);
    }
}
