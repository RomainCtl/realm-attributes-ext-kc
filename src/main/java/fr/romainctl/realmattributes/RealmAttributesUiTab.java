/*
 * SPDX-License-Identifier: Apache-2.0
 */
package fr.romainctl.realmattributes;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

import org.keycloak.Config;
import org.keycloak.component.ComponentModel;
import org.keycloak.component.ComponentValidationException;
import org.keycloak.models.KeycloakSession;
import org.keycloak.models.KeycloakSessionFactory;
import org.keycloak.models.RealmModel;
import org.keycloak.models.RealmProvider;
import org.keycloak.models.utils.KeycloakModelUtils;
import org.keycloak.provider.ProviderConfigProperty;
import org.keycloak.provider.ProviderEvent;
import org.keycloak.provider.ProviderEventListener;
import org.keycloak.services.ui.extend.UiTabProvider;
import org.keycloak.services.ui.extend.UiTabProviderFactory;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import org.jboss.logging.Logger;

/**
 * Adds an "Attributes" tab to the Realm settings page of the keycloak.v2 admin
 * console so realm administrators can read and edit realm attributes (the
 * {@code REALM.ATTRIBUTE} column) without resorting to the Admin REST API.
 *
 * <p>Backed by the {@code declarative-ui} experimental feature.
 * The tab is rendered declaratively from {@link #getConfigProperties()}
 * using the built-in {@code Map} component (add/remove key/value rows), so no
 * custom admin theme or REST endpoint is required.
 *
 * <p>Persistence: the admin console saves the form through the standard
 * components REST API, which triggers {@link #onCreate} / {@link #onUpdate} on
 * this factory. Realm attributes are then mirrored to
 * {@link RealmModel#setAttribute} / {@link RealmModel#removeAttribute}.
 * Because {@link #onUpdate} receives both the old and new component models,
 * deleted keys are properly removed from the realm. The route-param names
 * ({@code realm}, {@code tab}) injected by the console are filtered out and
 * never shown in the form.
 *
 * <p>Pre-fill: the declarative-UI console renders the form exclusively from
 * the stored {@link ComponentModel}; realm attributes are not read on tab
 * open. To avoid a blank first load, this factory registers itself as a
 * {@link ProviderEventListener} and:
 * <ul>
 *   <li>on {@link RealmModel.RealmPostCreateEvent} (console-created and
 *       imported realms): creates the component if absent, seeded from the
 *       realm's current attributes;</li>
 *   <li>in {@link #postInit(KeycloakSessionFactory)} (existing realms at
 *       startup): iterates every realm and creates the component if absent,
 *       seeded the same way.</li>
 * </ul>
 * After the first save, the stored component config is the source of truth
 * for the form -- external attribute changes do not auto-refresh (no event is
 * published for arbitrary {@code setAttribute} calls in Keycloak 26.x).
 */
public class RealmAttributesUiTab
        implements UiTabProvider, UiTabProviderFactory<ComponentModel>, ProviderEventListener {

    private static final Logger log = Logger.getLogger(RealmAttributesUiTab.class);

    /** Provider id / tab id / route param value. Re-uses the console's
     *  existing {@code attributes=Attributes} i18n key for a translated
     *  tab title. */
    static final String ID = "attributes";

    /** Name of the single configuration property (also the Map field name). */
    static final String ATTRS_KEY = "attributes";

    /** {@code providerType} value for declarative-UI tab components. Must match
     *  the {@link org.keycloak.services.ui.extend.UiTabProvider} SPI so the
     *  console's {@code GET /admin/realms/{realm}/components?type=...} finds
     *  the seeded component. */
    static final String TAB_PROVIDER_TYPE = UiTabProvider.class.getCanonicalName();//"org.keycloak.services.ui.extend.UiTabProvider";

    /** Reserved config keys injected by the console via {@link #getParams()} --
     *  never treated as realm attributes. */
    static final String PARAM_REALM = "realm";
    static final String PARAM_TAB = "tab";

    private static final ObjectMapper MAPPER = new ObjectMapper();

    /** Re-entrancy guard for the seed-on-empty logic in {@link #onCreate}. */
    private static final ThreadLocal<Boolean> SEEDING = ThreadLocal.withInitial(() -> Boolean.FALSE);

    /** Factory reference captured in {@link #postInit(KeycloakSessionFactory)}
     *  so {@link #close()} can unregister the listener. */
    private KeycloakSessionFactory factory;

    // --- ConfiguredProvider --------------------------------------------------

    @Override
    public String getHelpText() {
        return "Edit realm attributes (the REALM.ATTRIBUTE column) through "
                + "the keycloak.v2 admin console.";
    }

    // --- UiTabProviderFactory ------------------------------------------------

    @Override
    public String getId() {
        return ID;
    }

    @Override
    public String getPath() {
        return "/:realm/realm-settings/:tab?";
    }

    @Override
    public Map<String, String> getParams() {
        Map<String, String> params = new LinkedHashMap<>();
        params.put(PARAM_TAB, ID);
        return params;
    }

    @Override
    public List<ProviderConfigProperty> getConfigProperties() {
        ProviderConfigProperty property = new ProviderConfigProperty();
        property.setName(ATTRS_KEY);
        property.setLabel(ID);
        property.setHelpText(
                "Realm attributes as a list of key/value pairs. "
                        + "Saved values are mirrored to the REALM.ATTRIBUTE column.");
        property.setType(ProviderConfigProperty.MAP_TYPE);
        return Collections.singletonList(property);
    }

    // --- ProviderFactory lifecycle ------------------------------------------

    @Override
    public void init(Config.Scope config) {
        // Stateless provider; nothing to initialize.
    }

    @Override
    public void postInit(KeycloakSessionFactory factory) {
        this.factory = factory;
        factory.register(this);
        log.debugf("Registered %s as ProviderEventListener.", getClass().getSimpleName());

        // Backfill any realm that already exists when the server starts, so
        // existing realms get a seeded component too (RealmPostCreateEvent
        // only fires for realms created after this code ships). The JPA
        // RealmProvider requires an active transaction, so delegate to
        // KeycloakModelUtils.runJobInTransaction which wraps the task in one.
        try {
            int[] seededCount = new int[]{0};
            KeycloakModelUtils.runJobInTransaction(factory, session -> {
                RealmProvider realms = session.realms();
                realms.getRealmsStream().forEach(realm -> {
                    try {
                        if (ensureSeededComponent(session, realm)) {
                            seededCount[0]++;
                        }
                    } catch (RuntimeException e) {
                        log.warnf(e, "Failed to seed %s component for realm %s",
                                ID, realm.getName());
                    }
                });
            });
            if (seededCount[0] > 0) {
                log.infof("Backfill: seeded '%s' UI tab component for %d existing realm(s).",
                        ID, seededCount[0]);
            } else {
                log.debugf("Backfill: no realms needed '%s' UI tab component seeding.", ID);
            }
        } catch (RuntimeException e) {
            // Don't break boot if the DB is not yet reachable or the schema is
            // being migrated; new realms will still be seeded by the event
            // listener and any unseeded existing realm can be re-triggered by
            // restarting the server.
            log.warnf(e, "Startup backfill of '%s' UI tab components failed; "
                    + "new realms will still be seeded on creation.", ID);
        }
    }

    @Override
    public void close() {
        if (factory != null) {
            try {
                factory.unregister(this);
            } catch (RuntimeException e) {
                log.debugf(e, "Error unregistering %s from session factory.",
                        getClass().getSimpleName());
            }
            factory = null;
        }
    }

    @Override
    public UiTabProvider create(KeycloakSession session) {
        // UiTabProviderFactory defaults this to null; the console never
        // instantiates UiTabProviders (the tab is rendered declaratively).
        return this;
    }

    // --- ProviderEventListener ----------------------------------------------

    @Override
    public void onEvent(ProviderEvent event) {
        if (event instanceof RealmModel.RealmPostCreateEvent) {
            RealmModel.RealmPostCreateEvent e = (RealmModel.RealmPostCreateEvent) event;
            try {
                ensureSeededComponent(e.getKeycloakSession(), e.getCreatedRealm());
            } catch (RuntimeException ex) {
                log.warnf(ex, "Failed to seed '%s' component on RealmPostCreateEvent for realm %s",
                        ID, e.getCreatedRealm().getName());
            }
        }
    }

    // --- ComponentFactory callbacks -----------------------------------------

    @Override
    public void onCreate(KeycloakSession session, RealmModel realm, ComponentModel model) {
        Map<String, String> attrs = parseAttrs(model);

        // Seed-on-empty: if the admin saved an empty form on first creation,
        // mirror the realm's current attributes into the component config so
        // the tab is not blank the next time it is opened. Skipped on
        // re-entry to avoid loops.
        if (attrs.isEmpty() && !SEEDING.get()) {
            Map<String, String> seedAttrs = collectSeedAttrs(realm);
            if (!seedAttrs.isEmpty()) {
                SEEDING.set(Boolean.TRUE);
                try {
                    model.put(ATTRS_KEY, toConsoleJson(seedAttrs));
                    realm.updateComponent(model);
                    log.infof("Seeded '%s' component from realm attributes on first create (%d attrs) for realm %s.",
                            ID, seedAttrs.size(), realm.getName());
                } finally {
                    SEEDING.set(Boolean.FALSE);
                }
            }
        }

        for (Map.Entry<String, String> entry : attrs.entrySet()) {
            realm.setAttribute(entry.getKey(), entry.getValue());
        }
    }

    @Override
    public void onUpdate(KeycloakSession session, RealmModel realm,
                         ComponentModel oldModel, ComponentModel newModel) {
        Map<String, String> oldAttrs = parseAttrs(oldModel);
        Map<String, String> newAttrs = parseAttrs(newModel);

        // Drop keys the admin removed from the form (only those we manage).
        for (String key : oldAttrs.keySet()) {
            if (!newAttrs.containsKey(key)) {
                realm.removeAttribute(key);
            }
        }
        // Apply the current form values.
        for (Map.Entry<String, String> entry : newAttrs.entrySet()) {
            realm.setAttribute(entry.getKey(), entry.getValue());
        }
    }

    @Override
    public void preRemove(KeycloakSession session, RealmModel realm, ComponentModel model) {
        Map<String, String> attrs = parseAttrs(model);
        for (String key : attrs.keySet()) {
            realm.removeAttribute(key);
        }
    }

    @Override
    public void validateConfiguration(KeycloakSession session, RealmModel realm, ComponentModel model)
            throws ComponentValidationException {
        try {
            parseAttrs(model);
        } catch (RuntimeException e) {
            throw new ComponentValidationException(e.getMessage(), e);
        }
    }

    // --- Seeding helpers ----------------------------------------------------

    /**
     * Creates the {@code attributes} UI tab component for the given realm if
     * it does not already exist, seeded from the realm's current attributes.
     * Returns {@code true} when a new component was created.
     */
    private boolean ensureSeededComponent(KeycloakSession session, RealmModel realm) {
        if (realm == null) {
            return false;
        }
        String realmId = realm.getId();
        boolean alreadyExists = realm.getComponentsStream(realmId, TAB_PROVIDER_TYPE)
                .anyMatch(c -> ID.equals(c.getProviderId()));
        if (alreadyExists) {
            return false;
        }
        ComponentModel seed = buildSeedComponentModel(realm);
        // addComponentModel fires validateConfiguration + onCreate; both are
        // idempotent for the seeded content (onCreate re-applies the same
        // attributes and never removes).
        realm.addComponentModel(seed);
        log.infof("Seeded '%s' UI tab component for realm %s (%d seeded attribute(s)).",
                ID, realm.getName(), collectSeedAttrs(realm).size());
        return true;
    }

    /**
     * Builds a fresh {@link ComponentModel} for the seeded tab component.
     * Visible for testing.
     */
    static ComponentModel buildSeedComponentModel(RealmModel realm) {
        ComponentModel model = new ComponentModel();
        model.setName(ID);
        model.setProviderId(ID);
        model.setProviderType(TAB_PROVIDER_TYPE);
        model.setParentId(realm.getId());
        model.put(ATTRS_KEY, toConsoleJson(collectSeedAttrs(realm)));
        return model;
    }

    /**
     * Filters the realm's attributes down to the keys that are safe to show
     * in the form: non-empty, not prefixed with {@code _} (Keycloak internal),
     * and not equal to a reserved route-param name. Sorted by key for
     * deterministic JSON output. Visible for testing.
     */
    static Map<String, String> collectSeedAttrs(RealmModel realm) {
        return filterSeedAttrs(realm == null ? null : realm.getAttributes());
    }

    /** Pure-data version of {@link #collectSeedAttrs(RealmModel)}, useful for
     *  unit tests without a {@link RealmModel} stub. Visible for testing. */
    static Map<String, String> filterSeedAttrs(Map<String, String> all) {
        Map<String, String> out = new LinkedHashMap<>();
        if (all == null) {
            return out;
        }
        Map<String, String> sorted = new TreeMap<>(all);
        for (Map.Entry<String, String> e : sorted.entrySet()) {
            if (isSeedableKey(e.getKey())) {
                out.put(e.getKey(), e.getValue());
            }
        }
        return out;
    }

    static boolean isSeedableKey(String key) {
        if (key == null || key.isEmpty()) {
            return false;
        }
        // If is reserved key
        if (PARAM_REALM.equals(key) || PARAM_TAB.equals(key)) {
            return false;
        }
        return true;
    }

    /**
     * Serializes a key/value map into the exact JSON shape the console's
     * {@code MapComponent} writes: {@code [{"key":"...","value":"..."}, ...]}.
     * Empty maps yield {@code "[]"}. Visible for testing.
     */
    static String toConsoleJson(Map<String, String> attrs) {
        ArrayNode array = MAPPER.createArrayNode();
        if (attrs != null) {
            for (Map.Entry<String, String> e : attrs.entrySet()) {
                ObjectNode node = array.addObject();
                node.put("key", e.getKey());
                // Use putNull for null values so JSON.parse + asText round-trip
                // back to null on the console side.
                if (e.getValue() == null) {
                    node.putNull("value");
                } else {
                    node.put("value", e.getValue());
                }
            }
        }
        try {
            return MAPPER.writeValueAsString(array);
        } catch (Exception e) {
            throw new RuntimeException("Failed to serialize realm attributes for seed", e);
        }
    }

    // --- Parsing helpers (unchanged behavior) -------------------------------

    /**
     * Parses the Map component value into a key->value map. Route params
     * injected by the console ({@code realm}, {@code tab}) are filtered out so
     * they never leak into realm attributes.
     */
    static Map<String, String> parseAttrs(ComponentModel model) {
        if (model == null) {
            return new LinkedHashMap<>();
        }
        return parseAttrs(model.get(ATTRS_KEY));
    }

    /**
     * Parses a JSON array of {@code {key,value}} objects into a key->value map.
     * {@code null}, blank or non-array input yields an empty map.
     */
    static Map<String, String> parseAttrs(String json) {
        Map<String, String> out = new LinkedHashMap<>();
        if (json == null || json.isBlank()) {
            return out;
        }
        try {
            ArrayNode root = (ArrayNode) MAPPER.readTree(json);
            for (var row : root) {
                String key = row.path("key").asText("");
                String value = row.path("value").asText(null);
                if (!isSeedableKey(key)) {
                    throw new IllegalArgumentException("key '"+key+"' not acceptable as key!");
                }
                out.put(key, value);
            }
        } catch (RuntimeException e) {
            throw e;
        } catch (Exception e) {
            throw new RuntimeException(
                    "Invalid attributes JSON in component config: " + json, e);
        }
        return out;
    }
}
